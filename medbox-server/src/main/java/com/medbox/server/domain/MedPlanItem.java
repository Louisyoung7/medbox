package com.medbox.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 服药计划明细，对应 {@code med_plan_item} 表（见文档 06 的 2.6.1，由 V1 建）。
 *
 * <p><b>无仓位字段</b>：一次计划可含多种药（如"降压药+阿司匹林"），每种药的单次剂量 / 单位 /
 * 服用说明挂在这一行；"任一 item 缺失即判 MISS"的判定在后续 feat/plan-reminder 实现。
 *
 * <p><b>主键必须 {@code IdType.AUTO}</b>：表主键是 BIGSERIAL（同 Medicine / Device）。
 */
@Data
@TableName("med_plan_item")
public class MedPlanItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务 ID，如 {@code pi-1001}（V9 的 med_plan_item_biz_id_seq）。 */
    private String itemId;

    /** 所属计划（{@code med_plan.plan_id}）。 */
    private String planId;

    /** 药品（{@code medicine.medicine_id}）。 */
    private String medicineId;

    /** 单次剂量，如 "1"。 */
    private String dose;

    /** 单位，如 "片"。 */
    private String unit;

    /** 服用说明，如"餐后"。 */
    private String note;

    /** 展示排序。 */
    private Integer sortOrder;

    private OffsetDateTime createdAt;
}
