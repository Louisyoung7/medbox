package com.medbox.server.dto.record;

import java.time.OffsetDateTime;

/**
 * 监护人补录 / 纠错请求体（见文档 02e §1.1 / 07 §1）。
 *
 * <p><b>单接口双语义</b>：{@code POST /records/{recordId}/confirm} 命中已有记录 → 纠错（仅覆盖非空字段）；
 * 记录不存在 → 新建（补录，路径 {@code recordId} 被忽略，{@code elderId}/{@code planId}/{@code cellNo} 必填）。
 *
 * <p><b>覆盖规则</b>：{@code actualTime}/{@code onTime}/{@code takenResult} 任一为 {@code null}
 * 表示"不改该项"；无论纠错还是新建，{@code source} 恒为 {@code MANUAL}（由 Service 强制写入）。
 * 记录新增 / 纠错均不影响其他业务数据。
 *
 * @param elderId     归属老人业务 ID（仅新建必填）
 * @param planId      所属计划业务 ID（仅新建必填）
 * @param cellNo      药格格号 1 / 2 / 3（仅新建必填）
 * @param actualTime  实际服药时间（UTC），设备时间不准时监护人手填
 * @param onTime      是否按时：监护人可推翻后端自动判定
 * @param takenResult 取药结论 TAKEN / UNTAKEN
 */
public record RecordConfirmRequest(
        String elderId,
        String planId,
        Integer cellNo,
        OffsetDateTime actualTime,
        Boolean onTime,
        String takenResult) {
}
