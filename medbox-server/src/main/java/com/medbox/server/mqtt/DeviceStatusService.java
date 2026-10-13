package com.medbox.server.mqtt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.mapper.DeviceMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 设备在线状态维护（见文档 03 第 1 章 / 06 的 2.3）。
 *
 * <p>两条来源，互为补充：
 * <ol>
 *   <li><b>遗嘱 / status topic</b>：设备连上即发 retained 的 {@code {"online":true}}，
 *       异常掉线时 broker 自动代发 {@code {"online":false}} —— 离线感知最及时；</li>
 *   <li><b>任意上行消息</b>：能发消息说明设备还活着，顺带刷新 {@code last_heartbeat}。
 *       设备固件若没实现 status retained，靠这条也能维持在线态。</li>
 * </ol>
 *
 * <p><b>传感器子设备不在这里管</b>：它们没有 MQTT 连接，由 {@code up/sensor/heartbeat}
 * 每 60s 汇总上报，180s 未上报判离线 —— 那是 {@code feat/device-sensor} 的活。
 *
 * <p>设备没登记时（还没走 {@code POST /devices/register}）只 DEBUG 不抛：
 * 局域网上任何 clientId 都能连，未登记设备发消息是常态，不该刷 ERROR 日志。
 *
 * <p><b>{@code feat/device-core} 新增的跃变判定</b>：{@code markOnline} 在每次上行都会被调用
 * （{@code markAlive}），而心跳只有 60s 一次 —— 若每次都写一条状态事件，
 * 单台设备一天就是 1440 条噪声，事件流水会被心跳灌满。故先读出当前状态，
 * <b>只有真的变了才回调监听者</b>（写事件流水 + 推 DEVICE_STATUS）。
 *
 * <p><b>为什么是先 SELECT 再 UPDATE，而不是给 UPDATE 加 {@code online_state != 目标值}</b>：
 * 后者虽然一次 SQL，但会让"刷新心跳"一起失效 —— 设备已 ONLINE 时条件不成立，
 * {@code last_heartbeat} 就再也不更新了，反而丢掉了心跳这个关键信息。
 * 这里宁可多一次主键等值查询（设备表个位数行），也要保住心跳刷新。
 */
public class DeviceStatusService {

    private static final Logger log = LoggerFactory.getLogger(DeviceStatusService.class);

    private final DeviceMapper deviceMapper;
    private final Clock clock;
    private final List<DeviceStatusChangeListener> listeners;

    public DeviceStatusService(DeviceMapper deviceMapper) {
        this(deviceMapper, Clock.systemUTC(), List.of());
    }

    public DeviceStatusService(DeviceMapper deviceMapper, Clock clock) {
        this(deviceMapper, clock, List.of());
    }

    public DeviceStatusService(DeviceMapper deviceMapper,
                               Clock clock,
                               List<DeviceStatusChangeListener> listeners) {
        this.deviceMapper = deviceMapper;
        this.clock = clock;
        this.listeners = listeners == null ? List.of() : List.copyOf(listeners);
    }

    /** 设备上线（收到 status=true）。 */
    public boolean markOnline(String deviceId) {
        return update(deviceId, OnlineState.ONLINE, true);
    }

    /** 设备离线（收到 LWT status=false，或 broker 说连接断开）。 */
    public boolean markOffline(String deviceId) {
        return update(deviceId, OnlineState.OFFLINE, false);
    }

    /**
     * 收到任意上行消息：设备还活着，在线态与心跳一起刷新。
     *
     * @return true 表示确实更新到了一台已登记设备
     */
    public boolean markAlive(String deviceId) {
        return markOnline(deviceId);
    }

    private boolean update(String deviceId, OnlineState target, boolean refreshHeartbeat) {
        Device device;
        LambdaUpdateWrapper<Device> wrapper = new LambdaUpdateWrapper<Device>()
                .eq(Device::getDeviceId, deviceId)
                .set(Device::getOnlineState, target);
        if (refreshHeartbeat) {
            wrapper.set(Device::getLastHeartbeat, OffsetDateTime.now(clock));
        }
        try {
            device = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                    .eq(Device::getDeviceId, deviceId));
            if (device == null) {
                log.debug("设备未登记，跳过在线状态更新 deviceId={}", deviceId);
                return false;
            }
            if (deviceMapper.update(null, wrapper) == 0) {
                return false;
            }
        } catch (RuntimeException ex) {
            // 数据库一时不可用不该影响消息通道；状态可以稍后再修
            log.warn("更新设备在线状态失败 deviceId={}：{}", deviceId, ex.getMessage());
            return false;
        }

        // 副作用只在跃变时触发，且**放在数据库 try 之外**：状态已经落库了，
        // 监听者（写事件流水 / 推 WS）出问题不该反过来让本次更新返回 false
        if (device.getOnlineState() != target) {
            log.info("设备在线状态变更 deviceId={} {} -> {}", deviceId, device.getOnlineState(), target);
            notifyListeners(device, target);
        }
        return true;
    }

    /** 广播跃变事件。单个监听者抛异常只记日志，不影响其它监听者，也不影响返回值。 */
    private void notifyListeners(Device device, OnlineState newState) {
        for (DeviceStatusChangeListener listener : listeners) {
            try {
                listener.onStatusChanged(device, newState);
            } catch (RuntimeException ex) {
                log.warn("设备状态跃变监听者执行失败 listener={} deviceId={}：{}",
                        listener.getClass().getSimpleName(), device.getDeviceId(), ex.getMessage());
            }
        }
    }
}
