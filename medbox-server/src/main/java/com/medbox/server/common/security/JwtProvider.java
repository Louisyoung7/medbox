package com.medbox.server.common.security;

import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JWT 签发与解析（HS256，见文档 02 第 3 章）。
 *
 * <p>claims：{@code sub=userId}、{@code role}、{@code typ}、{@code jti}、{@code iat}、{@code exp}。
 * 有效期：access {@value #DEFAULT_ACCESS_TTL_SECONDS}s（2 小时）、refresh
 * {@value #DEFAULT_REFRESH_TTL_SECONDS}s（7 天），均由 {@code medbox.jwt.*} 配置。
 *
 * <p><b>一切解析失败（签名错 / 过期 / 类型不符 / 被篡改）都收敛成 {@code BizException(40101)}</b>，
 * 由 {@code GlobalExceptionHandler} 输出统一响应，不把 JWT 库的具体异常类型与细节透出去。
 *
 * <p><b>依赖说明</b>：JSON provider 用 {@code jjwt-orgjson} 而不是 {@code jjwt-jackson} —— 后者依赖
 * Jackson 2，会被 Spring Boot 4 自带的 Jackson 3（{@code tools.jackson}）BOM 顶掉，运行时直接
 * {@code NoClassDefFoundError}。
 */
@Component
public class JwtProvider {

    /** access token 默认有效期（秒）：2 小时，与文档 02 第 3 章一致。 */
    public static final long DEFAULT_ACCESS_TTL_SECONDS = 7200L;

    /** refresh token 默认有效期（秒）：7 天。 */
    public static final long DEFAULT_REFRESH_TTL_SECONDS = 604800L;

    /** HS256 要求密钥不少于 256 bit。 */
    private static final int MIN_SECRET_BYTES = 32;

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TYPE = "typ";

    private final SecretKey key;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    public JwtProvider(
            @Value("${medbox.jwt.secret}") String secret,
            @Value("${medbox.jwt.access-token-ttl-seconds:" + DEFAULT_ACCESS_TTL_SECONDS + "}") long accessTtlSeconds,
            @Value("${medbox.jwt.refresh-token-ttl-seconds:" + DEFAULT_REFRESH_TTL_SECONDS + "}") long refreshTtlSeconds) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "medbox.jwt.secret 至少 " + MIN_SECRET_BYTES + " 字节（HS256 要求），请用 MEDBOX_JWT_SECRET 覆盖");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    /** 签发一枚令牌，{@code jti} 自动生成（refresh token 用它落库做轮换与重放检测）。 */
    public String issue(String userId, String role, TokenType type) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_TYPE, type.name())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds(type))))
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验类型；失败一律 {@code 40101}。
     *
     * @param expected 期望的令牌类型（{@code null} 表示不校验类型）
     */
    public JwtPayload parse(String token, TokenType expected) {
        if (token == null || token.isBlank()) {
            throw unauthorized();
        }
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            throw unauthorized();
        }
        String typeName = claims.get(CLAIM_TYPE, String.class);
        if (expected != null && !expected.name().equals(typeName)) {
            throw unauthorized();
        }
        String userId = claims.getSubject();
        if (userId == null || userId.isBlank()) {
            throw unauthorized();
        }
        Instant expiresAt = claims.getExpiration() == null ? Instant.EPOCH : claims.getExpiration().toInstant();
        return new JwtPayload(userId, claims.get(CLAIM_ROLE, String.class),
                typeName == null ? null : TokenType.valueOf(typeName), claims.getId(), expiresAt);
    }

    public long accessTtlSeconds() {
        return accessTtlSeconds;
    }

    public long refreshTtlSeconds() {
        return refreshTtlSeconds;
    }

    /** 按类型取有效期（秒）。 */
    public long ttlSeconds(TokenType type) {
        return type == TokenType.REFRESH ? refreshTtlSeconds : accessTtlSeconds;
    }

    private static BizException unauthorized() {
        return BizException.of(ErrorCode.UNAUTHORIZED, "登录状态无效或已过期，请重新登录");
    }
}
