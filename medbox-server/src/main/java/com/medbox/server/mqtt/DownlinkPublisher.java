package com.medbox.server.mqtt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.domain.Device;
import com.medbox.server.mapper.DeviceMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 下行发布入口：后端下发给设备的所有消息都经这里（计划同步 / 控制命令，见文档 03 第 2 章）。
 *
 * <p><b>信封字段由本类注入</b>：下行消息必须带 {@code msgId} 与 {@code needAck}
 * （见 03 第 3 章的 {@code down/schedule} 示例），但这两个是"通道层"字段，
 * 业务 caller（{@code feat/plan-sync} / {@code feat/device-command}）只管拼自己的业务体，
 * 由这里统一塞进 JSON —— 少一处"忘了填 msgId 导致收不到回执"的坑。
 *
 * <p>{@code needAck=true} 时会登记 {@link PendingAckRegistry}，
 * 超时由那里的调度线程重发（<b>重发时 msgId 不变</b>，设备侧据此去重）。
 */
public class DownlinkPublisher implements PendingAckRegistry.Sender {

    private static final Logger log = LoggerFactory.getLogger(DownlinkPublisher.class);

    /** 下行 topic 的子路径常量。 */
    public static final String SUB_TOPIC_SCHEDULE = "schedule";

    /** 控制命令。 */
    public static final String SUB_TOPIC_COMMAND = "command";

    private final MqttTransport transport;
    private final DeviceMapper deviceMapper;
    private final PendingAckRegistry registry;
    private final MqttProperties props;
    private final ObjectMapper objectMapper;

    public DownlinkPublisher(MqttTransport transport,
                             DeviceMapper deviceMapper,
                             PendingAckRegistry registry,
                             MqttProperties props,
                             ObjectMapper objectMapper) {
        this.transport = transport;
        this.deviceMapper = deviceMapper;
        this.registry = registry;
        this.props = props;
        this.objectMapper = objectMapper;
        // 账本的重发动作就是"再调一次 publishToTopic"，注册回调避免构造循环依赖
        registry.setSender(this);
    }

    /**
     * 下发一条消息到设备。
     *
     * @param deviceId 药箱主控业务 ID
     * @param subTopic {@code schedule} / {@code command}（见本类常量）
     * @param payload  业务体（会被序列化并与信封字段合并）
     * @param needAck  是否需要设备回执（需要则登记超时重发）
     * @return 本次下行的 msgId（用于回执关联与排障）
     */
    public String publish(String deviceId, String subTopic, Object payload, boolean needAck) {
        String productKey = resolveProductKey(deviceId);
        String topic = MqttTopics.downTopic(props.getTopicPrefix(), productKey, deviceId, subTopic);
        String msgId = newDownlinkMsgId();
        String payloadJson = envelope(payload, msgId, needAck);

        publishBytes(topic, payloadJson);
        if (needAck) {
            registry.register(new PendingDownlink(
                msgId, deviceId, topic, payloadJson, 1, System.currentTimeMillis() + props.getAckTimeoutMs()));
        }
        log.info("已下行 deviceId={} topic={} msgId={} needAck={}", deviceId, topic, msgId, needAck);
        return msgId;
    }

    /** 账本回调：重发一条下行（topic 与 payload 都不变，含原 msgId）。 */
    @Override
    public void resend(PendingDownlink entry) {
        publishBytes(entry.topic(), entry.payloadJson());
    }

    private void publishBytes(String topic, String payloadJson) {
        // 下行计划与命令一律 QoS1、retained=false（见文档 03 第 1 章）
        transport.publish(topic, payloadJson.getBytes(StandardCharsets.UTF_8), 1, false);
    }

    /**
     * 下行 topic 的第 2 层是 productKey，只有 {@code device} 表里才有 —— 查不到就无法拼 topic，
     * 宁可抛错也不猜一个 topics 出来（猜错等于命令发到空气里，比报错更难排障）。
     */
    private String resolveProductKey(String deviceId) {
        LambdaQueryWrapper<Device> query = new LambdaQueryWrapper<Device>()
            .eq(Device::getDeviceId, deviceId)
            .select(Device::getProductKey);
        Device device = deviceMapper.selectOne(query);
        if (device == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "设备未登记，无法下发：" + deviceId);
        }
        if (!StringUtils.hasText(device.getProductKey())) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "设备未登记 productKey，无法拼下行 topic：" + deviceId);
        }
        return device.getProductKey();
    }

    private String envelope(Object payload, String msgId, boolean needAck) {
        try {
            JsonNode node = objectMapper.valueToTree(payload);
            if (node instanceof ObjectNode objectNode) {
                objectNode.put("msgId", msgId);
                objectNode.put("needAck", needAck);
                return objectMapper.writeValueAsString(objectNode);
            }
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "下行消息体必须是 JSON 对象");
        } catch (JacksonException ex) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "下行消息序列化失败");
        }
    }

    private String newDownlinkMsgId() {
        return "d-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
