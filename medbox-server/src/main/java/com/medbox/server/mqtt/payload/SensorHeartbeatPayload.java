package com.medbox.server.mqtt.payload;

import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 上行·传感器心跳 {@code up/sensor/heartbeat}（见文档 03 第 3 章）。
 *
 * <p><b>只有主控有 MQTT 连接</b>，子设备没有自己的遗嘱，因此由主控每 60s 汇总上报一次，
 * 后端据此更新各子设备的 {@code last_heartbeat} 与 {@code online_state}；
 * <b>连续 3 个周期（180s）未上报判离线，且子设备离线不生成 DEVICE_OFFLINE 告警</b>
 * （业务处理在 {@code feat/device-sensor}）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SensorHeartbeatPayload extends BaseUpPayload {

    private List<SensorState> sensors;

    /** 单个子设备的在线状态。 */
    @Data
    public static class SensorState {

        /** 子设备 ID（= device.device_id）。 */
        private String sensorId;

        private Boolean online;
    }
}
