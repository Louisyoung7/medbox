package com.medbox.server.devtools.dto;

/**
 * 模拟设备上报请求：等价于服务端版 {@code mosquitto_pub}。
 *
 * <p>{@code payload} 透传原始 JSON 字符串（与 {@code mosquitto_pub -m} 等价），不强制映射到具体 DTO，
 * 便于复刻 03 §5 里的任意上行报文。最终发到 {@code medbox/{productKey}/{deviceId}/up/{subTopic}}。
 */
public record MqttPublishRequest(

        /** 药箱主控设备 ID（同时是 MQTT clientId）。 */
        String deviceId,

        /** 上行子路径，如 {@code event/dispense}、{@code telemetry}、{@code sensor/heartbeat}、{@code inventory}。 */
        String subTopic,

        /** 产品 key；为空时取 {@code medbox.device.default-product-key}。 */
        String productKey,

        /** 透传的 payload 原文（JSON 字符串）。 */
        String payload
) {
}
