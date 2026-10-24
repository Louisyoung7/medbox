package com.medbox.server.dto.admin;

import com.medbox.server.domain.User;
import java.time.OffsetDateTime;

/**
 * 管理后台用户行（见文档 02 第 10.1 {@code AdminUserRow}）。
 *
 * <p>{@code role} / {@code status} 以字符串透出（与文档字段一致，避免前端再依赖枚举）；
 * 软删账号的 {@code phone} / {@code username} 为 NULL（见 06 的 2.1 V7），列表里自然为空。
 */
public record AdminUserRow(String userId, String role, String name, String phone,
                           String username, String status, OffsetDateTime createdAt) {

    /** 由 {@link User} 实体转换；role/status 取枚举名，null 安全。 */
    public static AdminUserRow from(User user) {
        if (user == null) {
            return null;
        }
        return new AdminUserRow(
                user.getUserId(),
                user.getRole() == null ? null : user.getRole().name(),
                user.getName(),
                user.getPhone(),
                user.getUsername(),
                user.getStatus() == null ? null : user.getStatus().name(),
                user.getCreatedAt());
    }
}
