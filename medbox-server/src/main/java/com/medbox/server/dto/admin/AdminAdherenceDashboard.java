package com.medbox.server.dto.admin;

import java.util.List;

/**
 * 服药依从率看板（见文档 02 §10.4.3）。
 *
 * <p>{@code trend} 按 granularity 归并到 week（yyyy-Www）/ month（yyyy-MM）桶；
 * {@code missByHour} 按 plan_time 的 UTC 小时分桶（演示近似）；
 * {@code byElder} 按老人维度取 top10，rate = taken / totalSlots（totalSlots 为「当前计划展开」近似）。
 */
public record AdminAdherenceDashboard(
        List<AdherencePoint> trend,
        List<MissHourBucket> missByHour,
        List<ElderAdherence> byElder) {

    /** 趋势点（按天统计后归并到 week/month 桶）。 */
    public record AdherencePoint(String bucket, long totalSlots, long taken, long missed, double rate) {}

    /** 漏服时段分布桶。 */
    public record MissHourBucket(int hour, long count) {}

    /** 按老人依从率排行。 */
    public record ElderAdherence(
            String elderId, String elderName, long totalSlots, long taken, long missed, double rate) {}
}
