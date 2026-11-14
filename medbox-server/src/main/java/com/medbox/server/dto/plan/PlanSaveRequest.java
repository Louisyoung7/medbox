package com.medbox.server.dto.plan;

import com.medbox.server.domain.PlanSlot;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /plans}（创建）与 {@code PUT /plans/{planId}}（整体覆盖修改）共用的请求体。
 *
 * <p><b>创建 vs 修改</b>：{@code elderId} 仅创建时生效；一老人一计划，重复创建返回 40901。
 * 修改走 path 里的 {@code planId} 定位，归属老人不可变。
 *
 * <p><b>{@code slots} 只描述「几点开几号格」</b>：1~3 项、{@code cellNo} ∈ 1/2/3 且不重复、
 * {@code time} 为 HH:mm 设备本地墙钟（不传 UTC）；未提交的格号视为该格不排程。
 * 格号合法性与去重在 Service 层校验，计划与药品无任何关联。
 *
 * @param elderId           归属老人业务 ID（创建必填，修改忽略）
 * @param name              方案名（可空）
 * @param slots             格号 → 开格时间，至少 1 项、最多 3 项
 * @param repeatRule        重复规则，本期仅 DAILY（可空，默认 DAILY）
 * @param missAlarmAfterMin 漏服容忍分钟（可空=沿用上一级）
 * @param alarmRule         告警规则（可空）
 */
public record PlanSaveRequest(
    @Size(max = 64, message = "elderId 超长")
    String elderId,

    @Size(max = 64, message = "name 超长")
    String name,

    @NotEmpty(message = "slots 不能为空")
    @Size(max = 3, message = "slots 最多 3 项")
    List<PlanSlot> slots,

    @Size(max = 16, message = "repeatRule 超长")
    String repeatRule,

    Integer missAlarmAfterMin,

    Map<String, Boolean> alarmRule
) {
}
