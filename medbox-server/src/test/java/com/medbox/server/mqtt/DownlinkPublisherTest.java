package com.medbox.server.mqtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.medbox.server.common.exception.BizException;
import com.medbox.server.domain.Device;
import com.medbox.server.mapper.DeviceMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 下行发布（CI 可跑，broker 与数据库都用替身）。
 *
 * <p>守三件事：① topic 必须拼对（第 2 层 productKey 来自 device 表，猜不得）；
 * ② 信封字段 {@code msgId} / {@code needAck} 必须被塞进消息体（不然设备回不了执）；
 * ③ 需要回执的下行要进账本，否则 5s×3 的重发链路就成了摆设。
 */
class DownlinkPublisherTest {

    private static final String DEVICE_ID = "BOXA1001";

    private FakeTransport transport;
    private DeviceMapper deviceMapper;
    private PendingAckRegistry registry;
    private DownlinkPublisher publisher;

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        deviceMapper = mock(DeviceMapper.class);
        ObjectMapper objectMapper = new JsonMapper();
        MqttProperties props = new MqttProperties();
        props.setAckTimeoutMs(5_000L);
        props.setMaxAttempts(3);
        registry = new PendingAckRegistry(props, new LoggingDownlinkFailureListener());
        publisher = new DownlinkPublisher(transport, deviceMapper, registry, props, objectMapper);
    }

    @Test
    @DisplayName("下发计划：topic 正确、信封字段注入、needAck 时进账本")
    void publishScheduleWithAck() {
        givenDevice("pk001");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("planId", "p-3301");
        payload.put("op", "UPSERT");
        String msgId = publisher.publish(DEVICE_ID, DownlinkPublisher.SUB_TOPIC_SCHEDULE, payload, true);

        assertThat(transport.publishedTopics)
            .containsExactly("medbox/pk001/BOXA1001/down/schedule");
        assertThat(transport.publishedQos).containsExactly(1);
        assertThat(transport.publishedRetained).containsExactly(false);

        String body = transport.publishedBodies.get(0);
        assertThat(body).contains("\"msgId\":\"" + msgId + "\"");
        assertThat(body).contains("\"needAck\":true");
        assertThat(body).contains("\"planId\":\"p-3301\"");
        assertThat(body).contains("\"op\":\"UPSERT\"");

        assertThat(registry.snapshot()).hasSize(1);
        assertThat(registry.snapshot().iterator().next().msgId()).isEqualTo(msgId);
    }

    @Test
    @DisplayName("不需要回执的下行不进账本")
    void publishWithoutAckSkipsLedger() {
        givenDevice("pk001");

        publisher.publish(DEVICE_ID, DownlinkPublisher.SUB_TOPIC_COMMAND, Map.of("cmd", "BEEP"), false);

        assertThat(transport.publishedTopics).hasSize(1);
        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("重发走同一 topic 与原 msgId")
    void resendUsesSameTopicAndMsgId() {
        givenDevice("pk001");
        String msgId = publisher.publish(DEVICE_ID, DownlinkPublisher.SUB_TOPIC_COMMAND, Map.of("cmd", "UNLOCK"), true);
        String firstBody = transport.publishedBodies.get(0);

        publisher.resend(registry.snapshot().iterator().next());

        assertThat(transport.publishedTopics).hasSize(2);
        assertThat(transport.publishedBodies.get(1)).isEqualTo(firstBody).contains("\"msgId\":\"" + msgId + "\"");
    }

    @Test
    @DisplayName("设备未登记：抛 40401 而不是猜一个 topic 发到空气里")
    void unknownDeviceIsRejected() {
        when(deviceMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> publisher.publish(DEVICE_ID, DownlinkPublisher.SUB_TOPIC_SCHEDULE, Map.of(), true))
            .isInstanceOf(BizException.class)
            .extracting(ex -> ((BizException) ex).code())
            .isEqualTo(40401);
        assertThat(transport.publishedTopics).isEmpty();
    }

    @Test
    @DisplayName("设备缺 productKey：抛 50000（表结构完整才能拼 topic）")
    void missingProductKeyIsRejected() {
        givenDevice(null);

        assertThatThrownBy(() -> publisher.publish(DEVICE_ID, DownlinkPublisher.SUB_TOPIC_SCHEDULE, Map.of(), true))
            .isInstanceOf(BizException.class)
            .extracting(ex -> ((BizException) ex).code())
            .isEqualTo(50000);
    }

    private void givenDevice(String productKey) {
        Device device = new Device();
        device.setDeviceId(DEVICE_ID);
        device.setProductKey(productKey);
        when(deviceMapper.selectOne(any())).thenReturn(device);
    }

    /** 记录下行的假传输层：不做真实 IO。 */
    private static class FakeTransport implements MqttTransport {

        private final List<String> publishedTopics = new ArrayList<>();
        private final List<String> publishedBodies = new ArrayList<>();
        private final List<Integer> publishedQos = new ArrayList<>();
        private final List<Boolean> publishedRetained = new ArrayList<>();

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void publish(String topic, byte[] payload, int qos, boolean retained) {
            publishedTopics.add(topic);
            publishedBodies.add(new String(payload, StandardCharsets.UTF_8));
            publishedQos.add(qos);
            publishedRetained.add(retained);
        }
    }
}
