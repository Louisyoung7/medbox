package com.medbox.server.dto.adherence;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 服药依从性统计响应（{@code GET /api/v1/adherence}，见文档 02 第 7 章、07 的 {@code feat/record-report}）。
 *
 * <p>口径（已在规划期与产品确认）：按时率 = 周期内 {@code med_record.on_time=true} 记录数 ÷
 * 周期内记录总数；漏服数 = 周期内 {@code alarm.type=MISS} 条数（迟到有记录不计入漏服）。
 * 周期采用滚动窗口（day=24h / week=7d / month=30d），{@code windowStart}/{@code windowEnd} 为 UTC 边界；
 * {@code dailyBreakdown} 按老人 MAIN 设备时区做本地日分桶趋势。
 *
 * @param elderId          目标老人业务 ID
 * @param period           周期：day / week / month
 * @param windowStart      统计窗口起点（UTC）
 * @param windowEnd        统计窗口终点（UTC，= 当前时刻）
 * @param totalDoses       周期内服药记录总数（含 on_time 三态，即所有有 actual_time 的记录）
 * @param onTimeCount      按时次数（on_time=true）
 * @param lateCount        迟到次数（on_time=false，有记录但迟到）
 * @param unjudgedCount    未判定次数（on_time=null，UNCERTAIN / 无匹配槽）
 * @param onTimeRate       按时率 0.0~1.0（totalDoses=0 时记 0.0）
 * @param missCount        漏服次数（alarm.type=MISS）
 * @param dailyBreakdown   按老人本地日分桶的趋势（含无数据的日期，便于前端画连续折线）
 */
public record AdherenceResponse(
        String elderId,
        String period,
        OffsetDateTime windowStart,
        OffsetDateTime windowEnd,
        long totalDoses,
        long onTimeCount,
        long lateCount,
        long unjudgedCount,
        double onTimeRate,
        long missCount,
        List<AdherenceDaily> dailyBreakdown) {
}
