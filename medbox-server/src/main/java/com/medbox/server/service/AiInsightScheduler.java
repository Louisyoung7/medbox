package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.ai.InsightType;
import com.medbox.server.config.AiInsightProperties;
import com.medbox.server.domain.LlmConfig;
import com.medbox.server.dto.ai.InsightResponse;
import com.medbox.server.mapper.LlmConfigMapper;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import com.medbox.server.ws.WsTopic;
import com.medbox.server.ws.payload.AiInsightPayload;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 周期洞察：到点生成 COMPREHENSIVE 并走 WS AI_INSIGHT 推送（05 §2.3）。 */
@Component
public class AiInsightScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiInsightScheduler.class);

    private final LlmConfigMapper configMapper;
    private final AiInsightService insightService;
    private final ObjectProvider<WsPushService> pushServiceProvider;
    private final AiInsightProperties props;

    public AiInsightScheduler(LlmConfigMapper configMapper,
                              AiInsightService insightService,
                              ObjectProvider<WsPushService> pushServiceProvider,
                              AiInsightProperties props) {
        this.configMapper = configMapper;
        this.insightService = insightService;
        this.pushServiceProvider = pushServiceProvider;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${medbox.ai.insight.schedule-scan-interval-ms:3600000}")
    public void scheduledScan() {
        scan(Clock.systemUTC());
    }

    public ScanSummary scan(Clock clock) {
        if (!props.isEnabled()) {
            return new ScanSummary(0, 0, 0);
        }
        List<LlmConfig> configs = configMapper.selectList(new LambdaQueryWrapper<LlmConfig>()
                .eq(LlmConfig::getEnabled, true)
                .eq(LlmConfig::getInsightEnabled, true));
        long pushed = 0;
        long skipped = 0;
        for (LlmConfig config : configs) {
            try {
                OffsetDateTime now = OffsetDateTime.now(clock);
                Duration period = "WEEKLY".equals(config.getInsightCadence())
                        ? Duration.ofDays(7) : Duration.ofDays(1);
                if (config.getLastInsightAt() != null
                        && Duration.between(config.getLastInsightAt(), now).compareTo(period) < 0) {
                    skipped++;
                    continue;
                }
                InsightResponse result = insightService.generateForElder(
                        config.getElderId(), InsightType.COMPREHENSIVE, now.minus(period), now);
                WsPushService pushService = pushServiceProvider.getIfAvailable();
                if (pushService != null) {
                    pushService.publish(WsTopic.elder(config.getElderId()), WsFrameType.AI_INSIGHT,
                            new AiInsightPayload("ai-" + UUID.randomUUID().toString().substring(0, 8),
                                    config.getElderId(), InsightType.COMPREHENSIVE.name(),
                                    result.content(), result.citations(), result.disclaimer()));
                }
                config.setLastInsightAt(now);
                configMapper.updateById(config);
                pushed++;
            } catch (RuntimeException ex) {
                skipped++;
                log.warn("周期洞察失败 elderId={}：{}", config.getElderId(), ex.getMessage());
            }
        }
        return new ScanSummary(configs.size(), pushed, skipped);
    }

    /** 扫描结果，便于测试断言。 */
    public record ScanSummary(long scanned, long pushed, long skipped) {
    }
}