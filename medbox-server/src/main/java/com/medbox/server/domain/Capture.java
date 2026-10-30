package com.medbox.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 服药抓拍图片，对应 {@code capture} 表（见文档 06 的 2.12，由 V1 建）。
 *
 * <p><b>本分支最小可用</b>：仅用到 {@code capture_id} / {@code reason} 两列
 * （低置信度时把 {@code reason} 从 NORMAL 标记为 LOW_CONFIDENCE）；其余列按 V1 .schema 一并声明，
 * 供后续 {@code feat/capture-core} 复用 / 扩展，避免重复定义实体（合并点已记入 CHANGELOG）。
 *
 * <p><b>字段口径</b>：{@code reason} 取值 NORMAL / LOW_CONFIDENCE / MANUAL；
 * {@code confidence} 为 NUMERIC(4,3)，用 {@link BigDecimal} 承载。
 */
@Data
@TableName("capture")
public class Capture {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务 ID，如 {@code cap-1001}。 */
    private String captureId;

    /** 抓拍设备（药箱主控 {@code device_id}）。 */
    private String deviceId;

    /** 归属老人（{@code user.user_id}，ELDER）。 */
    private String elderId;

    /** 抓拍涉及的药品（可空）。 */
    private String medicineId;

    /** 关联计划（可空）。 */
    private String planId;

    /** 抓拍原因：NORMAL / LOW_CONFIDENCE / MANUAL。 */
    private String reason;

    /** 服务端本地存储路径，不对外暴露。 */
    private String filePath;

    /** MIME 类型。 */
    private String mime;

    /** 字节大小。 */
    private Integer sizeBytes;

    /** 识别置信度（可空）。 */
    private BigDecimal confidence;

    /** 抓拍时刻（UTC）。 */
    private OffsetDateTime capturedAt;

    /** 首次被查看的时刻（可空 = 未读）。 */
    private OffsetDateTime viewedAt;

    /** 清理时刻。 */
    private OffsetDateTime expireAt;

    private OffsetDateTime createdAt;
}
