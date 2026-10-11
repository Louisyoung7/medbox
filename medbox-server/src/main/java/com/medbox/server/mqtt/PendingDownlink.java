package com.medbox.server.mqtt;

/**
 * 一条待回执的下行消息（内存账本里的条目）。
 *
 * @param msgId            下行消息 ID，**重发时保持不变**，设备侧据此去重
 * @param deviceId         目标设备（药箱主控）
 * @param topic            下行 topic
 * @param payloadJson      序列化后的消息体（重发时原样再发一遍，含 msgId / needAck）
 * @param attempts         已发送次数，首次为 1；达到 {@code medbox.mqtt.max-attempts} 即放弃
 * @param nextRetryAtMillis 下一次重发的时刻（epoch millis）
 */
public record PendingDownlink(
    String msgId,
    String deviceId,
    String topic,
    String payloadJson,
    int attempts,
    long nextRetryAtMillis
) {
}
