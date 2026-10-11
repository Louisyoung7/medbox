package com.medbox.server.ws;

import java.io.IOException;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * WebSocket 连接注册表：主题 → 连接的多对多索引 + 发送 + 空闲回收。
 *
 * <p><b>并发模型</b>：用 {@code topic → sessionId} 的倒排索引而不是 {@code topic → session}，
 * 主题解绑时不必遍历全部连接；单个 {@link WebSocketSession} 的 {@code sendMessage} 不是并发安全的，
 * 因此以连接状态对象为锁串行发送（同一连接的多条帧不会互相穿插）。
 *
 * <p><b>发送失败即摘除</b>：写失败说明连接已经废了（客户端断开 / 缓冲区爆），留着只会让后续广播
 * 反复失败，这里直接注销并关连接。
 */
public class WsSessionManager {

    private static final Logger log = LoggerFactory.getLogger(WsSessionManager.class);

    private final long idleTimeoutMillis;
    private final Clock clock;

    /** sessionId → 连接状态。 */
    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    /** topic → 订阅它的 sessionId 集合（倒排索引，避免广播时遍历全部连接）。 */
    private final ConcurrentHashMap<String, Set<String>> topicIndex = new ConcurrentHashMap<>();

    public WsSessionManager(WsProperties props) {
        this(props, Clock.systemUTC());
    }

    public WsSessionManager(WsProperties props, Clock clock) {
        this.idleTimeoutMillis = props.getIdleTimeoutSeconds() * 1000L;
        this.clock = clock;
    }

    /** 单条连接的状态：既是发送锁，也记最后活跃时间。 */
    private static final class SessionState {

        private final WebSocketSession session;
        private final Set<String> topics;
        private volatile long lastActiveAtMillis;

        private SessionState(WebSocketSession session, Set<String> topics, long nowMillis) {
            this.session = session;
            this.topics = topics;
            this.lastActiveAtMillis = nowMillis;
        }
    }

    /** 注册连接并建立主题倒排索引。 */
    public void register(WebSocketSession session, List<String> topics) {
        SessionState state = new SessionState(session, Set.copyOf(topics), clock.millis());
        sessions.put(session.getId(), state);
        for (String topic : state.topics) {
            topicIndex.computeIfAbsent(topic, key -> ConcurrentHashMap.newKeySet()).add(session.getId());
        }
        log.info("WS 连接建立 session={} userTopics={} 当前连接数={}", session.getId(), state.topics, sessions.size());
    }

    /** 注销连接（关闭 / 异常 / 发送失败都走这里），幂等。 */
    public void unregister(WebSocketSession session) {
        SessionState state = sessions.remove(session.getId());
        if (state == null) {
            return;
        }
        for (String topic : state.topics) {
            Set<String> ids = topicIndex.get(topic);
            if (ids == null) {
                continue;
            }
            ids.remove(session.getId());
            if (ids.isEmpty()) {
                topicIndex.remove(topic, ids);
            }
        }
        log.info("WS 连接移除 session={} 剩余连接数={}", session.getId(), sessions.size());
    }

    /** 刷新最后活跃时间（收到任何客户端帧都算活着）。 */
    public void touch(WebSocketSession session) {
        SessionState state = sessions.get(session.getId());
        if (state != null) {
            state.lastActiveAtMillis = clock.millis();
        }
    }

    /** 向订阅该主题的所有连接广播一条已序列化的帧，返回送达连接数。 */
    public int broadcast(String topic, String json) {
        Set<String> ids = topicIndex.getOrDefault(topic, Collections.emptySet());
        if (ids.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        for (String id : ids) {
            SessionState state = sessions.get(id);
            if (state != null && sendLocked(state, json)) {
                delivered++;
            }
        }
        return delivered;
    }

    /** 向单条连接发送（补推、PONG 用）。 */
    public boolean send(WebSocketSession session, String json) {
        SessionState state = sessions.get(session.getId());
        return state != null && sendLocked(state, json);
    }

    /** 订阅该主题的连接数。 */
    public int countOf(String topic) {
        return topicIndex.getOrDefault(topic, Collections.emptySet()).size();
    }

    /** 当前连接总数。 */
    public int count() {
        return sessions.size();
    }

    /** 关闭超过空闲阈值的连接，返回关闭条数（由 {@link WsIdleSessionReaper} 定时调用）。 */
    public int closeIdle() {
        long now = clock.millis();
        int closed = 0;
        for (SessionState state : sessions.values()) {
            if (now - state.lastActiveAtMillis < idleTimeoutMillis) {
                continue;
            }
            log.info("WS 连接空闲超时，关闭 session={} idleMs={}",
                    state.session.getId(), now - state.lastActiveAtMillis);
            closeQuietly(state.session);
            // 主动关闭后立刻摘除：容器的关闭回调不保证一定到达，留着会让广播反复往死连接写
            unregister(state.session);
            closed++;
        }
        return closed;
    }

    private boolean sendLocked(SessionState state, String json) {
        synchronized (state) {
            try {
                if (!state.session.isOpen()) {
                    unregister(state.session);
                    return false;
                }
                state.session.sendMessage(new TextMessage(json));
                return true;
            } catch (IOException | RuntimeException ex) {
                log.warn("WS 发送失败，注销连接 session={}：{}", state.session.getId(), ex.getMessage());
                unregister(state.session);
                closeQuietly(state.session);
                return false;
            }
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.NORMAL);
            }
        } catch (IOException | RuntimeException ex) {
            log.debug("关闭 WS 连接失败 session={}：{}", session.getId(), ex.getMessage());
        }
    }
}
