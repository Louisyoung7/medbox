package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.domain.IdempotencyRecord;
import com.medbox.server.dto.auth.TokenResponse;
import com.medbox.server.mapper.IdempotencyRecordMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 写操作幂等：{@code X-Request-Id} 去重（见文档 02 的 1、02 第 3 章）。
 *
 * <p>本分支**只保护 {@code POST /auth/register}**（重复提交只创建 1 个账号、回放首次响应）；
 * 登录 / 刷新天然幂等，不落记录 —— 免得把含 token 的响应快照写进库里。
 *
 * <p>键是 {@code (request_id, endpoint)}；同号但请求体指纹不同按 {@code 40001} 处理（客户端用错了号）。
 * 快照 TTL 由 {@code medbox.idempotency.ttl-hours} 控制，过期后不再回放。
 */
@Service
public class IdempotencyService {

    /** 幂等作用域：注册。 */
    public static final String ENDPOINT_AUTH_REGISTER = "AUTH_REGISTER";

    private final IdempotencyRecordMapper idempotencyRecordMapper;
    private final ObjectMapper objectMapper;
    private final long ttlHours;

    public IdempotencyService(IdempotencyRecordMapper idempotencyRecordMapper,
                              ObjectMapper objectMapper,
                              @Value("${medbox.idempotency.ttl-hours:24}") long ttlHours) {
        this.idempotencyRecordMapper = idempotencyRecordMapper;
        this.objectMapper = objectMapper;
        this.ttlHours = ttlHours;
    }

    /**
     * 命中幂等记录则返回首次响应；没带 {@code X-Request-Id} 或没命中返回 {@code Optional.empty()}。
     */
    public Optional<TokenResponse> replay(String endpoint, String requestId, String requestHash) {
        if (requestId == null) {
            return Optional.empty();
        }
        IdempotencyRecord record = find(endpoint, requestId);
        if (record == null) {
            return Optional.empty();
        }
        BizAssert.badRequestIf(!record.getRequestHash().equals(requestHash),
                "X-Request-Id 已用于其它请求内容，请更换后重试");
        if (record.getExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            // 过期记录不再回放：删掉，同一个号可以重新开始（并发下删不掉也无妨，insert 有唯一键兜底）
            idempotencyRecordMapper.deleteById(record.getId());
            return Optional.empty();
        }
        return Optional.of(deserialize(record.getResponseSnapshot()));
    }

    /** 落一条幂等快照；并发下唯一键冲突由对方先写入，这里静默忽略（首个响应才是权威结果）。 */
    public void save(String endpoint, String requestId, String requestHash, TokenResponse response) {
        if (requestId == null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        IdempotencyRecord record = new IdempotencyRecord();
        record.setRequestId(requestId);
        record.setEndpoint(endpoint);
        record.setRequestHash(requestHash);
        record.setResponseSnapshot(serialize(response));
        record.setExpiresAt(now.plusHours(ttlHours));
        record.setCreatedAt(now);
        try {
            idempotencyRecordMapper.insert(record);
        } catch (Exception ex) {
            // 并发重复：别人已经写进去了，忽略即可（首个响应才是权威结果）
        }
    }

    /**
     * 请求体指纹：**只覆盖身份相关字段，不含密码** —— 同号重发视为同一次提交；
     * 换了手机号 / 角色却复用同一个号，按 40001 拒绝。
     */
    public String fingerprint(String... parts) {
        String raw = String.join("\u0000", Arrays.stream(parts).map(part -> part == null ? "" : part).toList());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private IdempotencyRecord find(String endpoint, String requestId) {
        return idempotencyRecordMapper.selectOne(new LambdaQueryWrapper<IdempotencyRecord>()
                .eq(IdempotencyRecord::getRequestId, requestId)
                .eq(IdempotencyRecord::getEndpoint, endpoint));
    }

    private String serialize(TokenResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception ex) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "幂等快照序列化失败");
        }
    }

    private TokenResponse deserialize(String snapshot) {
        try {
            return objectMapper.readValue(snapshot, TokenResponse.class);
        } catch (Exception ex) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "幂等快照已损坏");
        }
    }
}
