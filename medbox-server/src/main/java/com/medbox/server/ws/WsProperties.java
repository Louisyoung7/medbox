package com.medbox.server.ws;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WebSocket 推送配置（{@code medbox.ws.*}，见文档 04）。
 *
 * <p>{@code enabled} 是逃生开关：设为 {@code false} 时整个 {@code ws} 包的 Bean 都不装配
 * （与 {@code medbox.mqtt.enabled} 同一套路），排障或 CI 里可以彻底关掉长连接通道。
 *
 * <p>{@code debug-endpoint-enabled} 默认 {@code false}：调试推送端点（供 {@code wscat} 验收）
 * 只在本地联调时打开，生产零暴露面。
 */
@ConfigurationProperties(prefix = "medbox.ws")
public class WsProperties {

    /** 是否启用 WebSocket 推送。 */
    private boolean enabled = true;

    /** 端点路径（context-path 之后），完整形态 {@code /medbox/ws}。 */
    private String endpoint = "/ws";

    /** 客户端 {@code ping} 间隔（秒），见文档 04 第 1 章。 */
    private int heartbeatSeconds = 30;

    /** 服务端判定连接僵死的空闲阈值（秒）：取 3 个心跳周期，容忍偶发丢包。 */
    private int idleTimeoutSeconds = 90;

    /** 空闲连接的扫描间隔（毫秒）。 */
    private long idleScanIntervalMs = 30_000L;

    /** 调试推送端点 {@code POST /api/v1/_debug/ws/publish} 开关，默认关闭。 */
    private boolean debugEndpointEnabled = false;

    /** 断线补推窗口配置。 */
    private Replay replay = new Replay();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public int getHeartbeatSeconds() {
        return heartbeatSeconds;
    }

    public void setHeartbeatSeconds(int heartbeatSeconds) {
        this.heartbeatSeconds = heartbeatSeconds;
    }

    public int getIdleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public void setIdleTimeoutSeconds(int idleTimeoutSeconds) {
        this.idleTimeoutSeconds = idleTimeoutSeconds;
    }

    public long getIdleScanIntervalMs() {
        return idleScanIntervalMs;
    }

    public void setIdleScanIntervalMs(long idleScanIntervalMs) {
        this.idleScanIntervalMs = idleScanIntervalMs;
    }

    public boolean isDebugEndpointEnabled() {
        return debugEndpointEnabled;
    }

    public void setDebugEndpointEnabled(boolean debugEndpointEnabled) {
        this.debugEndpointEnabled = debugEndpointEnabled;
    }

    public Replay getReplay() {
        return replay;
    }

    public void setReplay(Replay replay) {
        this.replay = replay;
    }

    /** 断线补推窗口：只保留最近 {@code maxEvents} 条 / {@code windowMinutes} 分钟的事件（文档 04 第 1 章）。 */
    public static class Replay {

        /** 每个主题最多保留的事件条数。 */
        private int maxEvents = 200;

        /** 事件保留窗口（分钟）。 */
        private int windowMinutes = 10;

        public int getMaxEvents() {
            return maxEvents;
        }

        public void setMaxEvents(int maxEvents) {
            this.maxEvents = maxEvents;
        }

        public int getWindowMinutes() {
            return windowMinutes;
        }

        public void setWindowMinutes(int windowMinutes) {
            this.windowMinutes = windowMinutes;
        }
    }
}
