package com.medbox.server.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 订阅主题语法与事件 ID 的解析（见文档 04 第 1 / 2 章）。 */
class WsTopicTest {

    @Test
    @DisplayName("elder:{elderId} 是合法主题")
    void elderTopicIsValid() {
        assertThat(WsTopic.rejectionReason("elder:e-1001")).isNull();
        assertThat(WsTopic.elderIdOf("elder:e-1001")).contains("e-1001");
        assertThat(WsTopic.elder("e-1001")).isEqualTo("elder:e-1001");
    }

    @Test
    @DisplayName("device: 主题明确拒绝并给出原因（设备归属解析待 feat/device-core）")
    void deviceTopicIsRejected() {
        String reason = WsTopic.rejectionReason("device:BOXA1001");

        assertThat(reason).isNotNull().contains("device");
        assertThat(WsTopic.elderIdOf("device:BOXA1001")).isEmpty();
    }

    @Test
    @DisplayName("畸形主题：无前缀 / 空 ID / 空白，一律拒绝")
    void malformedTopicsAreRejected() {
        assertThat(WsTopic.rejectionReason(null)).isNotNull();
        assertThat(WsTopic.rejectionReason("  ")).isNotNull();
        assertThat(WsTopic.rejectionReason("elder:")).isNotNull();
        assertThat(WsTopic.rejectionReason("e-1001")).isNotNull();
    }

    @Test
    @DisplayName("多主题：逗号分隔、去空白、去重、保持顺序")
    void splitHandlesMultipleTopics() {
        assertThat(WsTopic.split("elder:e-1001,elder:e-1002"))
                .containsExactly("elder:e-1001", "elder:e-1002");
        assertThat(WsTopic.split(" elder:e-1001 , elder:e-1001 ,, elder:e-1002 "))
                .containsExactly("elder:e-1001", "elder:e-1002");
        assertThat(WsTopic.split(null)).isEmpty();
        assertThat(WsTopic.split(" , , ")).isEmpty();
    }

    @Test
    @DisplayName("事件 ID：序号 ↔ evt-十六进制 可来回转换（与文档示例 evt-9a1c 同形）")
    void eventIdRoundTrip() {
        assertThat(WsEventId.format(0x9a1cL)).isEqualTo("evt-9a1c");
        assertThat(WsEventId.parse("evt-9a1c")).isEqualTo(OptionalLong.of(0x9a1cL));
        assertThat(WsEventId.format(1L)).isEqualTo("evt-1");
    }

    @Test
    @DisplayName("非法事件 ID 不抛异常，返回空（由调用方决定是否补推）")
    void invalidEventIdIsTolerated() {
        assertThat(WsEventId.parse(null).isPresent()).isFalse();
        assertThat(WsEventId.parse("evt-").isPresent()).isFalse();
        assertThat(WsEventId.parse("bogus-1").isPresent()).isFalse();
        assertThat(WsEventId.parse("evt-zzz").isPresent()).isFalse();
        assertThat(WsEventId.parse("evt-" + "f".repeat(17)).isPresent()).isFalse();
    }

    @Test
    @DisplayName("订阅记录：带合法 lastEventId 才需要补推")
    void subscriptionNeedReplay() {
        WsSubscription first = new WsSubscription("u-1", List.of("elder:e-1001"), WsEventId.NO_SEQ);
        WsSubscription reconnect = new WsSubscription("u-1", List.of("elder:e-1001"), 10L);

        assertThat(first.needReplay()).isFalse();
        assertThat(reconnect.needReplay()).isTrue();
        assertThat(Optional.of(reconnect.topics()).isPresent()).isTrue();
    }
}
