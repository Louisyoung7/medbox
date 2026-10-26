package com.medbox.server.mqtt;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.medbox.server.mqtt.payload.DeviceErrorPayload;
import com.medbox.server.service.DeviceFaultService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link DeviceErrorUpMessageHandler} 的纯单测（不走 Spring）：只验证"反序列化 → 转发"这一薄适配层，
 * 判定逻辑归 {@code DeviceFaultServiceTest}。保证畸形 / 缺字段不向外抛异常（Paho 回调线程不能被打断）。
 */
class DeviceErrorUpMessageHandlerTest {

    private final DeviceFaultService deviceFaultService = mock(DeviceFaultService.class);
    private final ObjectMapper objectMapper = new JsonMapper();
    private final DeviceErrorUpMessageHandler handler =
            new DeviceErrorUpMessageHandler(deviceFaultService, objectMapper);

    private static final String DEVICE_ID = "BOXA1001";
    private static final String TOPIC = "medbox/medbox-box/BOXA1001/up/event/error";
    private static final String VALID_JSON = """
        { "msgId":"BOXA1001-000125", "ts":1759474512000,
          "type":"DOOR_TIMEOUT", "detail":"仓门开启超过 60s 未关闭" }
        """;

    @Test
    @DisplayName("supports 匹配 event/error")
    void supports() {
        org.assertj.core.api.Assertions.assertThat(handler.supports()).isEqualTo("event/error");
    }

    @Test
    @DisplayName("合法 JSON：反序列化后转发给 service（type / detail / ts 齐全）")
    void forwardsValidPayload() {
        handler.handle(DEVICE_ID, TOPIC, VALID_JSON);

        ArgumentCaptor<DeviceErrorPayload> captor = ArgumentCaptor.forClass(DeviceErrorPayload.class);
        verify(deviceFaultService).ingest(eq(DEVICE_ID), captor.capture());
        DeviceErrorPayload payload = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(payload.getType()).isEqualTo("DOOR_TIMEOUT");
        org.assertj.core.api.Assertions.assertThat(payload.getDetail()).isEqualTo("仓门开启超过 60s 未关闭");
        org.assertj.core.api.Assertions.assertThat(payload.getTs()).isEqualTo(1759474512000L);
    }

    @Test
    @DisplayName("畸形 JSON：只记日志，不调 service、不抛异常")
    void malformedJsonSwallowed() {
        handler.handle(DEVICE_ID, TOPIC, "not-a-json");
        verify(deviceFaultService, never()).ingest(any(), any());
    }

    @Test
    @DisplayName("多带未知字段不报错（Boot 默认 FAIL_ON_UNKNOWN_PROPERTIES=false）")
    void extraFieldsIgnored() {
        handler.handle(DEVICE_ID, TOPIC,
                VALID_JSON.replace("}", ",\"futureField\":123}"));
        verify(deviceFaultService).ingest(eq(DEVICE_ID), any());
    }
}
