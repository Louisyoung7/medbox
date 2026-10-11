package com.medbox.server.mqtt;

import com.medbox.server.mqtt.MqttTopics.InboundTopic;
import com.medbox.server.mqtt.payload.StatusPayload;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 上行消息的入口网关：把 Paho 的原生回调翻译成"已去重的业务消息"。
 *
 * <p>链路（每条上行消息固定走一遍）：
 * <pre>
 * messageArrived → 解析 topic → [status] 更新在线态
 *                            → [up/**] 取 msgId → 去重 → 刷新在线态 → 路由给 UpMessageHandler
 * </pre>
 *
 * <p><b>{@link #onMessage} 必须兜住所有异常</b>：它在 Paho 的回调线程里执行，
 * 异常外溢到 Paho 只会让后续消息被静默丢弃（甚至看起来"设备不发消息了"），日志里还不一定有线索。
 *
 * <p><b>幂等在这里做，各业务分支就不用各自去重</b>：{"@code msgId} 先落 {@code mqtt_message} 唯一键，
 * 抢到才继续；这里保证"同一条上行只进 handler 一次"。
 */
public class MqttInboundGateway {

    private static final Logger log = LoggerFactory.getLogger(MqttInboundGateway.class);

    private final MqttProperties props;
    private final ObjectMapper objectMapper;
    private final MqttMessageDeduplicator deduplicator;
    private final DeviceStatusService deviceStatusService;
    private final List<UpMessageHandler> handlers;

    public MqttInboundGateway(MqttProperties props,
                              ObjectMapper objectMapper,
                              MqttMessageDeduplicator deduplicator,
                              DeviceStatusService deviceStatusService,
                              List<UpMessageHandler> handlers) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.deduplicator = deduplicator;
        this.deviceStatusService = deviceStatusService;
        this.handlers = handlers;
    }

    /**
     * Paho 回调入口（由 {@link MqttConnectionManager} 调用）。
     *
     * @param topic   完整 topic
     * @param payload 原始字节；**这里保证不抛异常**
     */
    public void onMessage(String topic, byte[] payload) {
        try {
            dispatch(topic, payload);
        } catch (RuntimeException ex) {
            log.error("处理上行消息失败 topic={}", topic, ex);
        }
    }

    private void dispatch(String topic, byte[] payload) {
        Optional<InboundTopic> parsed = MqttTopics.parse(props.getTopicPrefix(), topic);
        if (parsed.isEmpty()) {
            log.warn("无法识别的上行 topic，已丢弃：{}", topic);
            return;
        }
        if (payload == null || payload.length == 0) {
            log.warn("上行消息 payload 为空，已丢弃 topic={}", topic);
            return;
        }
        InboundTopic ref = parsed.get();
        String raw = new String(payload, StandardCharsets.UTF_8);
        if (ref.lwt()) {
            handleStatus(ref, raw);
            return;
        }
        handleUplink(ref, raw);
    }

    /**
     * 遗嘱 / 在线状态 topic。
     *
     * <p><b>它是唯一不去重的上行消息</b>：retained 的状态消息语义是"最新态"而非"一次事件"，
     * 去重会把"从在线变离线"的变化吞掉。
     */
    private void handleStatus(InboundTopic ref, String raw) {
        Boolean online;
        try {
            online = objectMapper.readValue(raw, StatusPayload.class).getOnline();
        } catch (JacksonException ex) {
            log.warn("status 消息解析失败 deviceId={} topic={}：{}", ref.deviceId(), ref.raw(), ex.getMessage());
            return;
        }
        boolean onlineFlag = Boolean.TRUE.equals(online);
        if (onlineFlag) {
            deviceStatusService.markOnline(ref.deviceId());
        } else {
            deviceStatusService.markOffline(ref.deviceId());
        }
        log.info("设备在线状态变更 deviceId={} online={}", ref.deviceId(), onlineFlag);
    }

    private void handleUplink(InboundTopic ref, String raw) {
        String msgId = extractMsgId(raw);
        if (msgId == null) {
            log.warn("上行消息缺少 msgId，已丢弃 deviceId={} topic={}", ref.deviceId(), ref.raw());
            return;
        }
        if (!deduplicator.claim(msgId, ref.deviceId(), ref.raw())) {
            // 设备断线重连重发 —— 正常现象，INFO 一条便于确认去重生效
            log.info("重复 msgId 已丢弃 deviceId={} msgId={} topic={}", ref.deviceId(), msgId, ref.subPath());
            return;
        }
        // 能发出上行消息 = 设备还活着；固件没实现 retained status 时，靠这条也能维持在线态
        deviceStatusService.markAlive(ref.deviceId());

        UpMessageHandler handler = handlers.stream()
            .filter(item -> item.supports().equals(ref.subPath()))
            .findFirst()
            .orElseGet(() -> handlers.stream()
                .filter(item -> UpMessageHandler.FALLBACK.equals(item.supports()))
                .findFirst()
                .orElse(null));
        if (handler == null) {
            log.warn("没有可用的上行处理器 deviceId={} subPath={}", ref.deviceId(), ref.subPath());
            return;
        }
        handler.handle(ref.deviceId(), ref.raw(), raw);
    }

    /**
     * 只取 {@code msgId}，不把整条消息绑成某个 DTO —— 上行要先去重再路由，
     * 具体类型由各自的 {@link UpMessageHandler} 反序列化，网关不感知业务字段。
     *
     * @return 取到返回 msgId；JSON 非法或缺字段返回 {@code null}（调用方据此丢弃该消息）
     */
    private String extractMsgId(String raw) {
        JsonNode node;
        try {
            node = objectMapper.readTree(raw);
        } catch (JacksonException ex) {
            log.warn("上行 payload 不是合法 JSON，已丢弃：{}", summarize(raw));
            return null;
        }
        String msgId = node.path("msgId").asString("");
        return msgId.isBlank() ? null : msgId;
    }

    private String summarize(String raw) {
        String trimmed = raw.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
    }
}
