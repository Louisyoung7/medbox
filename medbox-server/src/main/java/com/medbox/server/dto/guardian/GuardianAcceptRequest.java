package com.medbox.server.dto.guardian;

/**
 * {@code PATCH /users/guardians/{relationId}/accept} 的请求体（见文档 02 第 3 章）。
 *
 * <p>监护人统一为单一 {@code GUARDIAN} 角色，确认监护关系时不再指定子角色（文档 10 §1.2），
 * 故请求体为空（仅占位），后端只把关系状态从 {@code PENDING} 置为 {@code ACTIVE}。
 */
public record GuardianAcceptRequest() {
}
