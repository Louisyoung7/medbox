package com.medbox.server.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.ws.payload.AlarmPayload;
import java.net.URI;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 真链路集成测试：**需要本机先起应用**（本测试自己会拉起随机端口的完整上下文），CI 默认跳过。
 *
 * <p>它等价于 07 清单的验收动作"用 wscat 连上后能收到后端主动推送的告警帧"，只是把 wscat 换成
 * 客户端 API：连上 → 经 {@code WsPushService} 推一帧 → 断言收到。
 *
 * <pre>
 * MEDBOX_WS_LOCAL_TEST=true ./gradlew test --tests '*WsLocalIntegrationTest'
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"medbox.ws.enabled=true", "medbox.ws.debug-endpoint-enabled=true"})
@EnabledIfEnvironmentVariable(named = "MEDBOX_WS_LOCAL_TEST", matches = "true")
class WsLocalIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private WsPushService pushService;

    @Test
    @DisplayName("连上 /medbox/ws 后能收到后端主动推送的 ALARM 帧")
    void clientReceivesPushedAlarm() throws Exception {
        String token = jwtProvider.issue("e-1001", "ELDER", TokenType.ACCESS);
        String url = "ws://localhost:" + port + "/medbox/ws?token=" + token + "&topics=elder:e-1001";
        LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = client.execute(new CapturingHandler(received),
                new WebSocketHttpHeaders(), URI.create(url)).get(10, TimeUnit.SECONDS);
        try {
            pushService.publish("elder:e-1001", WsFrameType.ALARM,
                    new AlarmPayload("a-local-1", "MISS", "WARN", "本地验收：漏服", "e-1001", List.of("m-205")));

            String frame = received.poll(10, TimeUnit.SECONDS);
            assertThat(frame).as("未收到推送帧（上下文起来了没？topic 对不对？）").isNotNull();
            assertThat(frame).contains("\"type\":\"ALARM\"").contains("a-local-1");
        } finally {
            session.close(CloseStatus.NORMAL);
        }
    }

    /** 只收集文本帧的客户端 handler。 */
    private static final class CapturingHandler extends TextWebSocketHandler {

        private final LinkedBlockingQueue<String> received;

        private CapturingHandler(LinkedBlockingQueue<String> received) {
            this.received = received;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            received.add(message.getPayload());
        }
    }
}
