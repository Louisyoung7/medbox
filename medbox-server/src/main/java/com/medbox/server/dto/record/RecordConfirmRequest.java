package com.medbox.server.dto.record;

import java.time.OffsetDateTime;

/**
 * 监护人补录 / 校准请求体（见文档 02 第 7 章、07 的 {@code feat/record-core}）。
 *
 * <p><b>单接口双语义</b>：{@code POST /records/{recordId}/confirm} 命中已有记录 → 校准（仅覆盖非空字段）；
 * 记录不存在 → 新建（补录=创建，{@code elderId}/{@code planId}/{@code planItemId}/{@code medicineId} 必填）。
 *
 * <p><b>覆盖规则</b>：{@code actualTime}/{@code actualDose}/{@code onTime}/{@code faceResult} 任一为
 * {@code null} 表示"不改该项"；无论校准还是新建，{@code source} 恒为 {@code MANUAL}（由 Service 强制写入）。
 *
 * @param elderId     归属老人业务 ID（仅新建必填）
 * @param planId      所属计划业务 ID（仅新建必填）
 * @param planItemId  计划明细业务 ID（仅新建必填）
 * @param medicineId  识别 / 服用药品业务 ID（仅新建必填）
 * @param actualTime  实际服药时间（UTC），设备时间不准时监护人手填
 * @param actualDose  实际服用剂量（如 "1"）
 * @param onTime      是否按时：监护人可推翻后端自动判定
 * @param faceResult  服药结论 TAKEN/ABSENT/UNCERTAIN：监护人看图复核后改写（含把 UNCERTAIN 改写为 TAKEN 触发补扣）
 */
public record RecordConfirmRequest(
        String elderId,
        String planId,
        String planItemId,
        String medicineId,
        OffsetDateTime actualTime,
        String actualDose,
        Boolean onTime,
        String faceResult) {
}
