package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.medbox.server.ai.InsightContextBuilder;
import com.medbox.server.ai.InsightType;
import com.medbox.server.ai.LlmChatGateway;
import com.medbox.server.ai.LlmCredentials;
import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.config.AiInsightProperties;
import com.medbox.server.domain.LlmConfig;
import com.medbox.server.dto.ai.InsightRange;
import com.medbox.server.dto.ai.InsightRequest;
import com.medbox.server.dto.ai.InsightResponse;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AI 洞察规则的单元测试（07 清单 {@code feat/ai-insight} 的 🧭 规则表 → 用例）。
 *
 * <p>纯 Mockito：AccessService / LlmConfigService / InsightContextBuilder / LlmChatGateway
 * 全部打桩 —— 不启容器、不连外网。SSE 流式路径由 {@code AiControllerIntegrationTest} 覆盖。
 */
class AiInsightServiceTest {

    private static final String ELDER_ID = "u-1001";
    private static final LlmCredentials CRED = new LlmCredentials("http://localhost:11434/v1", "sk-x", "m");

    private final AccessService accessService = mock(AccessService.class);
    private final LlmConfigService configService = mock(LlmConfigService.class);
    private final InsightContextBuilder contextBuilder = mock(InsightContextBuilder.class);
    private final LlmChatGateway gateway = mock(LlmChatGateway.class);
    private final AiInsightProperties props = new AiInsightProperties();

    private AiInsightService service;

    @BeforeEach
    void setUp() {
        service = new AiInsightService(accessService, configService, contextBuilder, gateway, props);
    }

    // —— 参数校验（规则表的 40001 行）—— //

    @Test
    @DisplayName("type 非法 → 40001；type 为空 → COMPREHENSIVE 兜底")
    void type非法_40001() {
        BizException ex = assertThrows(BizException.class,
                () -> service.insight(new InsightRequest(ELDER_ID, "MAGIC", null, null)));
        assertEquals(40001, ex.code());
    }

    @Test
    @DisplayName("from 晚于 to → 40001")
    void from晚于to_40001() {
        OffsetDateTime now = OffsetDateTime.now();
        InsightRange range = new InsightRange(now, now.minusDays(1));

        BizException ex = assertThrows(BizException.class,
                () -> service.insight(new InsightRequest(ELDER_ID, null, range, null)));
        assertEquals(40001, ex.code());
    }

    @Test
    @DisplayName("时间范围超 90 天 → 40001")
    void 超90天_40001() {
        OffsetDateTime now = OffsetDateTime.now();
        InsightRange range = new InsightRange(now.minusDays(91), now);

        BizException ex = assertThrows(BizException.class,
                () -> service.insight(new InsightRequest(ELDER_ID, null, range, null)));
        assertEquals(40001, ex.code());
    }

    // —— 校验通过后的业务分支 —— //

    @Test
    @DisplayName("未配置 / Key 无效 → 50310（AI 助手暂未开通）")
    void 未配置_50310() {
        givenPassage(dataContext());
        when(configService.credentialsOf(any()))
                .thenThrow(BizException.of(ErrorCode.AI_UNAVAILABLE, "AI 助手暂未开通，请联系监护人配置"));

        BizException ex = assertThrows(BizException.class,
                () -> service.insight(new InsightRequest(ELDER_ID, "ADHERENCE", null, null)));
        assertEquals(50310, ex.code());
    }

    @Test
    @DisplayName("区间内无数据 → code 0 + 模板文案，不调上游（不产生幻觉）")
    void 无数据_模板不调上游() {
        givenPassage(new InsightContextBuilder.InsightContext("", List.of(), true));

        InsightResponse resp = service.insight(new InsightRequest(ELDER_ID, null, null, null));

        assertEquals(AiInsightService.NO_DATA, resp.content());
        assertEquals(List.of(), resp.citations());
        assertEquals(AiInsightService.DISCLAIMER, resp.disclaimer());
        assertEquals("COMPREHENSIVE", resp.type());
        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("上游超时 / 异常 → 50310")
    void 上游异常_50310() {
        givenPassage(dataContext());
        when(gateway.complete(any(), any(), any())).thenThrow(new RuntimeException("read timed out"));

        BizException ex = assertThrows(BizException.class,
                () -> service.insight(new InsightRequest(ELDER_ID, "TREND", null, null)));
        assertEquals(50310, ex.code());
    }

    @Test
    @DisplayName("正常洞察：BANNED 词被替换、citations 透传、必带 disclaimer")
    void 正常洞察_过滤与声明() {
        givenPassage(dataContext());
        when(gateway.complete(any(), any(), any())).thenReturn("建议核查夜间服药，请勿自行加量，坚持用药。");

        InsightResponse resp = service.insight(new InsightRequest(ELDER_ID, "ADHERENCE", null, null));

        assertTrue(resp.content().contains("请咨询医生后再决定"));
        assertTrue(!resp.content().contains("自行加量"));
        assertEquals(List.of("med_record#1"), resp.citations());
        assertEquals(AiInsightService.DISCLAIMER, resp.disclaimer());
        assertEquals("ADHERENCE", resp.type());
    }

    @Test
    @DisplayName("周期任务内层 generateForElder：不做权限校验（定时任务无 AuthContext）")
    void 周期内层不查权限() {
        LlmConfig config = new LlmConfig();
        config.setElderId(ELDER_ID);
        when(configService.findRaw(ELDER_ID)).thenReturn(config);
        when(configService.credentialsOf(config)).thenReturn(CRED);
        when(contextBuilder.build(eq(ELDER_ID), eq(InsightType.COMPREHENSIVE), any(), any()))
                .thenReturn(dataContext());
        when(gateway.complete(any(), any(), any())).thenReturn("一切平稳。");

        InsightResponse resp = service.generateForElder(ELDER_ID, InsightType.COMPREHENSIVE,
                OffsetDateTime.now().minusDays(1), OffsetDateTime.now());

        assertEquals("一切平稳。", resp.content());
        verifyNoInteractions(accessService);
    }

    /** 打桩「鉴权 → 取 Key → 拼上下文」三步全通；上下文内容由参数给定。 */
    private void givenPassage(InsightContextBuilder.InsightContext ctx) {
        LlmConfig config = new LlmConfig();
        config.setElderId(ELDER_ID);
        when(configService.findRaw(ELDER_ID)).thenReturn(config);
        when(configService.credentialsOf(config)).thenReturn(CRED);
        when(contextBuilder.build(eq(ELDER_ID), any(), any(), any())).thenReturn(ctx);
    }

    private static InsightContextBuilder.InsightContext dataContext() {
        return new InsightContextBuilder.InsightContext("【依从性】\n- 服药记录 10 条",
                List.of("med_record#1"), false);
    }
}
