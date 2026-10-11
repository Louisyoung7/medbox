package com.medbox.server.mqtt;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 真链路集成测试：**需要本机 EMQX**（{@code docker run --name emqx -p 1883:1883 -p 18083:18083 emqx/emqx}），
 * CI 因缺 broker 靠 {@code MEDBOX_MQTT_LOCAL_TEST} 自动跳过 —— 与 AI 侧的
 * {@code AiLocalIntegrationTest} 同一套路。
 *
 * <p>跑通它等于把 07 清单的验收点整条走了一遍：
 * 外部 MQTT 客户端发 {@code up/telemetry} → EMQX → 后端订阅 → 网关 → 业务 handler。
 *
 * <pre>
 * MEDBOX_MQTT_LOCAL_TEST=true ./gradlew test --tests '*MqttLocalIntegrationTest'
 * </pre>
 */
@SpringBootTest(properties = "medbox.mqtt.enabled=true")
@EnabledIfEnvironmentVariable(named = "MEDBOX_MQTT_LOCAL_TEST", matches = "true")
class MqttLocalIntegrationTest {

    @Autowired
    private MqttConnectionManager connectionManager;

    @Autowired
    private CapturingUpMessageHandler capturingHandler;

    @Autowired
    private MqttProperties props;

    @Test
    @DisplayName("外部客户端发 up/telemetry，后端订阅链路应收到")
    void externalPublishReachesGateway() throws Exception {
        waitUntilConnected();

        String clientId = "local-it-" + System.currentTimeMillis();
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        MqttClient client = new MqttClient(props.getBroker(), clientId, new MemoryPersistence());
        try {
            client.connect(options);
            // msgId 每次都不同：去重记录要保留 7 天，连跑两次同一个 msgId 会被当成重发丢掉
            String uplinkMsgId = "BOXLOCAL1-" + String.format("%010d", System.currentTimeMillis() % 1_000_000_000L);
            client.publish("medbox/pk-local/BOXLOCAL1/up/telemetry",
                ("{\"msgId\":\"" + uplinkMsgId + "\",\"ts\":" + System.currentTimeMillis()
                    + ",\"sensorId\":\"BOXLOCAL1-S-TH01\",\"temperature\":24.6}").getBytes(StandardCharsets.UTF_8),
                1, false);
        } finally {
            // close() 要求先 disconnect()，直接 close 会抛 MqttException(32100) Client is connected
            client.disconnect(2_000);
            client.close();
        }

        boolean received = capturingHandler.latch.await(10, TimeUnit.SECONDS);
        assertThat(received).as("后端未在 10s 内收到 up/telemetry（EMQX 起了吗？topic 前缀对吗？）").isTrue();
        assertThat(capturingHandler.lastDeviceId).isEqualTo("BOXLOCAL1");
    }

    private void waitUntilConnected() throws InterruptedException {
        for (int i = 0; i < 50 && !connectionManager.isConnected(); i++) {
            Thread.sleep(200);
        }
        assertThat(connectionManager.isConnected())
            .as("后端未连上 broker=%s（本机 EMQX 起了吗？）", props.getBroker())
            .isTrue();
    }

    /** 替身 handler：注册成 Bean 后会被网关按 supports() 自动匹配（这也是扩展点用法的示范）。 */
    @TestConfiguration
    static class LocalTestHandlers {

        @Bean
        public CapturingUpMessageHandler capturingUpMessageHandler() {
            return new CapturingUpMessageHandler();
        }
    }

    /** 测试期间累计收到的上行消息。 */
    static class CapturingUpMessageHandler implements UpMessageHandler {

        private final CountDownLatch latch = new CountDownLatch(1);

        private volatile String lastDeviceId;

        @Override
        public String supports() {
            return "telemetry";
        }

        @Override
        public void handle(String deviceId, String topic, String payloadJson) {
            lastDeviceId = deviceId;
            latch.countDown();
        }
    }
}
