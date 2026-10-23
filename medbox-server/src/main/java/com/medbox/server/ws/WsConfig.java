package com.medbox.server.ws;

import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.service.AccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * WebSocket 各组件的装配（见文档 04 全文）。
 *
 * <p><b>整包条件装配</b>：{@code medbox.ws.enabled=false} 时这些 Bean 一个都不建，长连接通道彻底关掉
 * （与 {@code MqttConfig} 同一套路），排障与 CI 都能一键关。
 *
 * <p><b>用原生 WebSocket 而不是 STOMP</b>：文档 04 的帧是自定义 JSON（含 {@code ping/pong}、
 * 单调递增的事件 id 用于补推），STOMP 的 destination / 订阅语义表达不了"按 lastEventId 补增量"，
 * 还会白引入一层消息代理概念。
 *
 * <p><b>定时任务已挪走</b>：{@code @EnableScheduling} 原挂在本类，现移到独立的 {@code SchedulingConfig}
 * （feat/miss-alarm）——本包带 {@code medbox.ws.enabled} 条件，关 WS 不应连带关掉漏服扫描等定时任务。
 */
@Configuration
@EnableWebSocket
@EnableConfigurationProperties(WsProperties.class)
@ConditionalOnProperty(prefix = "medbox.ws", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WsConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WsConfig.class);

    private final WsProperties props;
    private final JwtProvider jwtProvider;
    private final AccessService accessService;
    private final ObjectMapper objectMapper;

    public WsConfig(WsProperties props, JwtProvider jwtProvider,
                    AccessService accessService, ObjectMapper objectMapper) {
        this.props = props;
        this.jwtProvider = jwtProvider;
        this.accessService = accessService;
        this.objectMapper = objectMapper;
    }

    /** 注册端点：context-path 是 {@code /medbox}，故完整路径 {@code ws://host:8080/medbox/ws}。 */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        WsSessionManager sessions = wsSessionManager();
        WsEventBuffer buffer = wsEventBuffer();
        WebSocketHandler handler = medboxWebSocketHandler(sessions, buffer, wsPushService(sessions, buffer));
        registry.addHandler(handler, props.getEndpoint())
                .addInterceptors(wsHandshakeInterceptor())
                .setAllowedOrigins("*");
        log.info("WebSocket 端点已注册：{}{}（心跳 {}s，空闲阈值 {}s，补推 {} 条 / {} 分钟）",
                "/medbox", props.getEndpoint(), props.getHeartbeatSeconds(), props.getIdleTimeoutSeconds(),
                props.getReplay().getMaxEvents(), props.getReplay().getWindowMinutes());
    }

    /**
     * <b>刻意不注册 {@code ServletServerContainerFactoryBean}</b>：它在 {@code afterPropertiesSet} 里就去
     * ServletContext 上取 {@code jakarta.websocket.server.ServerContainer}，而 Boot 4 下该属性要等内嵌
     * Tomcat 起来才有 —— 注册它会让整个应用上下文启动失败（且 {@code jakarta.websocket} API 只在
     * 运行期由 tomcat-embed-websocket 提供，编译期还引用不到）。
     *
     * <p>代价：单帧大小沿用容器默认（Tomcat 文本帧 8KB）。业务帧（告警 / 设备状态 / 提醒）远小于此，
     * 真需要放宽时再到容器层配。
     */
    @Bean
    public WsSessionManager wsSessionManager() {
        return new WsSessionManager(props);
    }

    @Bean
    public WsEventBuffer wsEventBuffer() {
        return new WsEventBuffer(props);
    }

    @Bean
    public WsPushService wsPushService(WsSessionManager sessions, WsEventBuffer buffer) {
        return new WsPushService(buffer, sessions, objectMapper);
    }

    @Bean
    public WsHandshakeInterceptor wsHandshakeInterceptor() {
        return new WsHandshakeInterceptor(jwtProvider, accessService, objectMapper);
    }

    @Bean
    public MedboxWebSocketHandler medboxWebSocketHandler(WsSessionManager sessions,
                                                         WsEventBuffer buffer,
                                                         WsPushService pushService) {
        return new MedboxWebSocketHandler(sessions, buffer, pushService, objectMapper);
    }

    @Bean
    public WsIdleSessionReaper wsIdleSessionReaper(WsSessionManager sessions) {
        return new WsIdleSessionReaper(sessions, props);
    }
}
