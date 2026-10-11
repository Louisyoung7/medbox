package com.medbox.server.mqtt;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.medbox.server.domain.Device;
import com.medbox.server.mapper.DeviceMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
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
 */
public class DeviceStatusService {

    private static final Logger log = LoggerFactory.getLogger(DeviceStatusService.class);

    private static final String ONLINE = "ONLINE";
    private static final String OFFLINE = "OFFLINE";

    private final DeviceMapper deviceMapper;
    private final Clock clock;

    public DeviceStatusService(DeviceMapper deviceMapper) {
        this(deviceMapper, Clock.systemUTC());
    }

    public DeviceStatusService(DeviceMapper deviceMapper, Clock clock) {
        this.deviceMapper = deviceMapper;
        this.clock = clock;
    }

    /** 设备上线（收到 status=true）。 */
    public boolean markOnline(String deviceId) {
        return update(deviceId, new LambdaUpdateWrapper<Device>()
            .eq(Device::getDeviceId, deviceId)
            .set(Device::getOnlineState, ONLINE)
            .set(Device::getLastHeartbeat, OffsetDateTime.now(clock)));
    }

    /** 设备离线（收到 LWT status=false，或 broker 说连接断开）。 */
    public boolean markOffline(String deviceId) {
        return update(deviceId, new LambdaUpdateWrapper<Device>()
            .eq(Device::getDeviceId, deviceId)
            .set(Device::getOnlineState, OFFLINE));
    }

    /**
     * 收到任意上行消息：设备还活着，在线态与心跳一起刷新。
     *
     * @return true 表示确实更新到了一台已登记设备
     */
    public boolean markAlive(String deviceId) {
        return markOnline(deviceId);
    }

    private boolean update(String deviceId, LambdaUpdateWrapper<Device> wrapper) {
        try {
            int rows = deviceMapper.update(null, wrapper);
            if (rows == 0) {
                log.debug("设备未登记，跳过在线状态更新 deviceId={}", deviceId);
                return false;
            }
            return true;
        } catch (RuntimeException ex) {
            // 数据库一时不可用不该影响消息通道；状态可以稍后再修
            log.warn("更新设备在线状态失败 deviceId={}：{}", deviceId, ex.getMessage());
            return false;
        }
    }
}
