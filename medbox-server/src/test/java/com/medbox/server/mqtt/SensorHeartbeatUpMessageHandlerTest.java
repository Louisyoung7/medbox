package com.medbox.server.mqtt;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.medbox.server.mqtt.payload.SensorHeartbeatPayload;
import com.medbox.server.service.SensorStatusService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link SensorHeartbeatUpMessageHandler} 的纯单测（不走 Spring）：只验证"反序列化 → 转发"这一薄适配层，
 * 判定逻辑归 {@code SensorStatusServiceTest}。保证畸形 / 缺字段不向外抛异常（Paho 回调线程不能被打断）。
 */
class SensorHeartbeatUpMessageHandlerTest {

    private final SensorStatusService sensorStatusService = mock(SensorStatusService.class);
    private final ObjectMapper objectMapper = new JsonMapper();
    private final SensorHeartbeatUpMessageHandler handler =
            new SensorHeartbeatUpMessageHandler(sensorStatusService, objectMapper);

    private static final String DEVICE_ID = "BOXA1001";
    private static final String TOPIC = "medbox/medbox-box/BOXA1001/up/sensor/heartbeat";
    private static final String VALID_JSON = """
        { "msgId":"BOXA1001-000128", "ts":1759474540000,
          "sensors":[ { "sensorId":"BOXA1001-S-TH01","online":true },
                      { "sensorId":"BOXA1001-S-LX01","online":true },
                      { "sensorId":"BOXA1001-S-CAM01","online":false } ] }
        """;

    @Test
    @DisplayName("supports 匹配 sensor/heartbeat")
    void supports() {
        org.assertj.core.api.Assertions.assertThat(handler.supports()).isEqualTo("sensor/heartbeat");
    }

    @Test
    @DisplayName("合法 JSON：反序列化后逐条转发给 service")
    void forwardsValidPayload() {
        handler.handle(DEVICE_ID, TOPIC, VALID_JSON);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SensorHeartbeatPayload.SensorState>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(sensorStatusService).applyHeartbeat(eq(DEVICE_ID), captor.capture());
        List<SensorHeartbeatPayload.SensorState> sensors = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(sensors).hasSize(3);
        org.assertj.core.api.Assertions.assertThat(sensors.get(0).getSensorId()).isEqualTo("BOXA1001-S-TH01");
        org.assertj.core.api.Assertions.assertThat(sensors.get(2).getOnline()).isFalse();
    }

    @Test
    @DisplayName("畸形 JSON：只记日志，不调 service、不抛异常")
    void malformedJsonSwallowed() {
        handler.handle(DEVICE_ID, TOPIC, "not-a-json");
        verify(sensorStatusService, never()).applyHeartbeat(any(), any());
    }

    @Test
    @DisplayName("空 sensors 数组仍转发（丢弃由 service 判定），不抛异常")
    void emptySensorsForwardedNotThrown() {
        handler.handle(DEVICE_ID, TOPIC,
                "{\"msgId\":\"BOXA1001-000129\",\"ts\":1,\"sensors\":[]}");
        verify(sensorStatusService).applyHeartbeat(eq(DEVICE_ID), eq(List.of()));
    }

    @Test
    @DisplayName("多带未知字段不报错（Boot 默认 FAIL_ON_UNKNOWN_PROPERTIES=false）")
    void extraFieldsIgnored() {
        handler.handle(DEVICE_ID, TOPIC,
                VALID_JSON.replace("] }", "],\"futureField\":123}"));
        verify(sensorStatusService).applyHeartbeat(eq(DEVICE_ID), any());
    }
}
