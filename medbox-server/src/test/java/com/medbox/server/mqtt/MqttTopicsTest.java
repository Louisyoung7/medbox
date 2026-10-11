package com.medbox.server.mqtt;

import static org.assertj.core.api.Assertions.assertThat;

import com.medbox.server.mqtt.MqttTopics.InboundTopic;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Topic 拼装与解析（无依赖、CI 可跑）。
 *
 * <p>这里守的是文档 03 第 2 章的口径：<b>{@code medbox/{productKey}/{deviceId}/up|down|...}</b>，
 * 尤其是"第 3 层才是 deviceId" —— 解析错位会把甲设备的消息当成乙设备的写进库里。
 */
class MqttTopicsTest {

    private static final String PREFIX = "medbox";

    @Test
    @DisplayName("订阅列表覆盖上行 up/# 与遗嘱 status")
    void inboundTopicsCoversUplinkAndLwt() {
        List<String> topics = MqttTopics.inboundTopics(PREFIX);

        assertThat(topics).containsExactlyInAnyOrder("medbox/+/+/up/#", "medbox/+/+/status");
    }

    @Test
    @DisplayName("解析遥测 topic：deviceId 在第 3 层，subPath 是 up 之后的部分")
    void parseTelemetryTopic() {
        Optional<InboundTopic> parsed = MqttTopics.parse(PREFIX, "medbox/pk001/BOXA1001/up/telemetry");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().deviceId()).isEqualTo("BOXA1001");
        assertThat(parsed.get().productKey()).isEqualTo("pk001");
        assertThat(parsed.get().subPath()).isEqualTo("telemetry");
        assertThat(parsed.get().lwt()).isFalse();
        assertThat(parsed.get().raw()).isEqualTo("medbox/pk001/BOXA1001/up/telemetry");
    }

    @Test
    @DisplayName("解析多级子路径 topic：up/event/dispense")
    void parseNestedSubPath() {
        Optional<InboundTopic> parsed = MqttTopics.parse(PREFIX, "medbox/pk001/BOXA1001/up/event/dispense");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().subPath()).isEqualTo("event/dispense");
    }

    @Test
    @DisplayName("遗嘱 topic 被识别为 lwt")
    void parseLwtTopic() {
        Optional<InboundTopic> parsed = MqttTopics.parse(PREFIX, "medbox/pk001/BOXA1001/status");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().lwt()).isTrue();
        assertThat(parsed.get().subPath()).isEqualTo("status");
        assertThat(parsed.get().deviceId()).isEqualTo("BOXA1001");
    }

    @Test
    @DisplayName("拼下行 topic：medbox/{pk}/{deviceId}/down/{subTopic}")
    void buildDownTopic() {
        String topic = MqttTopics.downTopic(PREFIX, "pk001", "BOXA1001", "schedule");

        assertThat(topic).isEqualTo("medbox/pk001/BOXA1001/down/schedule");
    }

    @Test
    @DisplayName("畸形 topic 返回空而不是抛异常：回调线程不能被一条脏消息打断")
    void malformedTopicsReturnEmpty() {
        assertThat(MqttTopics.parse(PREFIX, null)).isEmpty();
        assertThat(MqttTopics.parse(PREFIX, "  ")).isEmpty();
        assertThat(MqttTopics.parse(PREFIX, "medbox/pk001/BOXA1001")).isEmpty();
        assertThat(MqttTopics.parse(PREFIX, "medbox/pk001/BOXA1001/up")).isEmpty();
        assertThat(MqttTopics.parse(PREFIX, "medbox/pk001//up/telemetry")).isEmpty();
        assertThat(MqttTopics.parse(PREFIX, "/medbox/pk001/BOXA1001/up/telemetry")).isEmpty();
        assertThat(MqttTopics.parse(PREFIX, "sensor/pk001/BOXA1001/up/telemetry")).isEmpty();
        assertThat(MqttTopics.parse(PREFIX, "medbox/pk001/BOXA1001/down/schedule")).isEmpty();
    }
}
