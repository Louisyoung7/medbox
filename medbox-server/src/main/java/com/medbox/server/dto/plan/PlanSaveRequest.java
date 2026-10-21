package com.medbox.server.dto.plan;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /plans}（创建）与 {@code PUT /plans/{planId}}（整体覆盖修改）共用的请求体。
 *
 * <p><b>创建 vs 修改</b>：{@code elderId} 仅创建时生效，修改时走 path 里的
 * {@code planId} 定位、归属老人不可变（忽略请求里的 {@code elderId}）。
 *
 * <p><b>{@code times} 是设备本地墙钟</b>（如 {@code ["08:00","20:00"]}），不传 UTC；
 * 格式与去重在 Service 层校验。{@code alarmRule} 形如 {@code {"miss":true}}。
 *
 * @param elderId           归属老人业务 ID（创建必填，修改忽略）
 * @param name              方案名，如"早餐后"（可空）
 * @param times             服药时间（本地墙钟），至少 1 个
 * @param repeatRule        重复规则，本期仅 DAILY（可空，默认 DAILY）
 * @param missAlarmAfterMin 漏服容忍分钟（可空=沿用上一级）
 * @param alarmRule         告警规则（可空）
 * @param items             药品明细，至少 1 条，同一计划内药品不重复
 */
public record PlanSaveRequest(
    @Size(max = 64, message = "elderId 超长")
    String elderId,

    @Size(max = 64, message = "name 超长")
    String name,

    List<String> times,

    @Size(max = 16, message = "repeatRule 超长")
    String repeatRule,

    Integer missAlarmAfterMin,

    Map<String, Boolean> alarmRule,

    @NotEmpty(message = "items 不能为空")
    List<PlanItemRequest> items
) {
}
