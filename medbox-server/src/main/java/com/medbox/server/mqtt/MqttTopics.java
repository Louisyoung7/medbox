package com.medbox.server.mqtt;

import java.util.List;
import java.util.Optional;

/**
 * Topic 拼装与解析（协议见文档 03 第 2 章）。
 *
 * <p>统一形态：<b>{@code medbox/{productKey}/{deviceId}/up|down|status/...}</b>。
 * 前缀没有前导斜杠（MQTT 规范里会产生空层级），因此解析时下标从 0 开始：
 *
 * <pre>
 * medbox / {pk} / BOXA1001 / up / telemetry      → deviceId=BOXA1001, subPath=telemetry
 * medbox / {pk} / BOXA1001 / up / event/dispense → deviceId=BOXA1001, subPath=event/dispense
 * medbox / {pk} / BOXA1001 / status (LWT)        → deviceId=BOXA1001, subPath=status
 * </pre>
 *
 * <p>解析失败<b>不抛异常</b>：上行 topic 可能来自任何客户端（本机匿名 broker 尤其如此），
 * 一条畸形消息不该打断回调线程，返回 {@link Optional#empty()} 交给上层丢弃 + 记日志。
 */
public final class MqttTopics {

    /** 上行方向常量。 */
    public static final String UP = "up";

    /** 下行方向常量。 */
    public static final String DOWN = "down";

    /** LWT / 在线状态/topic 最后一段。 */
    public static final String STATUS = "status";

    private MqttTopics() {
    }

    /**
     * 后端要订阅的 topic 列表：只订阅上行（{@code up/#}）与设备遗嘱状态（{@code status}）。
     *
     * <p>第 3 层用 {@code +} 通配 deviceId —— 后端不按药箱逐个订阅，
     * 设备上线即被覆盖；# 放在最后是 MQTT 规范允许的"多层通配必须结尾"。
     */
    public static List<String> inboundTopics(String topicPrefix) {
        return List.of(
            topicPrefix + "/+/+/" + UP + "/#",
            topicPrefix + "/+/+/" + STATUS
        );
    }

    /**
     * 拼下行 topic，如 {@code medbox/{pk}/BOXA1001/down/schedule}。
     *
     * @param topicPrefix 一级前缀
     * @param productKey  产品 key（来自 {@code device.product_key}）
     * @param deviceId    药箱主控业务 ID
     * @param subTopic    {@code schedule} / {@code command}
     */
    public static String downTopic(String topicPrefix, String productKey, String deviceId, String subTopic) {
        return topicPrefix + "/" + productKey + "/" + deviceId + "/" + DOWN + "/" + subTopic;
    }

    /**
     * 解析上行 topic。
     *
     * @return 解析成功返回引用；topic 形态不符（层级不足、不是本前缀、不是 up/status）返回空
     */
    public static Optional<InboundTopic> parse(String topicPrefix, String topic) {
        if (topic == null || topic.isBlank()) {
            return Optional.empty();
        }
        String[] parts = topic.split("/");
        // 最短形态：{prefix}/{pk}/{deviceId}/status → 4 段
        if (parts.length < 4 || !parts[0].equals(topicPrefix)) {
            return Optional.empty();
        }
        String productKey = parts[1];
        String deviceId = parts[2];
        String kind = parts[3];
        if (deviceId.isBlank() || productKey.isBlank()) {
            return Optional.empty();
        }
        if (STATUS.equals(kind)) {
            return Optional.of(new InboundTopic(topic, productKey, deviceId, STATUS, true));
        }
        if (!UP.equals(kind)) {
            return Optional.empty();
        }
        String subPath = String.join("/", java.util.Arrays.copyOfRange(parts, 4, parts.length));
        if (subPath.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new InboundTopic(topic, productKey, deviceId, subPath, false));
    }

    /**
     * 解析结果：把 topic 里有用的字段一次性抽出，避免各处重复 split。
     *
     * @param raw        完整 topic（排障用，日志里必须带上）
     * @param productKey 产品 key
     * @param deviceId   设备业务 ID（也是 MQTT clientId）
     * @param subPath    {@code up/} 之后的部分，如 {@code telemetry} / {@code event/dispense} / {@code ack}；
     *                   LWT 的 {@code status} 也是这个值（此时 {@code lwt=true}）
     * @param lwt        是否是遗嘱 / 状态 topic
     */
    public record InboundTopic(
        String raw,
        String productKey,
        String deviceId,
        String subPath,
        boolean lwt
    ) {
    }
}
