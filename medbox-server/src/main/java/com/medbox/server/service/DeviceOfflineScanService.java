package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.medbox.server.config.DeviceProperties;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mqtt.DeviceStatusChangeListener;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 主控（MAIN）设备心跳超时离线扫描（feat/alarm-device-offline，见文档 03 第 1 章 KeepAlive / 06 的 2.3）。
 *
 * <p><b>为什么独立于 {@code DeviceStatusService}</b>：那个类在 mqtt 包、受 {@code medbox.mqtt.enabled}
 * 条件装配，而本扫描是纯服务层定时任务，不应被 MQTT 开关拖死（与 {@code SensorStatusService} 同一考量）。
 * 但跃变通知<b>复用同一套 {@link DeviceStatusChangeListener}</b>：{@code DeviceStatusEventBridge}
 * 写事件流水 + 推 DEVICE_STATUS，{@code DeviceOfflineAlarmListener} 生成告警，零改动接入。
 *
 * <p><b>两条离线判定路径（方案 A）</b>：
 * <ol>
 *   <li><b>LWT 即时离线</b>：broker 检测到断连代发 {@code status=false} → {@code DeviceStatusService.markOffline}
 *       → 已回调监听者；</li>
 *   <li><b>心跳超时扫描（本类）</b>：每 {@code medbox.device.main-scan-interval-ms} 扫一次，
 *       MAIN 且 ONLINE 且 {@code last_heartbeat} 超过 {@code medbox.device.main-offline-after-ms}（默认 180s）
 *       或 NULL 者判 OFFLINE——覆盖"KeepAlive 静默掉线、LWT 未触发"的情形。</li>
 * </ol>
 *
 * <p><b>只查 MAIN</b>：与 {@code SensorStatusService}（只查 {@code ne(MAIN)}）互补，互不干扰；
 * 子设备离线仍由 {@code SensorStatusService} 负责且不告警。
 *
 * <p><b>只有跃变才通知监听者</b>：已 OFFLINE 的设备再次扫到不会再 notify（幂等），避免重复告警。
 */
@Service
public class DeviceOfflineScanService {

    private static final Logger log = LoggerFactory.getLogger(DeviceOfflineScanService.class);

    private final DeviceMapper deviceMapper;
    private final DeviceProperties props;
    private final List<DeviceStatusChangeListener> listeners;
    private final Clock clock;

    @Autowired
    public DeviceOfflineScanService(DeviceMapper deviceMapper, DeviceProperties props,
                                    List<DeviceStatusChangeListener> listeners) {
        this(deviceMapper, props, listeners, Clock.systemUTC());
    }

    public DeviceOfflineScanService(DeviceMapper deviceMapper, DeviceProperties props,
                                    List<DeviceStatusChangeListener> listeners, Clock clock) {
        this.deviceMapper = deviceMapper;
        this.props = props;
        this.listeners = listeners == null ? List.of() : List.copyOf(listeners);
        this.clock = clock;
    }

    /** 定时入口：每 {@code medbox.device.main-scan-interval-ms} 扫一次。 */
    @Scheduled(fixedDelayString = "${medbox.device.main-scan-interval-ms:60000}")
    public void scheduledScan() {
        try {
            scanOffline(clock);
        } catch (RuntimeException ex) {
            // 旁路任务：本轮失败只记日志，不能让调度线程死掉
            log.warn("主控离线扫描本轮失败：{}", ex.getMessage());
        }
    }

    /**
     * 离线扫描：MAIN 且 ONLINE 但超过阈值未刷新心跳（或心跳为 NULL）判 OFFLINE。
     *
     * @param scanClock 时钟（测试注入固定时钟）
     * @return 本轮被判定离线的主控数
     */
    public int scanOffline(Clock scanClock) {
        OffsetDateTime threshold = OffsetDateTime.now(scanClock)
                .minus(props.getMainOfflineAfterMs(), ChronoUnit.MILLIS);
        List<Device> stale = deviceMapper.selectList(new LambdaQueryWrapper<Device>()
                .eq(Device::getDeviceType, DeviceType.MAIN)
                .eq(Device::getOnlineState, OnlineState.ONLINE)
                .and(w -> w.lt(Device::getLastHeartbeat, threshold)
                        .or()
                        .isNull(Device::getLastHeartbeat)));
        if (stale == null || stale.isEmpty()) {
            return 0;
        }
        int changed = 0;
        for (Device main : stale) {
            try {
                if (updateState(main, OnlineState.OFFLINE)) {
                    changed++;
                }
            } catch (RuntimeException ex) {
                log.warn("主控离线判定失败 deviceId={}：{}", main.getDeviceId(), ex.getMessage());
            }
        }
        if (changed > 0) {
            log.info("主控离线扫描：本轮 {} 个主控判离线", changed);
        }
        return changed;
    }

    /**
     * 更新主控状态，只有跃变才通知监听者。
     *
     * @return true 表示确实更新到了该行（含已离线幂等）
     */
    private boolean updateState(Device device, OnlineState target) {
        if (device.getOnlineState() == target) {
            return false; // 已经是目标态，无状态可写，不算变更、不重复回调（幂等）
        }
        LambdaUpdateWrapper<Device> wrapper = new LambdaUpdateWrapper<Device>()
                .eq(Device::getDeviceId, device.getDeviceId())
                .set(Device::getOnlineState, target);
        try {
            if (deviceMapper.update(null, wrapper) == 0) {
                return false;
            }
        } catch (RuntimeException ex) {
            log.warn("更新主控在线状态失败 deviceId={}：{}", device.getDeviceId(), ex.getMessage());
            return false;
        }
        if (device.getOnlineState() != target) {
            log.info("主控在线状态变更 deviceId={} {} -> {}", device.getDeviceId(), device.getOnlineState(), target);
            notifyListeners(device, target);
        }
        return true;
    }

    /** 广播跃变事件。单个监听者抛异常只记日志，不影响其余监听者与本次更新结果。 */
    private void notifyListeners(Device device, OnlineState newState) {
        for (DeviceStatusChangeListener listener : listeners) {
            try {
                listener.onStatusChanged(device, newState);
            } catch (RuntimeException ex) {
                log.warn("主控状态跃变监听者执行失败 listener={} deviceId={}：{}",
                        listener.getClass().getSimpleName(), device.getDeviceId(), ex.getMessage());
            }
        }
    }
}
