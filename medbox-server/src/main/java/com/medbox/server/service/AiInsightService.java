package com.medbox.server.service;

import com.medbox.server.ai.InsightContextBuilder;
import com.medbox.server.ai.InsightType;
import com.medbox.server.ai.LlmChatGateway;
import com.medbox.server.ai.LlmCredentials;
import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.common.security.Permission;
import com.medbox.server.config.AiInsightProperties;
import com.medbox.server.domain.LlmConfig;
import com.medbox.server.dto.ai.InsightRange;
import com.medbox.server.dto.ai.InsightRequest;
import com.medbox.server.dto.ai.InsightResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** AI 洞察：同步 / SSE 流式 / 周期任务内层（见文档 05 第 2 章）。洞察不落库、响应必带 disclaimer。 */
@Service
public class AiInsightService {

    private static final Logger log = LoggerFactory.getLogger(AiInsightService.class);

    static final String DISCLAIMER = "本分析仅供参考，用药与设备调整请由监护人 / 医生决策。";
    static final String NO_DATA = "所选时间范围内暂无可用的结构化数据，无法生成有效分析。建议先补充服药记录 / 环境数据后再试。";
    static final String NO_MEDICATION_DATA = "该老人暂无药品档案或药品说明，无法进行用药安全分析。请先补充药品说明后再试。";
    private static final String SYSTEM_PROMPT = """
        你是家用药品箱的用药数据分析助手。只根据用户提供的结构化统计做分析、预测、建议。
        硬性要求：
        1) 不给出诊断、处方、明确用药结论；不编造数据里没有的数字。
        2) 用"可能 / 倾向 / 建议核查"这类措辞，结论要能回到给定数据。
        3) 中文输出，分点、简短，不超过 400 字。
        """;
    private static final List<String> BANNED = List.of(
            "确诊", "停药", "换药", "加大剂量", "减少剂量", "自行加量",
            "禁止同服", "不得同服", "严禁同服");
    private static final int MAX_RANGE_DAYS = 90;

    private final AccessService accessService;
    private final LlmConfigService configService;
    private final InsightContextBuilder contextBuilder;
    private final LlmChatGateway gateway;
    private final AiInsightProperties props;
    private final ExecutorService streamExecutor;

    public AiInsightService(AccessService accessService, LlmConfigService configService,
                            InsightContextBuilder contextBuilder, LlmChatGateway gateway,
                            AiInsightProperties props) {
        this.accessService = accessService;
        this.configService = configService;
        this.contextBuilder = contextBuilder;
        this.gateway = gateway;
        this.props = props;
        this.streamExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /** 同步洞察。 */
    public InsightResponse insight(InsightRequest request) {
        Premise p = prepare(request);
        if (p.context().empty()) {
            return new InsightResponse(p.elderId(), p.type().name(), p.range(),
                    noDataMessage(p.type()), List.of(), DISCLAIMER);
        }
        String content;
        try {
            content = gateway.complete(p.credentials(), SYSTEM_PROMPT, prompt(p));
        } catch (RuntimeException ex) {
            log.warn("AI 洞察调用失败 elderId={} type={}：{}", p.elderId(), p.type(), ex.getMessage());
            throw BizException.of(ErrorCode.AI_UNAVAILABLE, "AI 服务暂不可用，请稍后再试");
        }
        return new InsightResponse(p.elderId(), p.type().name(), p.range(),
                sanitize(content), p.context().citations(), DISCLAIMER);
    }

    /** SSE 流式洞察：鉴权 / 取 Key / 拼上下文都在请求线程里做完，再交给虚拟线程逐字发。 */
    public SseEmitter stream(InsightRequest request) {
        Premise p = prepare(request);          // 抛 BizException → 全局异常处理返回 50310/40001
        SseEmitter emitter = new SseEmitter(props.getStreamTimeoutMs());
        streamExecutor.execute(() -> {
            try {
                if (p.context().empty()) {
                    send(emitter, Map.of("type", "delta", "content", noDataMessage(p.type())));
                } else {
                    // 不用 forEach：send 抛 IOException 是受检异常，lambda 里捕获不了；for 循环由外层 catch 兜底
                    for (String delta : gateway.stream(p.credentials(), SYSTEM_PROMPT, prompt(p)).toIterable()) {
                        send(emitter, Map.of("type", "delta", "content", delta));
                    }
                }
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("type", "done");
                done.put("citations", p.context().citations());
                done.put("disclaimer", DISCLAIMER);
                send(emitter, done);
                emitter.complete();
            } catch (Exception ex) {
                log.warn("AI 洞察流式失败 elderId={} type={}：{}", p.elderId(), p.type(), ex.getMessage());
                try {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("type", "error");
                    err.put("code", ErrorCode.AI_UNAVAILABLE.code());
                    err.put("message", "AI 服务暂不可用，请稍后再试");
                    send(emitter, err);
                } catch (Exception ignored) {
                    // 连接已断，无需再发
                }
                emitter.complete();
            }
        });
        return emitter;
    }

    /** 周期任务用：无 AuthContext，不做权限校验（调用方是系统，见 AiInsightScheduler）。 */
    public InsightResponse generateForElder(String elderId, InsightType type,
                                            OffsetDateTime from, OffsetDateTime to) {
        LlmConfig config = configService.findRaw(elderId);
        LlmCredentials cred = configService.credentialsOf(config);
        InsightContextBuilder.InsightContext ctx = contextBuilder.build(elderId, type, from, to);
        InsightRange range = new InsightRange(from, to);
        if (ctx.empty()) {
            return new InsightResponse(elderId, type.name(), range,
                    noDataMessage(type), List.of(), DISCLAIMER);
        }
        try {
            String content = gateway.complete(cred, SYSTEM_PROMPT,
                    promptText(type, range, ctx.text()));
            return new InsightResponse(elderId, type.name(), range, sanitize(content),
                    ctx.citations(), DISCLAIMER);
        } catch (RuntimeException ex) {
            log.warn("周期洞察调用失败 elderId={}：{}", elderId, ex.getMessage());
            throw BizException.of(ErrorCode.AI_UNAVAILABLE, "AI 服务暂不可用，请稍后再试");
        }
    }

    private Premise prepare(InsightRequest request) {
        BizAssert.notBlank(request.elderId(), "elderId 不能为空");
        InsightType type = InsightType.parse(request.type());
        BizAssert.badRequestIf(type == null, "type 仅支持 ADHERENCE / ENV_CORRELATION / TREND / COMPREHENSIVE");
        InsightRange range = request.range() == null ? null : request.range();
        OffsetDateTime to = range == null || range.to() == null ? OffsetDateTime.now() : range.to();
        OffsetDateTime from = range == null || range.from() == null ? to.minusDays(14) : range.from();
        BizAssert.badRequestIf(from.isAfter(to), "from 必须早于 to");
        BizAssert.badRequestIf(Duration.between(from, to).toDays() > MAX_RANGE_DAYS, "时间范围不得超过 90 天");

        accessService.require(request.elderId(), Permission.AI_INSIGHT);
        LlmConfig config = configService.findRaw(request.elderId());   // 见下方说明
        LlmCredentials cred = configService.credentialsOf(config);
        InsightContextBuilder.InsightContext ctx = contextBuilder.build(request.elderId(), type, from, to);
        return new Premise(request.elderId(), type, new InsightRange(from, to), cred, ctx);
    }

    private String prompt(Premise p) {
        return promptText(p.type(), p.range(), p.context().text());
    }

    /** 公共提示词：用药安全维度额外声明"只依据监护人填写的说明"。 */
    private String promptText(InsightType type, InsightRange range, String contextText) {
        StringBuilder sb = new StringBuilder()
                .append("分析维度：").append(type)
                .append("\n时间范围（UTC）：").append(range.from()).append(" ~ ").append(range.to())
                .append("\n结构化数据：\n").append(contextText);
        if (type == InsightType.MEDICATION_SAFETY || type == InsightType.COMPREHENSIVE) {
            sb.append("\n\n用药安全约束：只依据上下文【用药安全】中监护人填写的药品说明判断；")
              .append("说明未提及的成分、适应症、禁忌、相互作用一律回答「说明未覆盖，无法判断」。")
              .append("不得联网、不得引用说明之外的外部知识，不得给出诊断或停药 / 换药 / 剂量调整结论。");
        }
        return sb.toString();
    }

    private String noDataMessage(InsightType type) {
        return type == InsightType.MEDICATION_SAFETY ? NO_MEDICATION_DATA : NO_DATA;
    }

    private String sanitize(String content) {
        String out = content == null ? "" : content;
        for (String word : BANNED) {
            out = out.replace(word, "请咨询医生后再决定");
        }
        return out;
    }

    private void send(SseEmitter emitter, Map<String, ?> data) throws IOException {
        emitter.send(SseEmitter.event().data(data, MediaType.APPLICATION_JSON));
    }

    private record Premise(String elderId, InsightType type, InsightRange range,
                           LlmCredentials credentials, InsightContextBuilder.InsightContext context) {
    }
}