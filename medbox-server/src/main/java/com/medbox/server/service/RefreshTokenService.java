package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.common.security.JwtPayload;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.RefreshToken;
import com.medbox.server.domain.enums.RefreshTokenStatus;
import com.medbox.server.mapper.RefreshTokenMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/**
 * refresh token 的签发、轮换与撤销（见文档 02 第 3 章、06 的 2.13）。
 *
 * <p><b>轮换（rotation）</b>：用旧的 refresh token 换新的时，旧记录立即置 {@code ROTATED} 并指向接替者，
 * 之后再用旧 token 就是**重放** —— 视为令牌可能已泄漏，撤销该用户全部 refresh token 并返回 40101。
 *
 * <p>库里只存 token 的 SHA-256 哈希，不存明文；{@code tokenId} 与 JWT 的 {@code jti} 一致。
 */
@Service
public class RefreshTokenService {

    private static final String REPLAY_MESSAGE = "登录状态已失效，请重新登录";

    private final RefreshTokenMapper refreshTokenMapper;
    private final JwtProvider jwtProvider;

    public RefreshTokenService(RefreshTokenMapper refreshTokenMapper, JwtProvider jwtProvider) {
        this.refreshTokenMapper = refreshTokenMapper;
        this.jwtProvider = jwtProvider;
    }

    /** 轮换结果：{@code rotatedTokenId} 是被顶替的旧 jti，用来回填它的 {@code replaced_by}。 */
    public record Rotation(String userId, String rotatedTokenId) {
    }

    /**
     * 登记一枚新的 refresh token。
     *
     * @param rotatedTokenId 被它顶替的旧 token_id（注册 / 登录场景传 {@code null}）
     */
    public void issue(String userId, String rawRefreshToken, String rotatedTokenId) {
        JwtPayload payload = jwtProvider.parse(rawRefreshToken, TokenType.REFRESH);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        RefreshToken row = new RefreshToken();
        row.setTokenId(payload.jti());
        row.setUserId(userId);
        row.setTokenHash(sha256Hex(rawRefreshToken));
        row.setStatus(RefreshTokenStatus.ACTIVE);
        row.setExpiresAt(OffsetDateTime.ofInstant(payload.expiresAt(), ZoneOffset.UTC));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        refreshTokenMapper.insert(row);

        if (rotatedTokenId != null) {
            RefreshToken old = findByTokenId(rotatedTokenId);
            if (old != null) {
                old.setStatus(RefreshTokenStatus.ROTATED);
                old.setReplacedBy(payload.jti());
                old.setUpdatedAt(now);
                refreshTokenMapper.updateById(old);
            }
        }
    }

    /**
     * 校验并轮换一枚 refresh token，返回它所属的用户。
     *
     * <p>校验顺序：记录存在 → 状态为 ACTIVE（否则按重放撤销全部）→ 未过期 → 哈希吻合。
     */
    public Rotation rotate(String rawRefreshToken) {
        JwtPayload payload = jwtProvider.parse(rawRefreshToken, TokenType.REFRESH);
        RefreshToken row = findByTokenId(payload.jti());
        BizAssert.unauthorizedIf(row == null, REPLAY_MESSAGE);

        if (row.getStatus() != RefreshTokenStatus.ACTIVE) {
            // 重放：旧 token 被用过一次了，说明它可能已经泄漏 —— 撤销该用户全部会话，强制重新登录
            revokeAll(row.getUserId());
            throw BizException.of(ErrorCode.UNAUTHORIZED, REPLAY_MESSAGE);
        }
        if (row.getExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "登录已过期，请重新登录");
        }
        if (!MessageDigest.isEqual(row.getTokenHash().getBytes(StandardCharsets.UTF_8),
                sha256Hex(rawRefreshToken).getBytes(StandardCharsets.UTF_8))) {
            revokeAll(row.getUserId());
            throw BizException.of(ErrorCode.UNAUTHORIZED, REPLAY_MESSAGE);
        }

        // 先置 ROTATED；replaced_by 要等新的 jti 生成后由 issue() 回填
        row.setStatus(RefreshTokenStatus.ROTATED);
        row.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        refreshTokenMapper.updateById(row);
        return new Rotation(row.getUserId(), row.getTokenId());
    }

    /** 撤销该用户仍在生效的全部 refresh token（登出 / 重放保护）。 */
    public void revokeAll(String userId) {
        refreshTokenMapper.revokeActiveByUserId(userId, RefreshTokenStatus.REVOKED);
    }

    private RefreshToken findByTokenId(String tokenId) {
        return refreshTokenMapper.selectOne(new LambdaQueryWrapper<RefreshToken>()
                .eq(RefreshToken::getTokenId, tokenId));
    }

    /** SHA-256 十六进制（只用于落库比对，不做口令派生 —— 口令派生是 BCrypt 的活）。 */
    public static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /** 供测试/排障：把 Instant 转 UTC 的 OffsetDateTime。 */
    static OffsetDateTime toUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
