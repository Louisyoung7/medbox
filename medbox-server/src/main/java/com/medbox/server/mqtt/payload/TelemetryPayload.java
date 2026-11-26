package com.medbox.server.mqtt.payload;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 上行·环境遥测 {@code up/telemetry}（见文档 03 第 3 章）。
 *
 * <p><b>主控直接上报，不带 {@code sensorId}</b>：温湿度一条给
 * {@code temperature} / {@code humidity}，光照一条给 {@code lux}，其余字段为 {@code null}。
 * 落 {@code env_sample} 时直接用 topic 中的主控 deviceId，业务处理在 EnvSampleService。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TelemetryPayload extends BaseUpPayload {

    private Double temperature;

    private Double humidity;

    /** 光照强度。 */
    private Double lux;
}
