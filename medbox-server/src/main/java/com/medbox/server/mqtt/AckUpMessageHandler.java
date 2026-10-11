package com.medbox.server.mqtt;

import com.medbox.server.mqtt.payload.AckPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * 下行回执 {@code up/ack} 的处理器（见文档 03 第 3 章）。
 *
 * <p>设备用 {@code ackFor} 指回下行消息的 {@code msgId}（不是自己的上行 msgId），
 * 交给 {@link PendingAckRegistry} 结算：OK → 账本里移除不再重发；FAILED → 终态，也不重发
 * （设备明说做不了，再重发没有意义）。
 *
 * <p>{@code ackFor} 找不到对应条目（已超时失败 / 已完成）属正常：多台后端实例或重启都会造成
 * "回执回到还没有账本的一方"，只记 DEBUG。
 */
public class AckUpMessageHandler implements UpMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(AckUpMessageHandler.class);

    private final PendingAckRegistry registry;
    private final ObjectMapper objectMapper;

    public AckUpMessageHandler(PendingAckRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public String supports() {
        return "ack";
    }

    @Override
    public void handle(String deviceId, String topic, String payloadJson) {
        AckPayload ack;
        try {
            ack = objectMapper.readValue(payloadJson, AckPayload.class);
        } catch (Exception ex) {
            log.warn("up/ack 解析失败 deviceId={} topic={}：{}", deviceId, topic, ex.getMessage());
            return;
        }
        if (ack.getAckFor() == null || ack.getAckFor().isBlank()) {
            log.warn("up/ack 缺少 ackFor deviceId={} topic={}", deviceId, topic);
            return;
        }
        boolean settled = registry.settle(ack.getAckFor(), ack.isOk(), ack.getReason());
        if (settled) {
            log.info("下行回执已结算 deviceId={} ackFor={} result={}", deviceId, ack.getAckFor(), ack.getResult());
        } else {
            log.debug("下行回执找不到对应条目（已超时或已完成）deviceId={} ackFor={}", deviceId, ack.getAckFor());
        }
    }
}
