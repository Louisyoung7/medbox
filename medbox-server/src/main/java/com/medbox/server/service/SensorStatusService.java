package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.medbox.server.config.DeviceProperties;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mqtt.DeviceStatusChangeListener;
import com.medbox.server.mqtt.payload.SensorHeartbeatPayload;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 传感器子设备的在线状态维护（{@code feat/device-sensor}，见文档 03 第 3 章 / 06 的 2.3）。
 *
 * <p><b>为什么独立于 {@code DeviceStatusService}</b>：那个类在 {@code MqttConfig} 里装配，
 * {@code medbox.mqtt.enabled=false} 时不存在，而离线扫描是纯服务层定时任务，不该被 MQTT 开关拖死。
 * 但<b>跃变通知复用同一套 {@link DeviceStatusChangeListener}</b>：{@code DeviceStatusEventBridge}
 * 会自动接上，写事件流水 + 推 {@code DEVICE_STATUS}（payload 已带 {@code parentDeviceId}），零改动。
 *
 * <p><b>两条判定路径（规则表见 07 清单本分支条目）</b>：
 * <ol>
 *   <li><b>心跳汇总 {@link #applyHeartbeat}</b>：主控每 60s 上报一次各子设备在线与否。
 *       {@code online=true} 置 ONLINE 并刷新 {@code last_heartbeat}；{@code online=false} 立即置 OFFLINE
 *       （不刷心跳——它语义是"传感器最后活着的时刻"）；</li>
 *   <li><b>离线扫描 {@link #scanOffline}</b>：每 {@code medbox.device.sensor-scan-interval-ms} 扫一次，
 *       ONLINE 但 {@code last_heartbeat} 超过 {@code medbox.device.sensor-offline-after-ms}（180s）
 *       未刷新的子设备判 OFFLINE——覆盖"整个传感器从心跳汇总里消失"的情形。</li>
 * </ol>
 *
 * <p><b>明确不做</b>：子设备离线<b>不生成 {@code DEVICE_OFFLINE} 告警</b>（文档 06 的 2.3），
 * 本类因此不持有 {@code AlarmMapper}；告警归主控专属，在 {@code feat/device-offline-alarm}。
 *
 * <p><b>只有跃变才通知监听者</b>：心跳 60s 一次，若每次都写事件流水，单台传感器一天 1440 条噪声
 * （与主控 {@code DeviceStatusService} 同一口径）。已 ONLINE 时只刷 {@code last_heartbeat}。
 */
@Service
public class SensorStatusService {

    private static final Logger log = LoggerFactory.getLogger(SensorStatusService.class);

    private final DeviceMapper deviceMapper;
    private final DeviceProperties props;
    private final List<DeviceStatusChangeListener> listeners;
    private final Clock clock;

    /** 生产用构造器（双构造器必须显式 {@code @Autowired}，同 {@code DeviceService}）。 */
    @Autowired
    public SensorStatusService(DeviceMapper deviceMapper,
                               DeviceProperties props,
                               List<DeviceStatusChangeListener> listeners) {
        this(deviceMapper, props, listeners, Clock.systemUTC());
    }

    /** 测试用构造器：注入固定时钟。 */
    public SensorStatusService(DeviceMapper deviceMapper,
                               DeviceProperties props,
                               List<DeviceStatusChangeListener> listeners,
                               Clock clock) {
        this.deviceMapper = deviceMapper;
        this.props = props;
        this.listeners = listeners == null ? List.of() : List.copyOf(listeners);
        this.clock = clock;
    }

    /**
     * 消费一次 {@code up/sensor/heartbeat}：按上报逐个子设备更新在线状态。
     *
     * <p>payload 已去重（{@code MqttInboundGateway} 保证同一 msgId 只进来一次），这里不判幂等。
     * 单条子设备处理失败只记日志，不影响同帧其余子设备。
     *
     * @param mainDeviceId 上报的主控（topic 第 3 层）
     * @param states       主控汇总的各子设备状态
     */
    public void applyHeartbeat(String mainDeviceId, List<SensorHeartbeatPayload.SensorState> states) {
        if (states == null || states.isEmpty()) {
            return;
        }
        Device main = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                .eq(Device::getDeviceId, mainDeviceId));
        if (main == null || main.getDeviceType() != DeviceType.MAIN) {
            // 未登记设备也能连 EMQX（本期匿名连接），是常态，不刷 warn
            log.debug("传感器心跳的主控未登记或非主控，忽略 mainDeviceId={}", mainDeviceId);
            return;
        }

        Map<String, Device> children = deviceMapper.selectList(new LambdaQueryWrapper<Device>()
                        .eq(Device::getParentDeviceId, mainDeviceId))
                .stream()
                .collect(Collectors.toMap(Device::getDeviceId, Function.identity()));

        OffsetDateTime now = OffsetDateTime.now(clock);
        for (SensorHeartbeatPayload.SensorState state : states) {
            try {
                applyOne(mainDeviceId, children, state, now);
            } catch (RuntimeException ex) {
                log.warn("处理子设备心跳失败 mainDeviceId={} sensorId={}：{}",
                        mainDeviceId, state == null ? null : state.getSensorId(), ex.getMessage());
            }
        }
    }

    /** 定时入口：每 {@code medbox.device.sensor-scan-interval-ms} 扫一次。 */
    @Scheduled(fixedDelayString = "${medbox.device.sensor-scan-interval-ms:60000}")
    public void scheduledScan() {
        try {
            scanOffline(clock);
        } catch (RuntimeException ex) {
            // 旁路任务：本轮失败只记日志，不能让调度线程死掉
            log.warn("传感器离线扫描本轮失败：{}", ex.getMessage());
        }
    }

    /**
     * 离线扫描：ONLINE 但超过阈值未刷新心跳的子设备判 OFFLINE。
     *
     * <p>{@code last_heartbeat} 为 NULL 的 ONLINE 子设备是防御性分支（正常不会出现：
     * 上线必经 {@link #applyHeartbeat} 会写心跳），同样判离线。
     *
     * @param scanClock 时钟（测试注入固定时钟）
     * @return 本轮被判定离线的子设备数
     */
    public int scanOffline(Clock scanClock) {
        OffsetDateTime threshold = OffsetDateTime.now(scanClock)
                .minus(props.getSensorOfflineAfterMs(), ChronoUnit.MILLIS);
        List<Device> stale = deviceMapper.selectList(new LambdaQueryWrapper<Device>()
                .ne(Device::getDeviceType, DeviceType.MAIN)
                .eq(Device::getOnlineState, OnlineState.ONLINE)
                .and(w -> w.lt(Device::getLastHeartbeat, threshold)
                        .or()
                        .isNull(Device::getLastHeartbeat)));
        if (stale == null || stale.isEmpty()) {
            return 0;
        }
        int changed = 0;
        for (Device sensor : stale) {
            try {
                if (updateState(sensor, OnlineState.OFFLINE, null)) {
                    changed++;
                }
            } catch (RuntimeException ex) {
                log.warn("子设备离线判定失败 deviceId={}：{}", sensor.getDeviceId(), ex.getMessage());
            }
        }
        if (changed > 0) {
            log.info("传感器离线扫描：本轮 {} 个子设备判离线", changed);
        }
        return changed;
    }

    // ------------------------------------------------------------------ 内部方法

    private void applyOne(String mainDeviceId,
                          Map<String, Device> children,
                          SensorHeartbeatPayload.SensorState state,
                          OffsetDateTime now) {
        if (state == null || state.getSensorId() == null || state.getSensorId().isBlank()) {
            log.warn("传感器心跳缺少 sensorId，已跳过 mainDeviceId={}", mainDeviceId);
            return;
        }
        Device sensor = children.get(state.getSensorId());
        if (sensor == null) {
            // 未登记 / 挂在别的主控下：不自动建行，防止脏数据混进设备档案
            log.warn("传感器心跳携带未登记子设备，已跳过 mainDeviceId={} sensorId={}",
                    mainDeviceId, state.getSensorId());
            return;
        }
        if (Boolean.TRUE.equals(state.getOnline())) {
            updateState(sensor, OnlineState.ONLINE, now);
        } else {
            // 主控明确报告子设备不在线：立即判离线，不等 180s；不刷心跳
            updateState(sensor, OnlineState.OFFLINE, null);
        }
    }

    /**
     * 更新子设备状态，只有跃变才通知监听者。
     *
     * @param heartbeat 非空时顺带刷新 {@code last_heartbeat}（上线语义）；离线不刷
     * @return true 表示确实更新到了该行
     */
    private boolean updateState(Device sensor, OnlineState target, OffsetDateTime heartbeat) {
        if (sensor.getOnlineState() == target && target == OnlineState.OFFLINE) {
            return true; // 已离线：无状态可写、无心跳可刷，直接算成功（幂等）
        }
        LambdaUpdateWrapper<Device> wrapper = new LambdaUpdateWrapper<Device>()
                .eq(Device::getDeviceId, sensor.getDeviceId())
                .set(Device::getOnlineState, target);
        if (heartbeat != null) {
            wrapper.set(Device::getLastHeartbeat, heartbeat);
        }
        if (deviceMapper.update(null, wrapper) == 0) {
            return false;
        }
        if (sensor.getOnlineState() != target) {
            log.info("传感器在线状态变更 deviceId={} {} -> {}",
                    sensor.getDeviceId(), sensor.getOnlineState(), target);
            notifyListeners(sensor, target);
        }
        return true;
    }

    /** 广播跃变事件。单个监听者抛异常只记日志，不影响其余监听者与本次更新结果。 */
    private void notifyListeners(Device sensor, OnlineState newState) {
        for (DeviceStatusChangeListener listener : listeners) {
            try {
                listener.onStatusChanged(sensor, newState);
            } catch (RuntimeException ex) {
                log.warn("传感器状态跃变监听者执行失败 listener={} deviceId={}：{}",
                        listener.getClass().getSimpleName(), sensor.getDeviceId(), ex.getMessage());
            }
        }
    }
}
