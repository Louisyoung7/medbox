package com.medbox.server.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MQTT 接入配置（{@code medbox.mqtt.*}，见文档 03 第 1 章）。
 *
 * <p>{@code enabled} 是逃生开关：本机没起 EMQX 或 CI 环境设为 {@code false}，
 * 整个 {@code mqtt} 包的 Bean 都不装配，应用照常启动（与测试期关 Flyway 同理）。
 */
@ConfigurationProperties(prefix = "medbox.mqtt")
public class MqttProperties {

    /** 是否启用 MQTT 接入；false 时不建连接、也不订阅（缺 broker 也能启动）。 */
    private boolean enabled = true;

    /** Broker 地址，如 {@code tcp://localhost:1883}。入库配置里不写内网 IP。 */
    private String broker = "tcp://localhost:1883";

    /** 后端在 EMQX 里的 clientId（设备侧是 deviceId，后端是固定的服务端标识）。 */
    private String clientId = "medbox-server-01";

    /** 用户名；本期 EMQX 匿名连接，为空即不发送。 */
    private String username;

    /** 密码；同上，为空即不发送。 */
    private String password;

    /** Topic 一级前缀（完整形态 {@code {prefix}/{productKey}/{deviceId}/...}）。 */
    private String topicPrefix = "medbox";

    /** 订阅 QoS。投递最终按发布方的 QoS（telemetry/heartbeat 是 0）。 */
    private int subscribeQos = 1;

    /** KeepAlive，与设备端一致。 */
    private int keepAliveSeconds = 60;

    /**
     * 干净会话。取 true：会话状态不留在 broker 侧，重连后在
     * {@code connectComplete} 回调里显式重新订阅，避免"以为订阅还在"的静默丢消息。
     */
    private boolean cleanSession = true;

    /** 是否开启 Paho 自动重连。 */
    private boolean autoReconnect = true;

    /** 单次连接超时；连不上不阻塞启动，只 WARN 并交给 Paho 后台重连。 */
    private int connectionTimeoutSeconds = 10;

    /** 下行消息等待 {@code up/ack} 的超时（文档 03：超时重发）。 */
    private long ackTimeoutMs = 5000L;

    /** 含首发在内的总下发次数（1 次首发 + {@code maxAttempts-1} 次重发）。 */
    private int maxAttempts = 3;

    /** 待回执账本的扫描间隔。 */
    private long retryScanIntervalMs = 1000L;

    /** 去重记录保留天数（只要覆盖设备的重发窗口）。 */
    private long dedupRetentionDays = 7L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBroker() {
        return broker;
    }

    public void setBroker(String broker) {
        this.broker = broker;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getTopicPrefix() {
        return topicPrefix;
    }

    public void setTopicPrefix(String topicPrefix) {
        this.topicPrefix = topicPrefix;
    }

    public int getSubscribeQos() {
        return subscribeQos;
    }

    public void setSubscribeQos(int subscribeQos) {
        this.subscribeQos = subscribeQos;
    }

    public int getKeepAliveSeconds() {
        return keepAliveSeconds;
    }

    public void setKeepAliveSeconds(int keepAliveSeconds) {
        this.keepAliveSeconds = keepAliveSeconds;
    }

    public boolean isCleanSession() {
        return cleanSession;
    }

    public void setCleanSession(boolean cleanSession) {
        this.cleanSession = cleanSession;
    }

    public boolean isAutoReconnect() {
        return autoReconnect;
    }

    public void setAutoReconnect(boolean autoReconnect) {
        this.autoReconnect = autoReconnect;
    }

    public int getConnectionTimeoutSeconds() {
        return connectionTimeoutSeconds;
    }

    public void setConnectionTimeoutSeconds(int connectionTimeoutSeconds) {
        this.connectionTimeoutSeconds = connectionTimeoutSeconds;
    }

    public long getAckTimeoutMs() {
        return ackTimeoutMs;
    }

    public void setAckTimeoutMs(long ackTimeoutMs) {
        this.ackTimeoutMs = ackTimeoutMs;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getRetryScanIntervalMs() {
        return retryScanIntervalMs;
    }

    public void setRetryScanIntervalMs(long retryScanIntervalMs) {
        this.retryScanIntervalMs = retryScanIntervalMs;
    }

    public long getDedupRetentionDays() {
        return dedupRetentionDays;
    }

    public void setDedupRetentionDays(long dedupRetentionDays) {
        this.dedupRetentionDays = dedupRetentionDays;
    }
}
