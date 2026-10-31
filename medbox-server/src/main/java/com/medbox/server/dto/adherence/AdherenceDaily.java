package com.medbox.server.dto.adherence;

/**
 * 单日依从性趋势（{@code AdherenceResponse.dailyBreakdown} 的元素，见文档 02 第 7 章）。
 *
 * <p>{@code date} 为老人本地日期（{@code yyyy-MM-dd}）；{@code onTimeRate} 为当日按时率 0.0~1.0。
 * 无数据的日期也会补出（counts 全 0、rate=0.0），便于小程序画连续折线不中断。
 *
 * @param date         老人本地日期 yyyy-MM-dd
 * @param onTimeRate   当日按时率 0.0~1.0（totalDoses=0 时 0.0）
 * @param totalDoses   当日服药记录总数
 * @param onTimeCount  当日按时次数
 * @param missCount    当日漏服次数（alarm.type=MISS，按 created_at 本地日分桶）
 */
public record AdherenceDaily(
        String date,
        double onTimeRate,
        long totalDoses,
        long onTimeCount,
        long missCount) {
}
