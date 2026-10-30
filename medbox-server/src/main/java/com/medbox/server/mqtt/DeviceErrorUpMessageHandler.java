package com.medbox.server.mqtt;

import com.medbox.server.mqtt.payload.DeviceErrorPayload;
import com.medbox.server.service.DeviceFaultService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 设备侧异常 {@code up/event/error} 的处理器（feat/device-fault，07 清单 P1）。
 *
 * <p><b>只做薄适配</b>：Jackson 反序列化 + 转发给 {@link DeviceFaultService#ingest}（判定核心在服务层，
 * 与 {@code SensorHeartbeatUpMessageHandler} 同一形态）——测试期
 * {@code MEDBOX_MQTT_ENABLED=false} 时 mqtt 装配整体不建，但服务层仍是真 Bean，集成测试可直接注入打链路。
 *
 * <p>本类<b>不抛异常</b>：畸形 / 缺字段只记 warn，绝不外溢到 Paho 回调线程。
 */
@Component
public class DeviceErrorUpMessageHandler implements UpMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(DeviceErrorUpMessageHandler.class);

    private final DeviceFaultService deviceFaultService;
    private final ObjectMapper objectMapper;

    public DeviceErrorUpMessageHandler(DeviceFaultService deviceFaultService, ObjectMapper objectMapper) {
        this.deviceFaultService = deviceFaultService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String supports() {
        return "event/error";
    }

    @Override
    public void handle(String deviceId, String topic, String payloadJson) {
        DeviceErrorPayload payload;
        try {
            payload = objectMapper.readValue(payloadJson, DeviceErrorPayload.class);
        } catch (Exception ex) {
            log.warn("up/event/error 解析失败 deviceId={} topic={}：{}", deviceId, topic, ex.getMessage());
            return;
        }
        deviceFaultService.ingest(deviceId, payload);
    }
}