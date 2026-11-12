package com.medbox.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 服药记录，对应 {@code med_record} 表（见文档 06 的 2.7）。
 *
 * <p>一条记录 = 某计划的某号格在某次计划时间窗内的一次取药事实；只带 {@code cellNo}，
 * 不带药品、剂量。由设备经 HTTP captures 上报或监护人手工补录写入。
 *
 * <p>{@code onTime} 由后端按「该格就近槽 + 容忍时长」重算（见 07 §1），不采用设备侧上报值。
 *
 * <p><b>业务 ID {@code recordId}</b>（如 {@code mr-1001}）走 {@code med_record_biz_id_seq} 序列。
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

    /** 药格格号：1 / 2 / 3（早 / 中 / 晚）；缺失表示未识别。 */
    private Integer cellNo;

    /** 抓拍（{@code capture.capture_id}）。 */
    private String captureId;

    /** 该格计划服药时间（按设备时区换算的 UTC）；该格无排程/无匹配槽时为 null。 */
    private OffsetDateTime planTime;

    /** 实际服药时间（设备上报 {@code capturedAt} 的 UTC）。 */
    private OffsetDateTime actualTime;

    /** 是否按时：单侧窗口（提前恒 true，只判迟到）。 */
    private Boolean onTime;

    /** 来源：CAMERA 设备上报 / MANUAL 手工补录。 */
    private String source;

    /** 取药结论：TAKEN / UNTAKEN（可空，监护人校准 / 补录后改写）。 */
    private String takenResult;

    /** 上报设备（药箱主控）的 {@code device_id}。 */
    private String deviceId;

    private OffsetDateTime createdAt;
}
