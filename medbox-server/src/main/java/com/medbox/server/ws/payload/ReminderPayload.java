package com.medbox.server.ws.payload;

/**
 * 服药提醒帧载荷（{@code REMINDER}，见文档 04 第 3 章 / 07 §4）。
 *
 * <p>按格提醒：只带 {@code planId / planTime / cellNo}，不含药品信息（格内放什么药由监护人布置，
 * 后端不记录）。{@code planTime} 为计划服药时间（UTC ISO 瞬时），如 {@code 2026-10-04T00:00:00Z}。
 */
public record ReminderPayload(String planId, String planTime, Integer cellNo) {
}
