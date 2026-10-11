package com.medbox.server.mqtt;

import static org.assertj.core.api.Assertions.assertThat;

import com.medbox.server.mqtt.payload.AckPayload;
import com.medbox.server.mqtt.payload.DispenseEventPayload;
import com.medbox.server.mqtt.payload.SensorHeartbeatPayload;
import com.medbox.server.mqtt.payload.StatusPayload;
import com.medbox.server.mqtt.payload.TelemetryPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 用文档 03 第 3 章的 payload 示例做解析回归（无依赖、CI 可跑）。
 *
 * <p>守的是"字段名一个字母都不能差"：驼峰名写错（如 {@code wrongdrug}）在 JSON 侧不会报错，
 * 只会静默变成 {@code null}，等到告警逻辑里才发现 —— 那时已经分不清是设备没发还是我们读错。
 */
class UpPayloadParseTest {

    private final ObjectMapper objectMapper = new JsonMapper();

    @Test
    @DisplayName("遥测：温湿度传感器只带 temperature / humidity，lux 为 null")
    void parseTelemetry() throws Exception {
        String json = """
            { "msgId":"BOXA1001-000123", "ts":1759474487000,
              "sensorId":"BOXA1001-S-TH01",
              "temperature":24.6, "humidity":52.1 }
            """;

        TelemetryPayload payload = objectMapper.readValue(json, TelemetryPayload.class);

        assertThat(payload.getMsgId()).isEqualTo("BOXA1001-000123");
        assertThat(payload.getTs()).isEqualTo(1759474487000L);
        assertThat(payload.getSensorId()).isEqualTo("BOXA1001-S-TH01");
        assertThat(payload.getTemperature()).isEqualTo(24.6);
        assertThat(payload.getHumidity()).isEqualTo(52.1);
        assertThat(payload.getLux()).isNull();
    }

    @Test
    @DisplayName("服药事件：wrongDrug / planItemId / imageId 等字段全部对齐")
    void parseDispenseEvent() throws Exception {
        String json = """
            { "msgId":"BOXA1001-000124", "ts":1759474500000,
              "planId":"p-3301", "planItemId":"pi-9001",
              "medicineId":"m-205",
              "actualDose":"1", "unit":"片", "wrongDrug":false, "onTime":true,
              "confidence":0.93, "source":"CAMERA", "imageId":"cap-8801" }
            """;

        DispenseEventPayload payload = objectMapper.readValue(json, DispenseEventPayload.class);

        assertThat(payload.getMsgId()).isEqualTo("BOXA1001-000124");
        assertThat(payload.getPlanId()).isEqualTo("p-3301");
        assertThat(payload.getPlanItemId()).isEqualTo("pi-9001");
        assertThat(payload.getMedicineId()).isEqualTo("m-205");
        assertThat(payload.getActualDose()).isEqualTo("1");
        assertThat(payload.getUnit()).isEqualTo("片");
        assertThat(payload.getWrongDrug()).isFalse();
        assertThat(payload.getOnTime()).isTrue();
        assertThat(payload.getConfidence()).isEqualTo(0.93);
        assertThat(payload.getSource()).isEqualTo("CAMERA");
        assertThat(payload.getImageId()).isEqualTo("cap-8801");
    }

    @Test
    @DisplayName("传感器心跳：sensors 数组含 sensorId 与 online")
    void parseSensorHeartbeat() throws Exception {
        String json = """
            { "msgId":"BOXA1001-000128", "ts":1759474540000,
              "sensors":[ { "sensorId":"BOXA1001-S-TH01","online":true },
                          { "sensorId":"BOXA1001-S-CAM01","online":false } ] }
            """;

        SensorHeartbeatPayload payload = objectMapper.readValue(json, SensorHeartbeatPayload.class);

        assertThat(payload.getSensors()).hasSize(2);
        assertThat(payload.getSensors().get(0).getSensorId()).isEqualTo("BOXA1001-S-TH01");
        assertThat(payload.getSensors().get(0).getOnline()).isTrue();
        assertThat(payload.getSensors().get(1).getSensorId()).isEqualTo("BOXA1001-S-CAM01");
        assertThat(payload.getSensors().get(1).getOnline()).isFalse();
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
    @DisplayName("回执：result 缺失或不是 OK 一律不算成功（避免静默忽略失败）")
    void ackFailsClosed() throws Exception {
        AckPayload failed = objectMapper.readValue("{\"ackFor\":\"d-001\",\"result\":\"FAILED\"}", AckPayload.class);
        AckPayload blank = objectMapper.readValue("{\"ackFor\":\"d-001\"}", AckPayload.class);

        assertThat(failed.isOk()).isFalse();
        assertThat(blank.isOk()).isFalse();
    }

    @Test
    @DisplayName("遗嘱状态 payload 固定 {\"online\":false}：msgId 不是它的字段，去重也不能套在它身上")
    void parseStatus() throws Exception {
        StatusPayload payload = objectMapper.readValue("{\"online\":false}", StatusPayload.class);

        assertThat(payload.getOnline()).isFalse();
    }
}
