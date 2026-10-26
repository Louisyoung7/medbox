package com.medbox.server.dto.alarm;

/**
 * 未读告警数（小程序角标），对应 {@code GET /alarms/unread-count}（见文档 02 第 8 章）。
 *
 * @param unreadCount 该老人名下未读告警数量（{@code read_state = false} 的计数）
 */
public record UnreadCountResponse(long unreadCount) {
}
