package com.medbox.server.mqtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medbox.server.domain.MqttMessage;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.MqttMessageMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 上行链路的集成测试：直接驱动 {@link MqttInboundGateway#onMessage} 打真实链路
 * （Paho 回调 → topic 解析 → msgId 去重 → 在线态 → 业务 handler），
 * 只把数据库（Mapper）换成替身 —— 因此 **CI 无 EMQX / 无 PostgreSQL 也能跑**。
 *
 * <p>这里验的是 07 清单里 {@code feat/backend-mqtt} 的验收点：
 * 「EMQX 发一条 {@code up/telemetry}，后端能收到；重复 {@code msgId} 只处理一次」。
 */
class MqttInboundIntegrationTest {

    private static final String TOPIC_TELEMETRY = "medbox/pk001/BOXA1001/up/telemetry";
    private static final String TELEMETRY_JSON = """
        { "msgId":"BOXA1001-0000000124", "ts":1759474487000,
          "sensorId":"BOXA1001-S-TH01", "temperature":24.6, "humidity":52.1 }
        """;

    private MqttMessageMapper messageMapper;
    private DeviceMapper deviceMapper;
    private PendingAckRegistry registry;
    private RecordingUpMessageHandler telemetryHandler;
    private MqttInboundGateway gateway;

    @BeforeEach
    void setUp() {
        messageMapper = mock(MqttMessageMapper.class);
        deviceMapper = mock(DeviceMapper.class);
        ObjectMapper objectMapper = new JsonMapper();
        MqttProperties props = new MqttProperties();
        MqttMessageDeduplicator deduplicator = new MqttMessageDeduplicator(messageMapper, props);
        DeviceStatusService deviceStatusService = new DeviceStatusService(deviceMapper);
        registry = new PendingAckRegistry(props, new LoggingDownlinkFailureListener());
        telemetryHandler = new RecordingUpMessageHandler("telemetry");
        List<UpMessageHandler> handlers = List.of(
            telemetryHandler,
            new AckUpMessageHandler(registry, objectMapper),
            new LoggingUpMessageHandler());
        gateway = new MqttInboundGateway(props, objectMapper, deduplicator, deviceStatusService, handlers);
        when(deviceMapper.update(any(), any())).thenReturn(1);
    }

    @Test
    @DisplayName("收到一条 up/telemetry：落到对应 handler，并刷新设备在线态")
    void telemetryReachesHandler() {
        when(messageMapper.insert(any(MqttMessage.class))).thenReturn(1);

        gateway.onMessage(TOPIC_TELEMETRY, TELEMETRY_JSON.getBytes(StandardCharsets.UTF_8));

        assertThat(telemetryHandler.received).hasSize(1);
        assertThat(telemetryHandler.received.get(0).deviceId()).isEqualTo("BOXA1001");
        assertThat(telemetryHandler.received.get(0).payloadJson()).contains("\"temperature\":24.6");
        verify(deviceMapper, times(1)).update(any(), any());
    }

    @Test
    @DisplayName("重复 msgId 只处理一次：第二次在去重层被拦下，handler 不再被调用")
    void duplicateMsgIdHandledOnlyOnce() {
        when(messageMapper.insert(any(MqttMessage.class)))
            .thenReturn(1)
            .thenThrow(new DuplicateKeyException("duplicate key"));

        gateway.onMessage(TOPIC_TELEMETRY, TELEMETRY_JSON.getBytes(StandardCharsets.UTF_8));
        gateway.onMessage(TOPIC_TELEMETRY, TELEMETRY_JSON.getBytes(StandardCharsets.UTF_8));

        assertThat(telemetryHandler.received).hasSize(1);
        verify(messageMapper, times(2)).insert(any(MqttMessage.class));
    }

    @Test
    @DisplayName("缺少 msgId 的上行直接丢弃：不写去重表、不进 handler")
    void missingMsgIdDropped() {
        gateway.onMessage(TOPIC_TELEMETRY, "{\"temperature\":24.6}".getBytes(StandardCharsets.UTF_8));
        gateway.onMessage(TOPIC_TELEMETRY, "{\"msgId\":\"  \"}".getBytes(StandardCharsets.UTF_8));

        assertThat(telemetryHandler.received).isEmpty();
        verify(messageMapper, never()).insert(any(MqttMessage.class));
    }

    @Test
    @DisplayName("非 JSON payload 只记日志不外抛：口号回调线程不能被脏消息打断")
    void malformedPayloadIsSwallowed() {
        gateway.onMessage(TOPIC_TELEMETRY, "not-a-json".getBytes(StandardCharsets.UTF_8));
        gateway.onMessage("unknown/topic", TELEMETRY_JSON.getBytes(StandardCharsets.UTF_8));
        gateway.onMessage(TOPIC_TELEMETRY, new byte[0]);

        assertThat(telemetryHandler.received).isEmpty();
    }

    @Test
    @DisplayName("遗嘱 status 不去重（它是最新态，不是事件），离线时更新 online_state")
    void lwtStatusIsNotDeduplicated() {
        gateway.onMessage("medbox/pk001/BOXA1001/status", "{\"online\":false}".getBytes(StandardCharsets.UTF_8));
        gateway.onMessage("medbox/pk001/BOXA1001/status", "{\"online\":false}".getBytes(StandardCharsets.UTF_8));

        verify(messageMapper, never()).insert(any(MqttMessage.class));
        verify(deviceMapper, times(2)).update(any(), any());
    }

    @Test
    @DisplayName("up/ack 按 ackFor 结算下行账本")
    void ackSettlesPendingDownlink() {
        when(messageMapper.insert(any(MqttMessage.class))).thenReturn(1);
        registry.register(new PendingDownlink("d-001", "BOXA1001", "medbox/pk001/BOXA1001/down/schedule", "{}",
            1, System.currentTimeMillis() + 60_000L));
        String ackJson = """
            { "msgId":"BOXA1001-0000000126", "ts":1759474520000,
              "ackFor":"d-001", "result":"OK", "reason":null }
            """;

        gateway.onMessage("medbox/pk001/BOXA1001/up/ack", ackJson.getBytes(StandardCharsets.UTF_8));

        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("没有专项 handler 的 topic 由兜底处理器接住（不说丢，也不报错）")
    void unknownSubPathGoesToFallback() {
        when(messageMapper.insert(any(MqttMessage.class))).thenReturn(1);

        gateway.onMessage("medbox/pk001/BOXA1001/up/inventory",
            "{\"msgId\":\"BOXA1001-0000000127\",\"ts\":1,\"medicineId\":\"m-205\",\"quantity\":12,\"delta\":-1}"
                .getBytes(StandardCharsets.UTF_8));

        // 兜底实现不记录，但要确认这条消息确实被消费（去重表里也只有一条）
        ArgumentCaptor<MqttMessage> captor = ArgumentCaptor.forClass(MqttMessage.class);
        verify(messageMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getTopic()).isEqualTo("medbox/pk001/BOXA1001/up/inventory");
    }

    /** 记录调用情况的测试 handler。 */
    private record Received(String deviceId, String payloadJson) {
    }

    private static class RecordingUpMessageHandler implements UpMessageHandler {

        private final String supportedSubPath;
        private final List<Received> received = new ArrayList<>();

        private RecordingUpMessageHandler(String supportedSubPath) {
            this.supportedSubPath = supportedSubPath;
        }

        @Override
        public String supports() {
            return supportedSubPath;
        }

        @Override
        public void handle(String deviceId, String topic, String payloadJson) {
            received.add(new Received(deviceId, payloadJson));
        }
    }
}
