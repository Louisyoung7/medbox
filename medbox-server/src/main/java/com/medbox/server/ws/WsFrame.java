package com.medbox.server.ws;

/**
 * 统一消息帧（见文档 04 第 2 章）。
 *
 * <pre>{@code
 * { "type":"ALARM","id":"evt-9a1c","topic":"elder:e-1001","ts":1759474520000,"payload":{ } }
 * }</pre>
 *
 * <p>{@code ts} 是 epoch <b>毫秒</b>（文档 04 帧示例即毫秒；与 REST 响应体 {@code R.timestamp}
 * 的 epoch 秒不同，别混）。{@code payload} 为 {@code null} 时（如 {@code PONG}）受全局
 * {@code spring.jackson.default-property-inclusion: non_null} 影响不出现在 JSON 里。
 *
 * @param type    帧类型
 * @param id      事件 ID（{@code evt-} + 递增序号的十六进制），用于断线补推
 * @param topic   订阅主题，如 {@code elder:e-1001}
 * @param ts      事件时间，epoch 毫秒
 * @param payload 各类型自己的载荷，见 {@code ws/payload/}
 */
public record WsFrame(WsFrameType type, String id, String topic, long ts, Object payload) {

    /** 无载荷的帧（PONG / RESYNC 不带业务数据时用）。 */
    public static WsFrame of(WsFrameType type, String id, String topic, long tsMillis) {
        return new WsFrame(type, id, topic, tsMillis, null);
    }

    public static WsFrame of(WsFrameType type, String id, String topic, long tsMillis, Object payload) {
        return new WsFrame(type, id, topic, tsMillis, payload);
    }
}
