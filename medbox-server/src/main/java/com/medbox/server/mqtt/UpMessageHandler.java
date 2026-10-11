package com.medbox.server.mqtt;

/**
 * 上行消息处理器（**扩展点**，见文档 03 第 2~3 章的 topic 表）。
 *
 * <p>{@code feat/backend-mqtt} 只搭骨架：<b>本分支的业务处理全部留给后续分支</b>，
 * 后续分支只需加一个新类实现本接口并注册成 Spring Bean（照
 * {@code UpMessageHandler} → {@code MqttConfig} 里的写法），
 * <b>不必改动本分支的任何代码</b> —— 网关按 {@link #supports()} 匹配后直接调用。
 *
 * <pre>
 * up/telemetry        → supports() = telemetry            → feat/env-core
 * up/event/dispense   → supports() = event/dispense       → feat/dispense-consume
 * up/event/error      → supports() = event/error          → feat/device-fault
 * up/sensor/heartbeat → supports() = sensor/heartbeat     → feat/device-sensor
 * up/inventory        → supports() = inventory            → feat/inventory-consume
 * up/ack              → supports() = ack                  → 本分支（下行回执结算）
 * </pre>
 *
 * <p><b>处理器收到的 payload 已经过去重</b>：同一 {@code msgId} 只会进来一次，
 * 实现里不必自己再判幂等（这也是为什么赤裸加上去重是必要的地基）。
 */
public interface UpMessageHandler {

    /**
     * 本处理器负责的 topic 子路径（{@code up/} 之后的部分，如 {@code event/dispense}）。
     *
     * <p>兜底实现返回 {@link #FALLBACK}，匹配不上任何具体处理器时由它兜住（只记日志）。
     */
    String supports();

    /** 兜底处理器的 {@link #supports()} 值。 */
    String FALLBACK = "*";

    /**
     * 处理一条上行消息。异常会由 {@link MqttInboundGateway} 兜住并记录，不会外溢到 Paho 回调线程。
     *
     * @param deviceId    设备业务 ID（解析自 topic 第 3 层）
     * @param topic       完整上行 topic（排障用）
     * @param payloadJson 原始 JSON 字符串（未解析，由各分支按自己的 DTO 反序列化）
     */
    void handle(String deviceId, String topic, String payloadJson);
}
