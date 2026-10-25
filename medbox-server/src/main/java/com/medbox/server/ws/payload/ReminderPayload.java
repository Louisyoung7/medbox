package com.medbox.server.ws.payload;

import java.util.List;

/**
 * 服药提醒帧载荷（{@code REMINDER}，feat/plan-reminder，见文档 04 第 3 章）。
 *
 * <p>一次计划常含多种药（如降压药 + 阿司匹林同时服用），故 {@code medicines} 用数组一次带全，
 * 小程序侧据此渲染"该时间点要吃哪些药"。{@code planTime} 为<b>计划服药时间（UTC ISO 瞬时）</b>，
 * 如 {@code 2026-10-04T00:00:00Z}（与 04 第 3 章示例一致）。
 */
public record ReminderPayload(String planId, String planTime, List<ReminderMedicine> medicines) {

    /** 单次提醒里的单个药品条目（一次计划可含多个）。 */
    public record ReminderMedicine(String itemId, String medicineId, String medicineName, String dose, String unit) {
    }
}
