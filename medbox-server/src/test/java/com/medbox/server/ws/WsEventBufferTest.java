package com.medbox.server.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.medbox.server.support.MutableClock;
import com.medbox.server.ws.payload.AlarmPayload;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 补推缓冲的边界（文档 04 第 1 章：最近 200 条 / 10 分钟）。
 *
 * <p>这里覆盖的是"悄悄算错也不报错"的那类逻辑：条数上限、窗口过期、断档判定 ——
 * 判定错了不会抛异常，只会让客户端少收或多收事件，所以必须逐条钉死。
 */
class WsEventBufferTest {

    private static final String TOPIC = "elder:e-1001";
    private static final String OTHER_TOPIC = "elder:e-1002";
    private static final long T0 = 1_759_000_000_000L;

    private MutableClock clock;
    private WsEventBuffer buffer;

    @BeforeEach
    void setUp() {
        clock = MutableClock.at(T0);
        WsProperties props = new WsProperties();
        props.getReplay().setMaxEvents(3);
        props.getReplay().setWindowMinutes(1);
        buffer = new WsEventBuffer(props, clock);
    }

    @Test
    @DisplayName("未带 lastEventId（首次连接）→ 不补推")
    void noLastEventIdMeansNoReplay() {
        append(TOPIC, 1L);

        assertThat(buffer.replaySince(TOPIC, WsEventId.NO_SEQ).events()).isEmpty();
        assertThat(buffer.replaySince(TOPIC, WsEventId.NO_SEQ).windowExpired()).isFalse();
    }

    @Test
    @DisplayName("补推只给 lastEventId 之后的增量，且保持顺序")
    void replaySinceReturnsTail() {
        append(TOPIC, 1L);
        append(TOPIC, 2L);
        append(TOPIC, 3L);

        WsEventBuffer.ReplayResult result = buffer.replaySince(TOPIC, 1L);

        assertThat(result.events()).extracting(WsFrame::id)
                .containsExactly(WsEventId.format(2L), WsEventId.format(3L));
        assertThat(result.windowExpired()).isFalse();
        assertThat(result.latestEventId()).isEqualTo(WsEventId.format(3L));
    }

    @Test
    @DisplayName("超过条数上限：老事件被淘汰，客户端落后太多时判定窗口已过期")
    void overflowMarksWindowExpired() {
        append(TOPIC, 1L);
        append(TOPIC, 2L);
        append(TOPIC, 3L);
        append(TOPIC, 4L);
        append(TOPIC, 5L);

        assertThat(buffer.size(TOPIC)).isEqualTo(3);

        // 客户端只收到过 evt-1，而缓冲里最老的是 evt-3 → 中间断了档
        WsEventBuffer.ReplayResult result = buffer.replaySince(TOPIC, 1L);
        assertThat(result.windowExpired()).isTrue();
        assertThat(result.events()).extracting(WsFrame::id).containsExactlyInAnyOrder(
                WsEventId.format(3L), WsEventId.format(4L), WsEventId.format(5L));
    }

    @Test
    @DisplayName("超过时间窗口：老事件被淘汰，判定窗口已过期（哪怕缓冲已空）")
    void expiredWindowIsDetectedEvenWhenBufferEmpty() {
        append(TOPIC, 1L);
        append(TOPIC, 2L);   // 客户端只拿到过 evt-1，evt-2 从未送达
        clock.advanceMillis(61_000L);

        WsEventBuffer.ReplayResult result = buffer.replaySince(TOPIC, 1L);

        assertThat(result.events()).isEmpty();
        assertThat(result.windowExpired()).as("事件已被时间窗口淘汰，必须回 RESYNC 让客户端拉全量").isTrue();
    }

    @Test
    @DisplayName("客户端已追上最新：有空增量但不算窗口过期")
    void caughtUpClientIsNotExpired() {
        append(TOPIC, 1L);
        append(TOPIC, 2L);

        WsEventBuffer.ReplayResult result = buffer.replaySince(TOPIC, 2L);

        assertThat(result.events()).isEmpty();
        assertThat(result.windowExpired()).isFalse();
    }

    @Test
    @DisplayName("主题之间互不影响")
    void topicsAreIsolated() {
        append(TOPIC, 1L);
        append(OTHER_TOPIC, 2L);

        assertThat(buffer.replaySince(OTHER_TOPIC, 1L).events()).extracting(WsFrame::id)
                .containsExactly(WsEventId.format(2L));
        assertThat(buffer.replaySince(TOPIC, 1L).events()).isEmpty();
        assertThat(buffer.topicCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("未知主题补推：无事件也不算过期")
    void unknownTopicIsNotExpired() {
        assertThat(buffer.replaySince("elder:unknown", 7L).events()).isEmpty();
        assertThat(buffer.replaySince("elder:unknown", 7L).windowExpired()).isFalse();
    }

    private void append(String topic, long seq) {
        buffer.append(topic, seq, WsFrame.of(WsFrameType.ALARM, WsEventId.format(seq), topic, clock.millis(),
                new AlarmPayload("a-" + seq, "MISS", "WARN", "调试", "e-1001", List.of("m-205"))));
    }
}
