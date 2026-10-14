package com.medbox.server.dto.user;

import java.time.OffsetDateTime;

/**
 * 注销账号响应（{@code POST /users/me/delete}，见文档 02 的 3.1）。
 *
 * <p>只回 {@code userId} 与 {@code deletedAt}：客户端拿到后清本地凭据、跳登录页即可，
 * 不需要也不应该再拿到任何可用凭据。
 *
 * @param userId    被注销账号的业务 ID（行仍在库里，只是 {@code status = DELETED}）
 * @param deletedAt 注销时间（UTC）
 */
public record DeleteAccountResponse(String userId, OffsetDateTime deletedAt) {
}
