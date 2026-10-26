package com.medbox.server.mqtt;

import com.medbox.server.mqtt.payload.TelemetryPayload;
import com.medbox.server.service.EnvSampleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 环境遥测 {@code up/telemetry} 的处理器（feat/env-core，07 清单 P1）。
 *
 * <p><b>只做薄适配</b>：Jackson 反序列化 + 转发给 {@link EnvSampleService#ingest}
 * （判定核心在服务层，与 {@link SensorHeartbeatUpMessageHandler} / {@link DispenseUpMessageHandler}
 * 同一形态）——测试期 {@code MEDBOX_MQTT_ENABLED=false} 时 mqtt 装配整体不建，但服务层仍是真 Bean，
 * 集成测试可直接注入打链路。
 *
 * <p>标 {@code @Component} 即被 {@code MqttConfig} 的 {@code List<UpMessageHandler>} 自动收集，
 * 无需改 MqttConfig。本类<b>不抛异常</b>：网关已在更外层兜底，这里也自行接住解析失败，
 * 畸形 / 缺字段只记 warn，绝不外溢到 Paho 回调线程。
 */
@Component
public class TelemetryUpMessageHandler implements UpMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(TelemetryUpMessageHandler.class);

    private final EnvSampleService envSampleService;
    private final ObjectMapper objectMapper;

    public TelemetryUpMessageHandler(EnvSampleService envSampleService, ObjectMapper objectMapper) {
        this.envSampleService = envSampleService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String supports() {
        return "telemetry";
    }

    @Override
    public void handle(String deviceId, String topic, String payloadJson) {
        TelemetryPayload payload;
        try {
            payload = objectMapper.readValue(payloadJson, TelemetryPayload.class);
        } catch (Exception ex) {
            log.warn("up/telemetry 解析失败 deviceId={} topic={}：{}", deviceId, topic, ex.getMessage());
            return;
        }
        envSampleService.ingest(deviceId, payload);
    }
}
