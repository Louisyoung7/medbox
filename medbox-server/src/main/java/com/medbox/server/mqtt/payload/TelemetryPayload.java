package com.medbox.server.mqtt.payload;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 上行·环境遥测 {@code up/telemetry}（见文档 03 第 3 章）。
 *
 * <p><b>每个传感器各发一条，只带自己负责的字段</b>：温湿度传感器给
 * {@code temperature} / {@code humidity}，光照传感器给 {@code lux}，其余为 {@code null}。
 * 谁上报由 {@code sensorId} 标明（= 该传感器子设备的 {@code device_id}），落到
 * {@code env_sample} 时只更新对应列 —— 业务处理在 {@code feat/env-core}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TelemetryPayload extends BaseUpPayload {

    /** 上报它的传感器子设备 ID（= device.device_id）。 */
    private String sensorId;

    private Double temperature;

    private Double humidity;

    /** 光照强度。 */
    private Double lux;
}
