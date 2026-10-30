package com.medbox.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 服药记录，对应 {@code med_record} 表（见文档 06 的 2.7，由 V1 建；V10 补 {@code source} 列）。
 *
 * <p>由 {@code feat/dispense-consume} 消费设备 {@code up/event/dispense} 上报后写入；
 * {@code onTime} 由后端按"计划时间 + 容忍时长"重算（见 06 §2.7 规则表），**不**直接采用设备侧上报值。
 *
 * <p><b>业务 ID {@code recordId}</b>（如 {@code mr-1001}）走 {@code med_record_biz_id_seq} 序列（V10 建），
 * 取号方法在 {@code MedRecordMapper} 里。前缀用 {@code mr-} 避免与 {@code guardian_relation} 的 {@code r-} 混淆。
 */
@Data
@TableName("med_record")
public class MedRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务 ID，如 {@code mr-1001}（V10 的 med_record_biz_id_seq）。 */
    private String recordId;

    /** 归属老人（冗余 {@code device.elder_id}）。 */
    private String elderId;

    /** 所属计划（{@code med_plan.plan_id}）。 */
    private String planId;

    /** 计划明细（{@code med_plan_item.item_id}）。 */
    private String planItemId;

    /** 识别到的药品（{@code medicine.medicine_id}）。 */
    private String medicineId;

    /** 抓拍（{@code capture.capture_id}）。 */
    private String captureId;

    /** 实际服用剂量，原样落库。 */
    private String actualDose;

    /** 计划服药时间（按设备时区换算的 UTC）；脏数据/无匹配槽时为 null。 */
    private OffsetDateTime planTime;

    /** 实际服药时间（设备上报 {@code ts} 的 UTC）。 */
    private OffsetDateTime actualTime;

    /** 是否按时：单侧窗口（提前恒 true，只判迟到）。 */
    private Boolean onTime;

    /** 来源：CAMERA / MANUAL。 */
    private String source;

    /** 服药结论：TAKEN / ABSENT / UNCERTAIN（可空，监护人复核后改写）。 */
    private String faceResult;

    /** 摄像头识别置信度 0~1（可空，仅 CAMERA 来源有值）。 */
    private BigDecimal confidence;

    /** 上报设备（药箱主控）的 {@code device_id}。 */
    private String deviceId;

    private OffsetDateTime createdAt;
}
