package com.medbox.server.devtools;

import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.config.DeviceProperties;
import com.medbox.server.mqtt.MqttTransport;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 模拟设备上报：复用 {@link MqttTransport} 把报文发到上行 topic，等价于服务端版 {@code mosquitto_pub}。
 *
 * <p>报文会流经真实入站网关 → 各 {@code UpMessageHandler} → 落库 → WS 推送整条链路，便于没有真设备时也
 * 能端到端验证（见文档 03 第 5 章）。topic 形态 {@code medbox/{productKey}/{deviceId}/up/{subTopic}}，
 * 与 03 第 2 章一致；{@code productKey} 缺省取 {@code medbox.device.default-product-key}。
 *
 * <p>MQTT 未启用（{@code medbox.mqtt.enabled=false}，此时 {@link MqttTransport} Bean 不存在）或未连接
 * broker 时，返回清晰的 50000 提示，不抛底层异常。
 */
@Service
@ConditionalOnProperty(prefix = "medbox.devtools", name = "enabled", havingValue = "true")
public class DevtoolsMqttSimService {

    private final ObjectProvider<MqttTransport> transportProvider;
    private final DeviceProperties deviceProperties;

    public DevtoolsMqttSimService(ObjectProvider<MqttTransport> transportProvider, DeviceProperties deviceProperties) {
        this.transportProvider = transportProvider;
        this.deviceProperties = deviceProperties;
    }

    /**
     * 发布一条模拟的上行消息。
     *
     * @param deviceId   药箱主控设备 ID（topic 第 3 层）
     * @param subTopic   上行子路径，如 {@code event/dispense}
     * @param productKey 产品 key，为空取默认值
     * @param payload    透传的 JSON 原文
     */
    public void publish(String deviceId, String subTopic, String productKey, String payload) {
        BizAssert.notBlank(deviceId, "deviceId 不能为空");
        BizAssert.notBlank(subTopic, "subTopic 不能为空");

        MqttTransport transport = transportProvider.getIfAvailable();
        BizAssert.isTrue(transport != null, ErrorCode.INTERNAL_ERROR,
                "MQTT 未启用（medbox.mqtt.enabled=false），无法模拟设备上报");
        BizAssert.isTrue(transport.isConnected(), ErrorCode.INTERNAL_ERROR,
                "MQTT 未连接 broker，无法模拟设备上报，请先启动 EMQX");

        String pk = (productKey == null || productKey.isBlank())
                ? deviceProperties.getDefaultProductKey() : productKey;
        String topic = String.join("/", "medbox", pk, deviceId, "up", subTopic);
        byte[] body = payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8);
        transport.publish(topic, body, 1, false);
    }
}
