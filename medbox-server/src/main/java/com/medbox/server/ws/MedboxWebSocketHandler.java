package com.medbox.server.ws;

import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * WebSocket 主处理器：连接注册、断线补推、心跳应答、异常兜底（见文档 04）。
 *
 * <p><b>补推时机</b>：连接建立后立刻按握手带来的 {@code lastEventId} 补发增量；窗口已过期则回一条
 * {@code RESYNC}，让客户端改走 REST 拉全量（否则它分不清"没有新事件"和"事件已被淘汰"）。
 *
 * <p><b>心跳</b>：客户端每 30s 发 {@code {"type":"ping"}}，这里回 {@code PONG} 并刷新活跃时间；
 * 服务端不主动 ping —— 断开僵死连接交给 {@link WsIdleSessionReaper} 按 90s 空闲阈值扫描。
 *
 * <p><b>统一兜异常</b>：handler 跑在容器的 WebSocket 回调线程上，异常外溢只会打进 Tomcat 日志，
 * 与 {@code MqttInboundGateway} 同一条纪律 —— 入口处 try/catch 掉，不让一条坏消息搞挂连接。
 */
public class MedboxWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MedboxWebSocketHandler.class);

    private static final String TYPE_FIELD = "type";
    private static final String PING = "ping";
    private static final String REASON_WINDOW_EXPIRED = "WINDOW_EXPIRED";

    private final WsSessionManager sessions;
    private final WsEventBuffer buffer;
    private final WsPushService pushService;
    private final ObjectMapper objectMapper;

    public MedboxWebSocketHandler(WsSessionManager sessions, WsEventBuffer buffer,
                                  WsPushService pushService, ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.buffer = buffer;
        this.pushService = pushService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        WsSubscription subscription = subscriptionOf(session);
        if (subscription == null) {
            log.warn("WS 连接缺少鉴权信息，拒绝 session={}", session.getId());
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        sessions.register(session, subscription.topics());
        if (subscription.needReplay()) {
            replay(session, subscription);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        sessions.touch(session);
        String type = typeOf(message.getPayload());
        if (PING.equals(type)) {
            // PONG 是控制帧：不带 topic（一条连接可能订阅多个主题），也不进补推窗口
            send(session, WsFrame.of(WsFrameType.PONG, pushService.nextId(), null, System.currentTimeMillis()));
            return;
        }
        log.debug("忽略未知帧类型 type={} session={}", type, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.unregister(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WS 传输异常 session={}：{}", session.getId(), exception.getMessage());
        sessions.unregister(session);
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    /** 补推：逐主题发增量，窗口过期发 RESYNC。 */
    private void replay(WebSocketSession session, WsSubscription subscription) {
        for (String topic : subscription.topics()) {
            WsEventBuffer.ReplayResult result = buffer.replaySince(topic, subscription.lastEventSeq());
            if (result.windowExpired()) {
                log.info("WS 补推窗口已过期 session={} topic={} lastSeq={}",
                        session.getId(), topic, subscription.lastEventSeq());
                send(session, WsFrame.of(WsFrameType.RESYNC, pushService.nextId(), topic,
                        System.currentTimeMillis(),
                        Map.of("reason", REASON_WINDOW_EXPIRED, "latestEventId",
                                result.latestEventId() == null ? "" : result.latestEventId())));
                continue;
            }
            for (WsFrame frame : result.events()) {
                send(session, frame);
            }
            if (!result.events().isEmpty()) {
                log.info("WS 补推 {} 条 session={} topic={}", result.events().size(), session.getId(), topic);
            }
        }
    }

    private void send(WebSocketSession session, WsFrame frame) {
        try {
            sessions.send(session, objectMapper.writeValueAsString(frame));
        } catch (RuntimeException ex) {
            log.error("WS 帧序列化失败 type={}：{}", frame.type(), ex.getMessage());
        }
    }

    private String typeOf(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode type = node == null ? null : node.get(TYPE_FIELD);
            return type == null || type.isNull() ? null : type.asString();
        } catch (RuntimeException ex) {
            log.debug("WS 收到非法 JSON，忽略：{}", ex.getMessage());
            return null;
        }
    }

    private static WsSubscription subscriptionOf(WebSocketSession session) {
        Object attribute = session.getAttributes().get(WsHandshakeInterceptor.ATTR_SUBSCRIPTION);
        return attribute instanceof WsSubscription subscription ? subscription : null;
    }

    /** 该主题当前的订阅连接数（排障与测试用）。 */
    public int subscriberCount(String topic) {
        return Optional.ofNullable(sessions).map(value -> value.countOf(topic)).orElse(0);
    }
}
