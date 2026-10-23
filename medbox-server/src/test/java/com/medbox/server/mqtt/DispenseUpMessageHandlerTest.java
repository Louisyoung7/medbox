package com.medbox.server.mqtt;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.medbox.server.mqtt.payload.DispenseEventPayload;
import com.medbox.server.service.DispenseConsumeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link DispenseUpMessageHandler} 的纯单测（不走 Spring）：只验证"反序列化 → 转发"这一薄适配层，
 * 判定逻辑归 {@link DispenseConsumeServiceTest}。保证畸形 / 缺字段不向外抛异常（Paho 回调线程不能被打断）。
 */
class DispenseUpMessageHandlerTest {

    private final DispenseConsumeService consumeService = mock(DispenseConsumeService.class);
    private final ObjectMapper objectMapper = new JsonMapper();
    private final DispenseUpMessageHandler handler =
            new DispenseUpMessageHandler(consumeService, objectMapper);

    private static final String DEVICE_ID = "BOXA1001";
    private static final String TOPIC = "medbox/pk001/BOXA1001/up/event/dispense";
    private static final String VALID_JSON = """
        { "msgId":"BOXA1001-0000000200", "ts":1759474500000,
          "planId":"p-1001", "planItemId":"pi-1001", "medicineId":"m-205",
          "actualDose":"1", "unit":"片", "wrongDrug":false, "onTime":true,
          "confidence":0.93, "source":"CAMERA", "imageId":"cap-8801" }
        """;

    @Test
    @DisplayName("supports 匹配 event/dispense")
    void supports() {
        org.assertj.core.api.Assertions.assertThat(handler.supports()).isEqualTo("event/dispense");
    }

    @Test
    @DisplayName("合法 JSON：反序列化后转发给 service")
    void forwardsValidPayload() {
        handler.handle(DEVICE_ID, TOPIC, VALID_JSON);

        ArgumentCaptor<DispenseEventPayload> captor = ArgumentCaptor.forClass(DispenseEventPayload.class);
        verify(consumeService).consume(eq(DEVICE_ID), captor.capture());
        DispenseEventPayload p = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(p.getPlanId()).isEqualTo("p-1001");
        org.assertj.core.api.Assertions.assertThat(p.getPlanItemId()).isEqualTo("pi-1001");
        org.assertj.core.api.Assertions.assertThat(p.getMedicineId()).isEqualTo("m-205");
        org.assertj.core.api.Assertions.assertThat(p.getWrongDrug()).isFalse();
    }

    @Test
    @DisplayName("畸形 JSON：只记日志，不调 service、不抛异常")
    void malformedJsonSwallowed() {
        handler.handle(DEVICE_ID, TOPIC, "not-a-json");
        verify(consumeService, never()).consume(any(), any());
    }

    @Test
    @DisplayName("缺 planId 的合法 JSON：handler 仍转发（丢弃由 service 判定），不抛异常")
    void missingPlanIdForwardedNotThrown() {
        handler.handle(DEVICE_ID, TOPIC,
                "{\"msgId\":\"BOXA1001-0000000201\",\"ts\":1,\"planItemId\":\"pi-1\"}");
        verify(consumeService).consume(eq(DEVICE_ID), any(DispenseEventPayload.class));
    }

    @Test
    @DisplayName("多带未知字段不报错（Boot 默认 FAIL_ON_UNKNOWN_PROPERTIES=false）")
    void extraFieldsIgnored() {
        handler.handle(DEVICE_ID, TOPIC,
                VALID_JSON.replace("}", ",\"futureField\":123}"));
        verify(consumeService).consume(eq(DEVICE_ID), any(DispenseEventPayload.class));
    }
}
