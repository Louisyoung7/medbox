package com.medbox.server.controller;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.ai.ApiKeyCipher;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.mqtt.DownlinkPublisher;
import com.medbox.server.mqtt.PendingAckRegistry;
import com.medbox.server.support.TableInfoWarmup;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 限流端到端验证（feat/ai-limit）：真实链路 MockMvc → 拦截器 → Controller。
 *
 * <p>用 {@code @DynamicPropertySource} 把三个维度的阈值压到 3 次/60s，分别打满后断言第 4 次返回
 * {@code 42901} + HTTP 429，前 3 次不应是 429（无论业务结果是 40102 / 40301 / 40901）。
 *
 * <p>不依赖本地 Postgres：{@code @TestConfiguration} 用嵌入式 H2 顶替 DataSource，Mapper 全部打桩。
 * mqtt/ws 关掉以缩小启动面；命令下发需要的 mqtt 账本用 {@code @MockitoBean} 兜底。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RateLimitWebIntegrationTest {

    @TestConfiguration
    static class H2DataSourceConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-ratelimit-test")
                    .build();
        }
    }

    private static final String ELDER = "elder-rl";
    private static final String GUARDIAN = "guardian-rl";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserMapper userMapper;
    @MockitoBean
    private GuardianRelationMapper guardianRelationMapper;
    @MockitoBean
    private DeviceMapper deviceMapper;
    @MockitoBean
    private DownlinkPublisher downlinkPublisher;
    @MockitoBean
    private PendingAckRegistry pendingAckRegistry;
    // ApiKeyCipher 是无条件 @Component，本沙箱缺 MEDBOX_LLM_AES_KEY 会构造失败；本测试不校验加密，打桩即可让上下文加载。
    @MockitoBean
    private ApiKeyCipher apiKeyCipher;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("medbox.ratelimit.enabled", () -> "true");
        registry.add("medbox.ratelimit.login.limit", () -> "3");
        registry.add("medbox.ratelimit.login.window-seconds", () -> "60");
        registry.add("medbox.ratelimit.ai-insight.limit", () -> "3");
        registry.add("medbox.ratelimit.ai-insight.window-seconds", () -> "60");
        registry.add("medbox.ratelimit.device-command.limit", () -> "3");
        registry.add("medbox.ratelimit.device-command.window-seconds", () -> "60");
        // 本机测试环境可能缺 MEDBOX_LLM_AES_KEY，直接钉一个合法 ≥32 字符密钥，避免 ApiKeyCipher 构造失败
        // （与限流功能无关，仅为让全上下文测试可加载）。
        registry.add("medbox.ai.insight.aes-key", () -> "medbox-local-dev-llm-key-change-me-32b");
        // 同理解 JWT 密钥（CI 通过 MEDBOX_JWT_SECRET 提供，本地缺省时这里钉一个 ≥32 字节值）。
        registry.add("medbox.jwt.secret", () -> "medbox-local-dev-jwt-secret-32bytes!!");
        // 本沙箱无 OpenAI 凭证，钉一个 dummy 让 Spring AI 自动配置能构造（测试本身不真正调用大模型）。
        registry.add("spring.ai.openai.api-key", () -> "dummy-key-for-test");
    }

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(User.class, GuardianRelation.class);
        // 监护关系不存在 → AccessService.require 返回 40301（越权，不泄漏资源是否存在）
        when(guardianRelationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(elder(ELDER));
    }

    @AfterEach
    void tearDown() {
        com.medbox.server.common.security.AuthContext.clear();
    }

    @Test
    @DisplayName("登录限流：第 4 次 POST /auth/login 返回 42901")
    void 登录限流_第4次429() throws Exception {
        String ip = "10.0.0.1";
        for (int i = 0; i < 3; i++) {
            MvcResult r = mockMvc.perform(login(ip)).andReturn();
            assertNotEquals(429, r.getResponse().getStatus(), "前 3 次不应被限流");
        }
        mockMvc.perform(login(ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42901));
    }

    @Test
    @DisplayName("AI 洞察限流：第 4 次 POST /ai/insight 返回 42901")
    void 洞察限流_第4次429() throws Exception {
        String body = "{\"elderId\":\"" + ELDER + "\"}";
        for (int i = 0; i < 3; i++) {
            MvcResult r = mockMvc.perform(aiInsight(body)).andReturn();
            assertNotEquals(429, r.getResponse().getStatus(), "前 3 次不应被限流");
        }
        mockMvc.perform(aiInsight(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42901));
    }

    @Test
    @DisplayName("命令下发限流：第 4 次 POST /devices/{id}/commands 返回 42901")
    void 命令限流_第4次429() throws Exception {
        String body = "{\"cmd\":\"BUZZ\"}";
        for (int i = 0; i < 3; i++) {
            MvcResult r = mockMvc.perform(deviceCommand(body)).andReturn();
            assertNotEquals(429, r.getResponse().getStatus(), "前 3 次不应被限流");
        }
        mockMvc.perform(deviceCommand(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42901));
    }

    private org.springframework.test.web.servlet.RequestBuilder login(String remoteAddr) {
        return post("/api/v1/auth/login")
                .with(req -> {
                    req.setRemoteAddr(remoteAddr);
                    return req;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"someone\",\"password\":\"123456\"}");
    }

    private org.springframework.test.web.servlet.RequestBuilder aiInsight(String body) {
        return post("/api/v1/ai/insight")
                .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN, "GUARDIAN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private org.springframework.test.web.servlet.RequestBuilder deviceCommand(String body) {
        return post("/api/v1/devices/DEV-RL-01/commands")
                .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN, "GUARDIAN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    private User elder(String id) {
        User u = new User();
        u.setUserId(id);
        u.setRole(UserRole.ELDER);
        return u;
    }
}
