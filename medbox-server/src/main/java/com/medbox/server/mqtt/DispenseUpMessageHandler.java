package com.medbox.server.mqtt;

import com.medbox.server.mqtt.payload.DispenseEventPayload;
import com.medbox.server.service.DispenseConsumeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 服药事件 {@code up/event/dispense} 的处理器（feat/dispense-consume，07 清单 P0 第 9 步）。
 *
 * <p><b>只做薄适配</b>：Jackson 反序列化 + 转发给 {@link DispenseConsumeService}（判定核心在服务层）。
 * 这样判定逻辑不依赖 MQTT 装配（测试期 {@code MEDBOX_MQTT_ENABLED=false} 时 mqtt 包整体不装配，
 * 但服务层仍是真 Bean，集成测试可直接注入打链路）。
 *
 * <p>标 {@code @Component} 即被 {@code MqttConfig} 的 {@code List<UpMessageHandler>} 自动收集，
 * 无需改 MqttConfig。本类<b>不抛异常</b>：网关已在更外层兜底，但这里也自行接住解析失败，
 * 畸形 / 缺字段只记 warn，绝不外溢到 Paho 回调线程。
 */
@Component
public class DispenseUpMessageHandler implements UpMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(DispenseUpMessageHandler.class);

    private final DispenseConsumeService consumeService;
    private final ObjectMapper objectMapper;

    public DispenseUpMessageHandler(DispenseConsumeService consumeService, ObjectMapper objectMapper) {
        this.consumeService = consumeService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String supports() {
        return "event/dispense";
    }

    @Override
    public void handle(String deviceId, String topic, String payloadJson) {
        DispenseEventPayload payload;
        try {
            payload = objectMapper.readValue(payloadJson, DispenseEventPayload.class);
        } catch (Exception ex) {
            log.warn("up/event/dispense 解析失败 deviceId={} topic={}：{}", deviceId, topic, ex.getMessage());
            return;
        }
        consumeService.consume(deviceId, payload);
    }
}
