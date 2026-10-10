package com.medbox.server.common.security;

import java.time.Instant;

/**
 * 解析成功的令牌内容（见 {@link JwtProvider#parse(String, TokenType)}）。
 *
 * @param userId    业务 user_id（{@code sub}），如 {@code u-1001}
 * @param role      ELDER / GUARDIAN
 * @param type      令牌类型，已按期望值校验过
 * @param jti       令牌唯一 ID（{@code jti}），refresh token 用它关联 {@code refresh_token} 表
 * @param expiresAt 过期时刻（UTC）
 */
public record JwtPayload(String userId, String role, TokenType type, String jti, Instant expiresAt) {
}
