package com.medbox.server.mqtt;

import com.medbox.server.domain.EnvSample;
import com.medbox.server.domain.event.EnvSampleRecordedEvent;
import com.medbox.server.mqtt.payload.TelemetryPayload;
import com.medbox.server.service.EnvSampleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
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
 *
 * <p>入库后通过 {@link ApplicationEventPublisher} 发布 {@link EnvSampleRecordedEvent}，
 * 由 {@code EnvAlarmService} 做环境超标判定（与 {@code wrong-drug-alarm} 消费
 * {@code DispenseRecordedEvent} 同一范式）；2 参构造器保留给 {@code TelemetryUpMessageHandlerTest} 手工 new。
 */
@Component
public class TelemetryUpMessageHandler implements UpMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(TelemetryUpMessageHandler.class);

    private final EnvSampleService envSampleService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /** 测试用构造器：publisher 置 null，不发布事件（避免测试依赖 Spring 上下文）。 */
    public TelemetryUpMessageHandler(EnvSampleService envSampleService, ObjectMapper objectMapper) {
        this(envSampleService, objectMapper, null);
    }

    /** 生产用构造器：Spring 注入 {@link ApplicationEventPublisher} 发布遥测入库事件。 */
    @Autowired
    public TelemetryUpMessageHandler(EnvSampleService envSampleService, ObjectMapper objectMapper,
                                     ApplicationEventPublisher eventPublisher) {
        this.envSampleService = envSampleService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
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
        EnvSample sample = envSampleService.ingest(deviceId, payload);
        if (sample != null && eventPublisher != null) {
            eventPublisher.publishEvent(new EnvSampleRecordedEvent(sample));
        }
    }
}
