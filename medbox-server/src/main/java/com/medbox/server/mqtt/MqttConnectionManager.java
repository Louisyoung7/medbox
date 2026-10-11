package com.medbox.server.mqtt;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.StringUtils;

/**
 * Paho 客户端的生命周期与发布能力（RPC 另一 half：见文档 03 第 1 章）。
 *
 * <p><b>为什么用 {@code MqttAsyncClient}</b>：Paho 的自动重连（{@code setAutomaticReconnect}）
 * 只有异步客户端支持；同步 {@code MqttClient} 掉线后不会自己回来。
 *
 * <p><b>连不上必须不阻塞启动</b>：本机没起 EMQX、或 CI 里根本没有 broker 是常态
 * （测试环境靠 {@code MEDBOX_MQTT_ENABLED=false} 整包不装配；真机上缺 broker 时也要能起来做 REST），
 * 因此 {@link #start()} 的连接动作全程 try/catch，失败只 WARN，重连交给 Paho 后台。
 *
 * <p><b>回调不走业务 bean</b>：{@code messageArrived} 里直接转给 {@link MqttInboundGateway}，
 * 由网关统一兜异常 —— Paho 回调线程里抛异常只会让后续消息被静默丢弃，很难排障。
 */
public class MqttConnectionManager implements MqttTransport, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MqttConnectionManager.class);

    private final MqttProperties props;
    private final MqttInboundGateway gateway;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile MqttAsyncClient client;

    public MqttConnectionManager(MqttProperties props, MqttInboundGateway gateway) {
        this.props = props;
        this.gateway = gateway;
    }

    @Override
    public void start() {
        running.set(true);
        try {
            connectIfNeeded();
        } catch (Exception ex) {
            // 不能让"没起 broker"变成"应用起不来"：REST / WS 侧能力与该通道正交
            log.warn("MQTT 连接失败（本机 EMQX 起了吗？）—— 应用继续启动，Paho 将在后台自动重连：{}", ex.getMessage());
        }
    }

    @Override
    public void stop() {
        running.set(false);
        MqttAsyncClient current = client;
        client = null;
        if (current == null) {
            return;
        }
        try {
            if (current.isConnected()) {
                current.disconnect(2_000);
            }
            current.close(true);
        } catch (MqttException ex) {
            log.warn("MQTT 关闭异常：{}", ex.getMessage());
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isConnected() {
        MqttAsyncClient current = client;
        return current != null && current.isConnected();
    }

    /**
     * 发布一条消息。
     *
     * <p>等 {@code waitForCompletion} 而不是"发出即忘"：QoS1 的送达失败（如 broker 断开）
     * 要能被同步感知，下行计划 / 命令的调用方才能按失败处理。
     *
     * @throws MqttTransportException 未连接或发布失败（统一成运行时异常，调用方不必感知 Paho）
     */
    @Override
    public void publish(String topic, byte[] payload, int qos, boolean retained) {
        MqttAsyncClient current = client;
        if (current == null || !current.isConnected()) {
            throw new MqttTransportException("MQTT 未连接，无法发布 topic=" + topic);
        }
        org.eclipse.paho.client.mqttv3.MqttMessage message = new org.eclipse.paho.client.mqttv3.MqttMessage(payload);
        message.setQos(qos);
        // 下行消息一律不留 retained：只有设备的 status（在线态）才该是 retained，
        // 下行计划 / 命令留着会让新订阅的设备拿到一条陈旧的下发。
        message.setRetained(retained);
        try {
            IMqttDeliveryToken token = current.publish(topic, message);
            token.waitForCompletion(props.getConnectionTimeoutSeconds() * 1_000L);
            log.debug("MQTT 已发布 topic={} bytes={}", topic, payload.length);
        } catch (MqttException ex) {
            throw new MqttTransportException("MQTT 发布失败 topic=" + topic, ex);
        }
    }

    private synchronized void connectIfNeeded() throws MqttException {
        if (client != null && client.isConnected()) {
            return;
        }
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(props.isCleanSession());
        options.setKeepAliveInterval(props.getKeepAliveSeconds());
        options.setAutomaticReconnect(props.isAutoReconnect());
        options.setConnectionTimeout(props.getConnectionTimeoutSeconds());
        // 本期 EMQX 为匿名连接（allow_anonymous=true），用户名密码留空即不发送；
        // 将来关闭匿名时在这里补凭据（见文档 03 第 1 章）
        if (StringUtils.hasText(props.getUsername())) {
            options.setUserName(props.getUsername());
            if (props.getPassword() != null) {
                options.setPassword(props.getPassword().toCharArray());
            }
        }
        MqttAsyncClient created = new MqttAsyncClient(props.getBroker(), props.getClientId(), new MemoryPersistence());
        created.setCallback(pahoCallback());
        client = created;
        created.connect(options);
    }

    /**
     * 订阅上行 topic。
     *
     * <p>在 {@code connectComplete} 里订阅而不是只订阅一次：{@code cleanSession=true}
     * 时会话状态不留在 broker 侧，重连后必须显式重新订阅，否则"连接看着正常、消息一条都收不到"。
     */
    private synchronized void subscribe() {
        MqttAsyncClient current = client;
        if (current == null || !current.isConnected()) {
            return;
        }
        List<String> topics = MqttTopics.inboundTopics(props.getTopicPrefix());
        try {
            int[] qos = topics.stream().mapToInt(topic -> props.getSubscribeQos()).toArray();
            IMqttToken token = current.subscribe(topics.toArray(String[]::new), qos);
            token.waitForCompletion(props.getConnectionTimeoutSeconds() * 1_000L);
            log.info("MQTT 已连接并订阅 broker={} topics={}", props.getBroker(), topics);
        } catch (MqttException ex) {
            log.warn("MQTT 订阅失败 topics={}：{}", topics, ex.getMessage());
        }
    }

    private MqttCallbackExtended pahoCallback() {
        return new MqttCallbackExtended() {

            @Override
            public void connectComplete(boolean reconnect, String serverUri) {
                log.info("MQTT 连接就绪 broker={} reconnect={}", serverUri, reconnect);
                subscribe();
            }

            @Override
            public void connectionLost(Throwable cause) {
                log.warn("MQTT 连接断开，等待自动重连：{}", cause == null ? "unknown" : cause.getMessage());
            }

            @Override
            public void messageArrived(String topic, org.eclipse.paho.client.mqttv3.MqttMessage message) {
                gateway.onMessage(topic, message.getPayload());
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // 下行 QoS1 的成功由 publish() 里的 waitForCompletion 感知，这里不重复处理
            }
        };
    }
}
