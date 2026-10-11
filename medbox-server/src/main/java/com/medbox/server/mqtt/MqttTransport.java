package com.medbox.server.mqtt;

/**
 * MQTT 收发能力的最小抽象 —— 只暴露"发得出去吗 / 发出去"两件事。
 *
 * <p>存在的意义是让下行链路可替换：真正连 broker 的是 {@link MqttConnectionManager}（Paho），
 * 而 {@code DownlinkPublisher} 只依赖本接口，测试里塞一个假实现就能验证"5s 重发 3 次"的账本逻辑，
 * 不必真的起 EMQX。
 */
public interface MqttTransport {

    /**
     * 当前是否已连上 broker。
     *
     * @return true 表示可以安全发布
     */
    boolean isConnected();

    /**
     * 发布一条消息。
     *
     * @param topic    完整 topic
     * @param payload  消息体字节
     * @param qos      0 / 1 / 2（下行计划与命令一律 QoS1，见文档 03 第 1 章）
     * @param retained 是否保留消息；下行消息一律 false
     * @throws MqttTransportException 未连接或发布失败
     */
    void publish(String topic, byte[] payload, int qos, boolean retained);
}
