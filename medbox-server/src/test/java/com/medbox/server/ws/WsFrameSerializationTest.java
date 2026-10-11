package com.medbox.server.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.medbox.server.ws.payload.AlarmPayload;
import com.medbox.server.ws.payload.DeviceStatusPayload;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 帧结构与文档 04 第 2 / 3 章逐字段对齐 —— 帧格式错了小程序侧解析直接崩，
 * 且这种错不会有任何异常，只能靠断言钉死。
 */
class WsFrameSerializationTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("ALARM 帧：type/id/topic/ts/payload 齐备，ts 为毫秒，payload 字段与 04 一致")
    void alarmFrameMatchesSpec() throws Exception {
        WsFrame frame = new WsFrame(WsFrameType.ALARM, "evt-9a1c", "elder:e-1001", 1759474520000L,
                new AlarmPayload("a-5001", "MISS", "WARN",
                        "08:00 计划服药未按时服用：缺 阿莫西林、维生素D", "e-1001", List.of("m-205", "m-388")));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(frame));

        assertThat(json.get("type").asString()).isEqualTo("ALARM");
        assertThat(json.get("id").asString()).isEqualTo("evt-9a1c");
        assertThat(json.get("topic").asString()).isEqualTo("elder:e-1001");
        assertThat(json.get("ts").asLong()).isEqualTo(1759474520000L);

        JsonNode payload = json.get("payload");
        assertThat(payload.get("alarmId").asString()).isEqualTo("a-5001");
        assertThat(payload.get("type").asString()).isEqualTo("MISS");
        assertThat(payload.get("level").asString()).isEqualTo("WARN");
        assertThat(payload.get("message").asString()).contains("阿莫西林");
        assertThat(payload.get("elderId").asString()).isEqualTo("e-1001");
        assertThat(payload.get("medicineIds")).hasSize(2);
    }

    @Test
    @DisplayName("DEVICE_STATUS 帧：主控不带 parentDeviceId（受全局 non_null 影响，该字段不出现）")
    void deviceStatusFrameMatchesSpec() throws Exception {
        WsFrame frame = new WsFrame(WsFrameType.DEVICE_STATUS, "evt-1", "elder:e-1001", 1759474520000L,
                new DeviceStatusPayload("BOXA1001", "MAIN", true, null));

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(frame));
        JsonNode payload = json.get("payload");

        assertThat(payload.get("deviceId").asString()).isEqualTo("BOXA1001");
        assertThat(payload.get("deviceType").asString()).isEqualTo("MAIN");
        assertThat(payload.get("online").asBoolean()).isTrue();
        // 入库配置是 non_null，本用例用裸 JsonMapper 会写成 null 节点 —— 两种形态都算"没有该字段"
        JsonNode parent = payload.get("parentDeviceId");
        assertThat(parent == null || parent.isNull()).isTrue();
    }

    @Test
    @DisplayName("传感器子设备带 parentDeviceId（04 第 3 章：子设备状态由主控汇总上报）")
    void sensorCarriesParentDeviceId() throws Exception {
        WsFrame frame = new WsFrame(WsFrameType.DEVICE_STATUS, "evt-2", "elder:e-1001", 1759474520000L,
                new DeviceStatusPayload("BOXA1001-S-TH01", "TEMP_HUMI", false, "BOXA1001"));

        JsonNode payload = objectMapper.readTree(objectMapper.writeValueAsString(frame)).get("payload");

        assertThat(payload.get("parentDeviceId").asString()).isEqualTo("BOXA1001");
        assertThat(payload.get("online").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("PONG 是控制帧：不带 topic 与 payload（一条连接可能订阅多个主题）")
    void pongIsTopicLess() {
        WsFrame pong = WsFrame.of(WsFrameType.PONG, "evt-3", null, 1759474520000L);

        assertThat(pong.payload()).isNull();
        assertThat(pong.topic()).isNull();
        assertThat(pong.type()).isEqualTo(WsFrameType.PONG);
    }

    @Test
    @DisplayName("事件 ID 形态与文档示例一致：evt- + 十六进制")
    void eventIdFormat() {
        assertThat(WsEventId.format(1L)).isEqualTo("evt-1");
        assertThat(WsEventId.format(255L)).isEqualTo("evt-ff");
        assertThat(WsEventId.format(0x9a1cL)).isEqualTo("evt-9a1c");
    }
}
