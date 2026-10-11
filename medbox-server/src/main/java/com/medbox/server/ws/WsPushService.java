package com.medbox.server.ws;

import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * WebSocket 推送的**唯一出口**（见文档 04）。
 *
 * <p>后续业务分支（{@code feat/miss-alarm}、{@code feat/plan-reminder}、{@code feat/env-alarm}、
 * {@code feat/alarm-core}）一律只调 {@link #publish}，**不要自己持有 WebSocketSession** ——
 * 与 MQTT 侧"下行出口只有 {@code DownlinkPublisher}"是同一条约定：通道细节收在一处，
 * 换实现（多实例广播 / 外部 broker）时调用方零改动。
 *
 * <p>流程：发号（单调递增序号）→ 组装帧 → 写补推缓冲 → 扇出到订阅该主题的连接。
 * **没人订阅也照样写缓冲**：客户端稍后重连时还能补到，不会因为"当时没人在线"而丢事件。
 */
public class WsPushService {

    private static final Logger log = LoggerFactory.getLogger(WsPushService.class);

    /** 推送结果。 */
    public record WsPushResult(String eventId, int deliveredSessions) {
    }

    private final WsEventBuffer buffer;
    private final WsSessionManager sessions;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong(WsEventId.NO_SEQ);

    public WsPushService(WsEventBuffer buffer, WsSessionManager sessions, ObjectMapper objectMapper) {
        this(buffer, sessions, objectMapper, Clock.systemUTC());
    }

    public WsPushService(WsEventBuffer buffer, WsSessionManager sessions,
                         ObjectMapper objectMapper, Clock clock) {
        this.buffer = buffer;
        this.sessions = sessions;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 推一条事件帧。
     *
     * @param topic   订阅主题（{@code elder:{elderId}}）
     * @param type    帧类型，只允许 {@link WsFrameType#pushable()} 的业务帧
     * @param payload 各类型自己的载荷（见 {@code ws/payload/}）
     * @return 事件 ID 与本次送达的连接数（0 表示当时无人订阅，但事件已进补推窗口）
     */
    public WsPushResult publish(String topic, WsFrameType type, Object payload) {
        BizAssert.notBlank(topic, "topic 不能为空");
        BizAssert.isTrue(type != null && type.pushable(), ErrorCode.BAD_REQUEST, "该帧类型不允许主动推送");

        String eventId = nextId();
        long seq = WsEventId.parse(eventId).orElse(WsEventId.NO_SEQ);
        WsFrame frame = WsFrame.of(type, eventId, topic, clock.millis(), payload);
        buffer.append(topic, seq, frame);

        int delivered = sessions.broadcast(topic, toJson(frame, eventId));
        log.debug("WS 推送 {} -> topic={} 送达 {} 条连接", type, topic, delivered);
        return new WsPushResult(eventId, delivered);
    }

    /**
     * 取下一个事件 ID（只发号、不写缓冲）：给 {@code PONG} / {@code RESYNC} 这类控制帧用 ——
     * 它们不进补推窗口，但仍要一个单调可比较的 id。
     */
    public String nextId() {
        return WsEventId.format(sequence.incrementAndGet());
    }

    /** 当前已发出的最大序号（排障与测试用）。 */
    public long currentSeq() {
        return sequence.get();
    }

    private String toJson(WsFrame frame, String eventId) {
        try {
            return objectMapper.writeValueAsString(frame);
        } catch (RuntimeException ex) {
            // 帧序列化失败属于内部错误：不能让业务调用方以为推送成功了
            log.error("WS 帧序列化失败 eventId={}：{}", eventId, ex.getMessage());
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "推送消息序列化失败");
        }
    }
}
