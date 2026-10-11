package com.medbox.server.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.medbox.server.support.MutableClock;
import com.medbox.server.ws.payload.AlarmPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 推送链路的集成测试：**真实**的 session 注册表 / 补推缓冲 / 推送出口 / 处理器，
 * 只有 {@link WebSocketSession} 是替身 —— 覆盖 07 清单的验收点"能收到后端主动推送的告警帧"
 * 与文档 04 的"断线重连补推"。
 */
class WsPushIntegrationTest {

    private static final String TOPIC = "elder:e-1001";
    private static final String OTHER_TOPIC = "elder:e-1002";
    private static final long T0 = 1_759_000_000_000L;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private MutableClock clock;
    private WsSessionManager sessions;
    private WsEventBuffer buffer;
    private WsPushService pushService;
    private MedboxWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        clock = MutableClock.at(T0);
        WsProperties props = new WsProperties();
        sessions = new WsSessionManager(props, clock);
        buffer = new WsEventBuffer(props, clock);
        pushService = new WsPushService(buffer, sessions, objectMapper, clock);
        handler = new MedboxWebSocketHandler(sessions, buffer, pushService, objectMapper);
    }

    @Test
    @DisplayName("订阅后推送：连接收到 ALARM 帧，字段与文档 04 一致")
    void subscriberReceivesAlarmFrame() throws Exception {
        FakeSession session = connect("s-1", TOPIC, WsEventId.NO_SEQ);

        WsPushService.WsPushResult result = pushService.publish(TOPIC, WsFrameType.ALARM, alarmPayload("a-5001"));

        assertThat(result.deliveredSessions()).isEqualTo(1);
        assertThat(result.eventId()).isEqualTo("evt-1");
        JsonNode frame = session.lastFrame(objectMapper);
        assertThat(frame.get("type").asString()).isEqualTo("ALARM");
        assertThat(frame.get("id").asString()).isEqualTo("evt-1");
        assertThat(frame.get("topic").asString()).isEqualTo(TOPIC);
        assertThat(frame.get("payload").get("alarmId").asString()).isEqualTo("a-5001");
    }

    @Test
    @DisplayName("无人订阅也写缓冲：重连带 lastEventId 能补到离线期间的事件")
    void offlineEventsAreReplayedOnReconnect() throws Exception {
        WsPushService.WsPushResult first = pushService.publish(TOPIC, WsFrameType.ALARM, alarmPayload("a-1"));
        WsPushService.WsPushResult second = pushService.publish(TOPIC, WsFrameType.ALARM, alarmPayload("a-2"));
        assertThat(first.deliveredSessions()).isZero();
        assertThat(second.deliveredSessions()).isZero();

        FakeSession session = connect("s-2", TOPIC, WsEventId.parse(first.eventId()).orElseThrow());

        assertThat(session.frames).hasSize(1);
        assertThat(session.frames.get(0).get("id").asString()).isEqualTo(second.eventId());
    }

    @Test
    @DisplayName("首次连接（无 lastEventId）不补推历史")
    void firstConnectionDoesNotReplay() throws Exception {
        pushService.publish(TOPIC, WsFrameType.ALARM, alarmPayload("a-1"));

        FakeSession session = connect("s-3", TOPIC, WsEventId.NO_SEQ);

        assertThat(session.frames).isEmpty();
    }

    @Test
    @DisplayName("补推窗口过期 → 收到 RESYNC，提示改走 REST 全量")
    void expiredWindowYieldsResync() throws Exception {
        WsProperties tightProps = new WsProperties();
        tightProps.getReplay().setMaxEvents(1);
        buffer = new WsEventBuffer(tightProps, clock);
        pushService = new WsPushService(buffer, sessions, objectMapper, clock);
        handler = new MedboxWebSocketHandler(sessions, buffer, pushService, objectMapper);

        WsPushService.WsPushResult first = pushService.publish(TOPIC, WsFrameType.ALARM, alarmPayload("a-1"));
        pushService.publish(TOPIC, WsFrameType.ALARM, alarmPayload("a-2"));
        pushService.publish(TOPIC, WsFrameType.ALARM, alarmPayload("a-3"));

        FakeSession session = connect("s-4", TOPIC, WsEventId.parse(first.eventId()).orElseThrow());

        assertThat(session.frames).hasSize(1);
        assertThat(session.frames.get(0).get("type").asString()).isEqualTo("RESYNC");
        assertThat(session.frames.get(0).get("payload").get("reason").asString()).isEqualTo("WINDOW_EXPIRED");
    }

    @Test
    @DisplayName("越权隔离：订阅 A 老人收不到 B 老人的帧")
    void otherTopicIsNotDelivered() throws Exception {
        FakeSession session = connect("s-5", TOPIC, WsEventId.NO_SEQ);

        WsPushService.WsPushResult result = pushService.publish(OTHER_TOPIC, WsFrameType.ALARM, alarmPayload("a-9"));

        assertThat(result.deliveredSessions()).isZero();
        assertThat(session.frames).isEmpty();
        // 但仍进了补推缓冲，订阅该主题的连接重连时能补到
        assertThat(buffer.size(OTHER_TOPIC)).isEqualTo(1);
    }

    @Test
    @DisplayName("心跳：ping → PONG，且刷新活跃时间")
    void pingIsAnsweredWithPong() throws Exception {
        FakeSession session = connect("s-6", TOPIC, WsEventId.NO_SEQ);

        handler.handleTextMessage(session.delegate, new TextMessage("{\"type\":\"ping\"}"));

        assertThat(session.frames).hasSize(1);
        assertThat(session.frames.get(0).get("type").asString()).isEqualTo("PONG");
    }

    @Test
    @DisplayName("未知 / 非法 JSON 帧不报错，也不回任何帧")
    void unknownFrameIsIgnored() throws Exception {
        FakeSession session = connect("s-7", TOPIC, WsEventId.NO_SEQ);

        handler.handleTextMessage(session.delegate, new TextMessage("{\"type\":\"whatever\"}"));
        handler.handleTextMessage(session.delegate, new TextMessage("not-json"));

        assertThat(session.frames).isEmpty();
    }

    @Test
    @DisplayName("空闲连接被回收（90s 阈值）")
    void idleSessionsAreClosed() throws Exception {
        FakeSession session = connect("s-8", TOPIC, WsEventId.NO_SEQ);
        assertThat(sessions.count()).isEqualTo(1);

        clock.advanceMillis(89_000L);
        assertThat(sessions.closeIdle()).isZero();

        clock.advanceMillis(2_000L);
        assertThat(sessions.closeIdle()).isEqualTo(1);
        assertThat(sessions.count()).isZero();
        assertThat(session.closed).isTrue();
    }

    @Test
    @DisplayName("连接关闭后从注册表摘除，后续推送不再尝试发送")
    void closedSessionIsUnregistered() throws Exception {
        FakeSession session = connect("s-9", TOPIC, WsEventId.NO_SEQ);
        handler.afterConnectionClosed(session.delegate, org.springframework.web.socket.CloseStatus.NORMAL);

        WsPushService.WsPushResult result = pushService.publish(TOPIC, WsFrameType.ALARM, alarmPayload("a-1"));

        assertThat(result.deliveredSessions()).isZero();
        assertThat(sessions.countOf(TOPIC)).isZero();
    }

    // —— 辅助 —— //

    private FakeSession connect(String sessionId, String topic, long lastEventSeq) throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(WsHandshakeInterceptor.ATTR_SUBSCRIPTION,
                new WsSubscription("u-1", List.of(topic), lastEventSeq));
        return new FakeSession(sessionId, attributes, handler);
    }

    private static AlarmPayload alarmPayload(String alarmId) {
        return new AlarmPayload(alarmId, "MISS", "WARN", "08:00 计划服药未按时服用", "e-1001", List.of("m-205"));
    }

    /** 替身连接：记下所有发出的帧，供断言。 */
    private static final class FakeSession {

        private final WebSocketSession delegate;
        private final List<JsonNode> frames = new ArrayList<>();
        private boolean closed;

        private FakeSession(String id, Map<String, Object> attributes, MedboxWebSocketHandler handler)
                throws Exception {
            WebSocketSession session = mock(WebSocketSession.class);
            when(session.getId()).thenReturn(id);
            when(session.isOpen()).thenReturn(true);
            when(session.getAttributes()).thenReturn(attributes);
            doAnswer(invocation -> {
                frames.add(JsonMapper.builder().build()
                        .readTree(((TextMessage) invocation.getArgument(0)).getPayload()));
                return null;
            }).when(session).sendMessage(any());
            doAnswer(invocation -> {
                closed = true;
                return null;
            }).when(session).close(any());
            this.delegate = session;
            handler.afterConnectionEstablished(session);
        }

        private JsonNode lastFrame(ObjectMapper mapper) {
            return frames.get(frames.size() - 1);
        }
    }
}
