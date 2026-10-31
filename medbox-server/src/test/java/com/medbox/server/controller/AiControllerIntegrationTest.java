package com.medbox.server.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.ai.ApiKeyCipher;
import com.medbox.server.ai.InsightContextBuilder;
import com.medbox.server.ai.LlmChatGateway;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.LlmConfig;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.LlmConfigMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.support.TableInfoWarmup;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

/**
 * AI 配置与洞察的集成测试（{@code feat/ai-insight}，见文档 05 第 1 / 2 章）。
 *
 * <p>真实链路：MockMvc → {@code AiController} → 真实 {@code LlmConfigService} / {@code AiInsightService}
 * / {@code ApiKeyCipher} / {@code AccessService} → 打桩的 Mapper 与 {@code LlmChatGateway}
 * （不跑真实 SQL、不连外网；内嵌 H2 顶替 DataSource 让事务能开）。
 *
 * <p>覆盖：Key 加密落库与脱敏回显、权限矩阵（老人配 Key 40301）、50310 未配置降级、
 * 无数据模板不调上游、type/range 校验 40001、SSE 三帧（delta → done）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiControllerIntegrationTest {

    @TestConfiguration
    static class H2DataSourceConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-ai-test")
                    .build();
        }
    }

    private static final String ELDER_ID = "u-1001";
    private static final String GUARDIAN_ID = "u-2001";
    private static final String PLAIN_KEY = "sk-test-1234567890abcdef";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtProvider jwtProvider;
    @Autowired
    private ApiKeyCipher cipher;

    @MockitoBean
    private LlmConfigMapper llmConfigMapper;
    @MockitoBean
    private UserMapper userMapper;
    @MockitoBean
    private GuardianRelationMapper guardianRelationMapper;
    @MockitoBean
    private InsightContextBuilder contextBuilder;
    @MockitoBean
    private LlmChatGateway gateway;

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(LlmConfig.class, User.class, GuardianRelation.class);
        // AccessService.isElder：任何目标都按"是老人"处理
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(elder(ELDER_ID));
        givenGuardianGuards();
    }

    @AfterEach
    void tearDown() {
        com.medbox.server.common.security.AuthContext.clear();
    }

    // ————————————————— 配置管理（05 §2.1）—————————————————

    @Test
    @DisplayName("PUT /config：FAMILY 家属 → 200，落库的 apiKeyEnc 已加密（v1: 前缀、不含明文、可解密还原）")
    void 保存配置_Key加密落库() throws Exception {
        when(llmConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        mockMvc.perform(put("/api/v1/ai/config").param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"openai-compatible\",\"apiKey\":\"" + PLAIN_KEY
                                + "\",\"model\":\"qwen2.5\",\"baseUrl\":\"http://localhost:11434/v1\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.apiKeyMask").value("sk****cdef"));

        ArgumentCaptor<LlmConfig> captor = ArgumentCaptor.forClass(LlmConfig.class);
        verify(llmConfigMapper).insert(captor.capture());
        String enc = captor.getValue().getApiKeyEnc();
        org.junit.jupiter.api.Assertions.assertTrue(enc.startsWith("v1:"));
        org.junit.jupiter.api.Assertions.assertFalse(enc.contains(PLAIN_KEY));
        org.junit.jupiter.api.Assertions.assertEquals(PLAIN_KEY, cipher.decrypt(enc));
    }

    @Test
    @DisplayName("PUT /config：老人本人 → 40301（CONFIG_LLM_KEY 仅 FAMILY）")
    void 老人配Key_40301() throws Exception {
        mockMvc.perform(put("/api/v1/ai/config").param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + PLAIN_KEY
                                + "\",\"model\":\"qwen2.5\",\"baseUrl\":\"http://localhost:11434/v1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("GET /config：回显脱敏 Key（sk****abcd 形态，不含完整明文）")
    void 查询配置_脱敏回显() throws Exception {
        when(llmConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(savedConfig());

        mockMvc.perform(get("/api/v1/ai/config").param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKeyMask").value("sk****cdef"))
                .andExpect(jsonPath("$.data.apiKeyMask").value(not(containsString(PLAIN_KEY))))
                .andExpect(jsonPath("$.data.model").value("qwen2.5"));
    }

    // ————————————————— 同步洞察（05 §2.2 / §2.4）—————————————————

    @Test
    @DisplayName("POST /insight：未配置大模型 → HTTP 503 / code 50310（AI 助手暂未开通）")
    void 洞察未配置_50310() throws Exception {
        when(llmConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        mockMvc.perform(post("/api/v1/ai/insight")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"elderId\":\"" + ELDER_ID + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(50310));
    }

    @Test
    @DisplayName("POST /insight：type 非法 → 40001")
    void 洞察type非法_40001() throws Exception {
        mockMvc.perform(post("/api/v1/ai/insight")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"elderId\":\"" + ELDER_ID + "\",\"type\":\"MAGIC\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("POST /insight：时间范围超 90 天 → 40001")
    void 洞察超90天_40001() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        String body = "{\"elderId\":\"" + ELDER_ID + "\",\"range\":{\"from\":\""
                + now.minusDays(91) + "\",\"to\":\"" + now + "\"}}";

        mockMvc.perform(post("/api/v1/ai/insight")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("POST /insight：区间内无数据 → 200 + 模板文案，不调上游")
    void 洞察无数据_模板文案() throws Exception {
        when(llmConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(savedConfig());
        when(contextBuilder.build(any(), any(), any(), any()))
                .thenReturn(new InsightContextBuilder.InsightContext("", List.of(), true));

        mockMvc.perform(post("/api/v1/ai/insight")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"elderId\":\"" + ELDER_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.content").value(containsString("暂无可用的结构化数据")))
                .andExpect(jsonPath("$.data.disclaimer").exists())
                .andExpect(jsonPath("$.data.type").value("COMPREHENSIVE"));

        org.mockito.Mockito.verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("POST /insight：正常 → 200，内容来自上游、必带 disclaimer 与 citations")
    void 洞察正常_200() throws Exception {
        when(llmConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(savedConfig());
        when(contextBuilder.build(any(), any(), any(), any()))
                .thenReturn(new InsightContextBuilder.InsightContext("【依从性】\n- 服药记录 10 条",
                        List.of("med_record#mr-1"), false));
        when(gateway.complete(any(), any(), any())).thenReturn("夜间服药可能偏晚，建议核查。");

        mockMvc.perform(post("/api/v1/ai/insight")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"elderId\":\"" + ELDER_ID + "\",\"type\":\"ADHERENCE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("夜间服药可能偏晚，建议核查。"))
                .andExpect(jsonPath("$.data.citations[0]").value("med_record#mr-1"))
                .andExpect(jsonPath("$.data.disclaimer").value(containsString("仅供参考")));
    }

    // ————————————————— SSE 流式（05 §2.2）—————————————————

    @Test
    @DisplayName("POST /insight/stream：SSE 逐帧输出，delta 分片后接 done（含 disclaimer）")
    void 流式洞察_SSE三帧() throws Exception {
        when(llmConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(savedConfig());
        when(contextBuilder.build(any(), any(), any(), any()))
                .thenReturn(new InsightContextBuilder.InsightContext("【依从性】数据", List.of(), false));
        when(gateway.stream(any(), any(), any())).thenReturn(Flux.just("你好", "世界"));

        MvcResult pending = mockMvc.perform(post("/api/v1/ai/insight/stream")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"elderId\":\"" + ELDER_ID + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult done = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andReturn();

        // text/event-stream 无 charset，MockMvc 默认按 ISO-8859-1 解码 → 必须显式 UTF-8，否则中文全乱码
        String body = done.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("你好"), body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("世界"), body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"type\":\"delta\""), body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"type\":\"done\""), body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("仅供参考"), body);
    }

    // ————————————————— 周期洞察开关（05 §2.3）—————————————————

    @Test
    @DisplayName("GET /insight/schedule：未配置 → 200，enabled=false / DAILY 兜底")
    void 查询周期_未配置兜底() throws Exception {
        when(llmConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        mockMvc.perform(get("/api/v1/ai/insight/schedule").param("elderId", ELDER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.cadence").value("DAILY"));
    }

    // ————————————————— 打桩与工厂 —————————————————

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    /** 配成「监护该老人的监护人」（ACTIVE；统一 GUARDIAN 单角色后无 relation role 可设）。 */
    private void givenGuardianGuards() {
        GuardianRelation r = new GuardianRelation();
        r.setRelationId("r-1001");
        r.setElderId(ELDER_ID);
        r.setGuardianId(GUARDIAN_ID);
        r.setStatus(RelationStatus.ACTIVE);
        when(guardianRelationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(r));
        when(guardianRelationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(r);
    }

    /** 一份「已配置且启用」的 llm_config（apiKeyEnc 用真实 cipher 加密，走通 credentialsOf 的解密路径）。 */
    private LlmConfig savedConfig() {
        LlmConfig c = new LlmConfig();
        c.setConfigId("cfg-1001");
        c.setElderId(ELDER_ID);
        c.setProvider("openai-compatible");
        c.setApiKeyEnc(cipher.encrypt(PLAIN_KEY));
        c.setModel("qwen2.5");
        c.setBaseUrl("http://localhost:11434/v1");
        c.setEnabled(true);
        c.setInsightEnabled(false);
        c.setInsightCadence("DAILY");
        c.setUpdatedAt(OffsetDateTime.now());
        return c;
    }

    private static User elder(String userId) {
        User u = new User();
        u.setUserId(userId);
        u.setRole(UserRole.ELDER);
        u.setName("张三");
        return u;
    }
}
