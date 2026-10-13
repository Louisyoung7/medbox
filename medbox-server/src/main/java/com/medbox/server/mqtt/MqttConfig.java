package com.medbox.server.mqtt;

import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.MqttMessageMapper;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * MQTT 各组件的装配（见文档 03 全文）。
 *
 * <p><b>整包条件装配</b>：{@code medbox.mqtt.enabled=false} 时这些 Bean 一个都不建 ——
 * 本机没起 EMQX、CI 无任何中间件时，应用还要能起来做 REST 与跑测试
 * （测试期由 {@code build.gradle.kts} 注入 {@code MEDBOX_MQTT_ENABLED=false}，与 Flyway 同理）。
 *
 * <p><b>Bean 依赖方向（刻意排成不成环的一条链）</b>：
 * <pre>
 * PendingAckRegistry → DownlinkPublisher → MqttTransport(MqttConnectionManager) → MqttInboundGateway → PendingAckRegistry
 * </pre>
 * 发布器要把"重发回调"注册进账本（构造依赖反方向），因此账务型组件先建、并由发布器
 * 在构造时 {@code setSender}，而不是把发布器注入账本。
 */
@Configuration
@EnableConfigurationProperties(MqttProperties.class)
@ConditionalOnProperty(prefix = "medbox.mqtt", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MqttConfig {

    private static final Logger log = LoggerFactory.getLogger(MqttConfig.class);

    @Bean
    public MqttMessageDeduplicator mqttMessageDeduplicator(MqttMessageMapper mqttMessageMapper, MqttProperties props) {
        return new MqttMessageDeduplicator(mqttMessageMapper, props);
    }

    /**
     * 状态服务。监听者用 {@code List} 注入（与下面 {@code mqttInboundGateway} 收
     * {@code List<UpMessageHandler>} 同一套做法）：{@code feat/device-core} 的
     * {@code DeviceStatusEventBridge} 会自动进这个列表；没有监听者时是空列表，零副作用。
     */
    @Bean
    public DeviceStatusService deviceStatusService(DeviceMapper deviceMapper,
                                                   List<DeviceStatusChangeListener> listeners) {
        return new DeviceStatusService(deviceMapper, Clock.systemUTC(), listeners);
    }

    @Bean
    public DownlinkFailureListener downlinkFailureListener() {
        return new LoggingDownlinkFailureListener();
    }

    @Bean
    public PendingAckRegistry pendingAckRegistry(MqttProperties props, DownlinkFailureListener failureListener) {
        return new PendingAckRegistry(props, failureListener);
    }

    @Bean
    public DownlinkPublisher downlinkPublisher(MqttTransport transport,
                                               DeviceMapper deviceMapper,
                                               PendingAckRegistry registry,
                                               MqttProperties props,
                                               ObjectMapper objectMapper) {
        return new DownlinkPublisher(transport, deviceMapper, registry, props, objectMapper);
    }

    /** 兜底处理器：没有专项分支接管时，至少让消息在日志里可见（<code>up/telemetry</code> 验收就靠它）。 */
    @Bean
    public UpMessageHandler loggingUpMessageHandler() {
        return new LoggingUpMessageHandler();
    }

    /** 下行回执：属通道层，本分支落地（不是后续分支的事）。 */
    @Bean
    public UpMessageHandler ackUpMessageHandler(PendingAckRegistry registry, ObjectMapper objectMapper) {
        return new AckUpMessageHandler(registry, objectMapper);
    }

    @Bean
    public MqttInboundGateway mqttInboundGateway(MqttProperties props,
                                                 ObjectMapper objectMapper,
                                                 MqttMessageDeduplicator deduplicator,
                                                 DeviceStatusService deviceStatusService,
                                                 List<UpMessageHandler> handlers) {
        log.info("MQTT 已启用 broker={} clientId={}", props.getBroker(), props.getClientId());
        return new MqttInboundGateway(props, objectMapper, deduplicator, deviceStatusService, handlers);
    }

    /**
     * 真正连 broker 的客户端。返回具体类型而不是接口：<b>{@code org.springframework.context.SmartLifecycle}
     * 只有在 Bean 实例真的是 {@code SmartLifecycle} 时才被生命周期处理器拾取</b>，
     * 声明成返回类型不当也不会报错，连接就永远不会被 start()。
     */
    @Bean
    public MqttConnectionManager mqttConnectionManager(MqttProperties props, MqttInboundGateway gateway) {
        return new MqttConnectionManager(props, gateway);
    }
}
