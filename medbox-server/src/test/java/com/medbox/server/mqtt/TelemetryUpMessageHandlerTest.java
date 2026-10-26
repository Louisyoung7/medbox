package com.medbox.server.mqtt;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medbox.server.domain.EnvSample;
import com.medbox.server.domain.event.EnvSampleRecordedEvent;
import com.medbox.server.mqtt.payload.TelemetryPayload;
import com.medbox.server.service.EnvSampleService;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link TelemetryUpMessageHandler} 的纯单测（不走 Spring）：只验证"反序列化 → 转发"这一薄适配层，
 * 落库与归属判定在 {@code EnvSampleServiceTest} 覆盖。保证畸形 / 缺字段不向外抛异常（Paho 回调线程不能被打断）。
 * 同时验证：ingest 返回非 null 样本时发布 {@link EnvSampleRecordedEvent}（供 env-alarm 判定）。
 */
class TelemetryUpMessageHandlerTest {

    private final EnvSampleService envSampleService = mock(EnvSampleService.class);
    private final ObjectMapper objectMapper = new JsonMapper();
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final TelemetryUpMessageHandler handler =
            new TelemetryUpMessageHandler(envSampleService, objectMapper, publisher);

    private static final String DEVICE_ID = "BOXA1001";
    private static final String TOPIC = "medbox/medbox-box/BOXA1001/up/telemetry";

    @Test
    @DisplayName("supports 匹配 telemetry")
    void supports() {
        Assertions.assertThat(handler.supports()).isEqualTo("telemetry");
    }

    @Test
    @DisplayName("合法 JSON：反序列化后转发给 service")
    void forwardsValidPayload() {
        String json = """
            { "msgId":"BOXA1001-000123", "ts":1759474487000,
              "sensorId":"BOXA1001-S-TH01", "temperature":24.6, "humidity":52.1 }
            """;

        handler.handle(DEVICE_ID, TOPIC, json);

        ArgumentCaptor<TelemetryPayload> captor = ArgumentCaptor.forClass(TelemetryPayload.class);
        verify(envSampleService).ingest(eq(DEVICE_ID), captor.capture());
        TelemetryPayload payload = captor.getValue();
        Assertions.assertThat(payload.getSensorId()).isEqualTo("BOXA1001-S-TH01");
        Assertions.assertThat(payload.getTemperature()).isEqualTo(24.6);
        Assertions.assertThat(payload.getHumidity()).isEqualTo(52.1);
    }

    @Test
    @DisplayName("ingest 返回样本 → 发布 EnvSampleRecordedEvent 供 env-alarm 判定")
    void publishesEventWhenIngested() {
        EnvSample sample = new EnvSample();
        sample.setElderId("u-1001");
        sample.setDeviceId("BOXA1001-S-TH01");
        when(envSampleService.ingest(eq(DEVICE_ID), any())).thenReturn(sample);

        String json = """
            { "msgId":"BOXA1001-000123", "ts":1759474487000,
              "sensorId":"BOXA1001-S-TH01", "temperature":24.6, "humidity":52.1 }
            """;

        handler.handle(DEVICE_ID, TOPIC, json);

        ArgumentCaptor<EnvSampleRecordedEvent> captor = ArgumentCaptor.forClass(EnvSampleRecordedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        Assertions.assertThat(captor.getValue().getSample()).isSameAs(sample);
    }

    @Test
    @DisplayName("ingest 跳过（返回 null）→ 不发布事件")
    void noEventWhenSkipped() {
        when(envSampleService.ingest(eq(DEVICE_ID), any())).thenReturn(null);

        String json = """
            { "msgId":"BOXA1001-000123", "ts":1759474487000,
              "sensorId":"BOXA1001-S-TH01", "temperature":24.6 }
            """;

        handler.handle(DEVICE_ID, TOPIC, json);

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("畸形 JSON：只记日志，不调 service、不抛异常")
    void malformedJsonSwallowed() {
        handler.handle(DEVICE_ID, TOPIC, "not-a-json");
        verify(envSampleService, never()).ingest(any(), any());
    }

    @Test
    @DisplayName("多带未知字段不报错（Boot 默认 FAIL_ON_UNKNOWN_PROPERTIES=false）")
    void extraFieldsIgnored() {
        String json = """
            { "msgId":"BOXA1001-000124", "ts":1759474488000,
              "sensorId":"BOXA1001-S-LX01", "lux":120, "futureField":1 }
            """;
        handler.handle(DEVICE_ID, TOPIC, json);
        verify(envSampleService).ingest(eq(DEVICE_ID), any());
    }
}
