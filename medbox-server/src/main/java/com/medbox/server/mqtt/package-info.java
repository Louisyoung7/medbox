/**
 * MQTT 层：EMQX 客户端、上下行消息通道、上行去重与下行 ack 重发，见文档 03。
 *
 * <p>{@code feat/backend-mqtt}（07 清单地基 P0 第 3 步）的职责边界：
 * <ul>
 *   <li><b>做</b>：连 broker、订阅 {@code up/#}、上行 {@code msgId} 去重、在线状态维护、
 *       {@code down/*} 发布与 {@code up/ack} 超时重发（5s × 3 次）；</li>
 *   <li><b>不做</b>：任何业务落库 —— 各上行 topic 的业务语义分散在
 *       {@code feat/env-core} / {@code feat/dispense-consume} / {@code feat/device-fault} /
 *       {@code feat/device-sensor} / {@code feat/inventory-consume}，
 *       它们实现 {@link com.medbox.server.mqtt.UpMessageHandler} 并注册成 Bean 即可生效。</li>
 * </ul>
 *
 * <p>缺 broker 也能启动：整包由 {@code medbox.mqtt.enabled} 条件装配，
 * 连不上时 {@code MqttConnectionManager} 只 WARN 并交给 Paho 后台重连。
 */
package com.medbox.server.mqtt;
