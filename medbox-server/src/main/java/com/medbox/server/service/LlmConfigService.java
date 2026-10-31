package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.ai.ApiKeyCipher;
import com.medbox.server.ai.LlmChatGateway;
import com.medbox.server.ai.LlmCredentials;
import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.common.security.Permission;
import com.medbox.server.domain.LlmConfig;
import com.medbox.server.dto.ai.InsightScheduleRequest;
import com.medbox.server.dto.ai.InsightScheduleResponse;
import com.medbox.server.dto.ai.LlmConfigRequest;
import com.medbox.server.dto.ai.LlmConfigResponse;
import com.medbox.server.dto.ai.LlmConfigTestResponse;
import com.medbox.server.mapper.LlmConfigMapper;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** BYOK 配置管理：AES-GCM 加密存 Key、脱敏回显、连通性探测、周期洞察开关（见文档 05 §2.1 / §2.3）。 */
@Service
public class LlmConfigService {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigService.class);

    private static final String DEFAULT_PROVIDER = "openai-compatible";
    private static final String UNOPENED = "AI 助手暂未开通，请联系监护人配置";

    private final LlmConfigMapper configMapper;
    private final AccessService accessService;
    private final ApiKeyCipher cipher;
    private final LlmChatGateway gateway;

    public LlmConfigService(LlmConfigMapper configMapper, AccessService accessService,
                            ApiKeyCipher cipher, LlmChatGateway gateway) {
        this.configMapper = configMapper;
        this.accessService = accessService;
        this.cipher = cipher;
        this.gateway = gateway;
    }

    @Transactional
    public LlmConfigResponse save(String elderId, LlmConfigRequest req) {
        accessService.require(elderId, Permission.CONFIG_LLM_KEY);
        BizAssert.notBlank(req.model(), "model 不能为空");
        BizAssert.notBlank(req.baseUrl(), "baseUrl 不能为空");
        BizAssert.badRequestIf(!req.baseUrl().startsWith("http"), "baseUrl 必须以 http 开头");

        LlmConfig config = find(elderId);
        boolean created = config == null;
        if (created) {
            config = new LlmConfig();
            config.setConfigId("cfg-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            config.setElderId(elderId);
            config.setInsightEnabled(false);
            config.setInsightCadence("DAILY");
        }
        config.setProvider(req.provider() == null ? DEFAULT_PROVIDER : req.provider());
        config.setModel(req.model());
        config.setBaseUrl(req.baseUrl());
        config.setEnabled(req.enabled() == null || req.enabled());
        // Key 为空 = 沿用旧密文（编辑其他字段不必重填 Key）
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            config.setApiKeyEnc(cipher.encrypt(req.apiKey()));
        } else if (created) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "首次配置必须填写 apiKey");
        }
        config.setUpdatedAt(OffsetDateTime.now());
        if (created) {
            configMapper.insert(config);
        } else {
            configMapper.updateById(config);
        }
        return toResponse(config);
    }

    @Transactional(readOnly = true)
    public LlmConfigResponse get(String elderId) {
        accessService.require(elderId, Permission.AI_INSIGHT);
        LlmConfig config = find(elderId);
        BizAssert.notFound(config, "尚未配置大模型");
        return toResponse(config);
    }

    @Transactional
    public void delete(String elderId) {
        accessService.require(elderId, Permission.CONFIG_LLM_KEY);
        LlmConfig config = find(elderId);
        BizAssert.notFound(config, "尚未配置大模型");
        configMapper.deleteById(config.getId());
    }

    public LlmConfigTestResponse test(String elderId) {
        accessService.require(elderId, Permission.CONFIG_LLM_KEY);
        LlmConfig config = find(elderId);
        BizAssert.notFound(config, "尚未配置大模型");
        LlmCredentials cred = credentialsOf(config);
        long start = System.currentTimeMillis();
        try {
            gateway.complete(cred, "你是连通性探测助手，只回复 OK。", "ping");
            return new LlmConfigTestResponse(true, System.currentTimeMillis() - start, "连接成功");
        } catch (RuntimeException ex) {
            log.warn("AI Key 连通测试失败 elderId={}：{}", elderId, ex.getMessage()); // 不打 Key
            return new LlmConfigTestResponse(false, System.currentTimeMillis() - start, "连接失败或额度不足");
        }
    }

    @Transactional(readOnly = true)
    public InsightScheduleResponse schedule(String elderId) {
        accessService.require(elderId, Permission.AI_INSIGHT);
        LlmConfig config = find(elderId);
        if (config == null) {
            return new InsightScheduleResponse(elderId, false, "DAILY");
        }
        return new InsightScheduleResponse(elderId,
                Boolean.TRUE.equals(config.getInsightEnabled()),
                config.getInsightCadence() == null ? "DAILY" : config.getInsightCadence());
    }

    @Transactional
    public InsightScheduleResponse updateSchedule(String elderId, InsightScheduleRequest req) {
        accessService.require(elderId, Permission.CONFIG_LLM_KEY);
        String cadence = req.cadence() == null ? "DAILY" : req.cadence().toUpperCase();
        BizAssert.badRequestIf(!("DAILY".equals(cadence) || "WEEKLY".equals(cadence)),
                "cadence 仅支持 DAILY / WEEKLY");
        LlmConfig config = find(elderId);
        BizAssert.notFound(config, "请先完成大模型配置，再开启周期洞察");
        config.setInsightEnabled(req.enabled() != null && req.enabled());
        config.setInsightCadence(cadence);
        config.setUpdatedAt(OffsetDateTime.now());
        configMapper.updateById(config);
        return new InsightScheduleResponse(elderId, config.getInsightEnabled(), cadence);
    }

    /** 取解密后的凭据；未配置 / 关闭 / 缺字段一律 50310。 */
    LlmCredentials credentialsOf(LlmConfig config) {
        BizAssert.isTrue(config != null && Boolean.TRUE.equals(config.getEnabled())
                        && config.getApiKeyEnc() != null && config.getApiKeyEnc().length() > 0
                        && config.getModel() != null && !config.getModel().isBlank()
                        && config.getBaseUrl() != null && !config.getBaseUrl().isBlank(),
                ErrorCode.AI_UNAVAILABLE, UNOPENED);
        try {
            return new LlmCredentials(config.getBaseUrl(), cipher.decrypt(config.getApiKeyEnc()), config.getModel());
        } catch (IllegalStateException ex) {
            log.warn("AI Key 解密失败 elderId={}：{}", config.getElderId(), ex.getMessage());
            throw BizException.of(ErrorCode.AI_UNAVAILABLE, UNOPENED);
        }
    }

    private LlmConfig find(String elderId) {
        return configMapper.selectOne(new LambdaQueryWrapper<LlmConfig>().eq(LlmConfig::getElderId, elderId));
    }

    private LlmConfigResponse toResponse(LlmConfig c) {
        // 只回显脱敏；apiKeyEnc 为 null（脏数据）时解密会抛 IllegalStateException → GET 变 500，先判空
        String mask = c.getApiKeyEnc() == null ? null : ApiKeyCipher.mask(cipher.decrypt(c.getApiKeyEnc()));
        return new LlmConfigResponse(c.getElderId(), c.getProvider(), c.getModel(), c.getBaseUrl(),
                mask,
                Boolean.TRUE.equals(c.getEnabled()),
                Boolean.TRUE.equals(c.getInsightEnabled()),
                c.getInsightCadence() == null ? "DAILY" : c.getInsightCadence());
    }

    /** 内部用：不做权限校验（调用方已完成鉴权 / 定时任务无登录态）。 */
    public LlmConfig findRaw(String elderId) {
        return find(elderId);
    }

}