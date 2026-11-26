package com.medbox.server.mqtt;

import static org.assertj.core.api.Assertions.assertThat;

import com.medbox.server.mqtt.payload.AckPayload;
import com.medbox.server.mqtt.payload.StatusPayload;
import com.medbox.server.mqtt.payload.TelemetryPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 用文档 03 第 3 章的 payload 示例做解析回归（无依赖、CI 可跑）。
 *
 * <p>守的是"字段名一个字母都不能差"：驼峰名写错在 JSON 侧不会报错，只会静默变成 {@code null}。
 */
class UpPayloadParseTest {

    private final ObjectMapper objectMapper = new JsonMapper();

    @Test
    @DisplayName("遥测：主控直接上报温湿度，读数字段命名固定")
    void parseTelemetry() throws Exception {
        String json = """
            { "msgId":"BOXA1001-000123", "ts":1759474487000,
              "temperature":24.6, "humidity":52.1 }
            """;

        TelemetryPayload payload = objectMapper.readValue(json, TelemetryPayload.class);

        assertThat(payload.getMsgId()).isEqualTo("BOXA1001-000123");
        assertThat(payload.getTs()).isEqualTo(1759474487000L);
        assertThat(payload.getTemperature()).isEqualTo(24.6);
        assertThat(payload.getHumidity()).isEqualTo(52.1);
        assertThat(payload.getLux()).isNull();
    }

    @Test
    @DisplayName("遥测：光照上报 lux，不带 sensorId 概念")
    void parseLuxTelemetry() throws Exception {
        String json = """
            { "msgId":"BOXA1001-000124", "ts":1759474488000, "lux":120 }
            """;

        TelemetryPayload payload = objectMapper.readValue(json, TelemetryPayload.class);

        assertThat(payload.getLux()).isEqualTo(120.0);
        assertThat(payload.getTemperature()).isNull();
    }

    @Test
    @DisplayName("回执：ackFor 指回下行的 msgId，result=OK 判定成功")
    void parseAck() throws Exception {
        String json = """
            { "msgId":"BOXA1001-000126", "ts":1759474520000,
              "ackFor":"d-001", "result":"OK", "reason":null }
            """;

        AckPayload payload = objectMapper.readValue(json, AckPayload.class);

        assertThat(payload.getAckFor()).isEqualTo("d-001");
        assertThat(payload.isOk()).isTrue();
        assertThat(payload.getReason()).isNull();
    }

    @Test
    @DisplayName("回执：result 缺失或不是 OK 一律不算成功")
    void ackFailsClosed() throws Exception {
        AckPayload failed = objectMapper.readValue("{\"ackFor\":\"d-001\",\"result\":\"FAILED\"}", AckPayload.class);
        AckPayload blank = objectMapper.readValue("{\"ackFor\":\"d-001\"}", AckPayload.class);

        assertThat(failed.isOk()).isFalse();
        assertThat(blank.isOk()).isFalse();
    }

    @Test
    @DisplayName("遗嘱状态 payload 固定 {\"online\":false}")
    void parseStatus() throws Exception {
        StatusPayload payload = objectMapper.readValue("{\"online\":false}", StatusPayload.class);

        assertThat(payload.getOnline()).isFalse();
    }
}
