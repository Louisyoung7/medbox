package com.medbox.server.ws;

import java.util.List;

/**
 * 握手鉴权的产物：这条连接是谁、订阅了哪些主题、补推从哪里开始。
 *
 * <p>由 {@link WsHandshakeInterceptor} 鉴权通过后写进 handshake attributes，
 * 再由 {@link MedboxWebSocketHandler} 取出并注册进 {@link WsSessionManager}。
 *
 * @param userId       当前登录用户的业务 user_id
 * @param topics       已通过归属鉴权的订阅主题（{@code elder:{elderId}}）
 * @param lastEventSeq 客户端带来的 {@code lastEventId} 对应序号；{@link WsEventId#NO_SEQ} 表示首次连接、无需补推
 */
public record WsSubscription(String userId, List<String> topics, long lastEventSeq) {

    /** 是否需要补推（带了合法的 lastEventId 才补）。 */
    public boolean needReplay() {
        return lastEventSeq > WsEventId.NO_SEQ;
    }
}
