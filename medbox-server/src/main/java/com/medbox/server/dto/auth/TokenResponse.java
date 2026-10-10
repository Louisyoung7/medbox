package com.medbox.server.dto.auth;

/**
 * 令牌响应：注册 / 登录 / 刷新**同构**（见文档 02 第 3 章）。
 *
 * @param token        access token，2 小时（{@code Authorization: Bearer xxx}）
 * @param refreshToken refresh token，7 天，轮换制
 * @param userId       业务 user_id，如 {@code u-1001}
 * @param role         ELDER / GUARDIAN
 * @param expiresIn    access token 有效期（秒），7200
 */
public record TokenResponse(String token, String refreshToken, String userId, String role, long expiresIn) {
}
