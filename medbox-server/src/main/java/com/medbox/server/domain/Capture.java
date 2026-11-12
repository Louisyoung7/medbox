package com.medbox.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 服药抓拍图片，对应 {@code capture} 表（见文档 06 的 2.12）。
 *
 * <p><b>字段口径</b>：{@code reason} 取值 NORMAL / MANUAL；{@code takenResult} 取值
 * TAKEN / UNTAKEN（设备端侧二值取药结论，不做人脸识别、不报置信度）；
 * 仅 {@code UNTAKEN} 上报携带原图，{@code TAKEN} 不上传图片。
 * 服药上报必带 {@code cellNo}，MANUAL 主动留证可空。
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

    /** 关联计划（可空；非空触发去重与落记录）。 */
    private String planId;

    /** 药格格号 1 / 2 / 3（服药上报必带，MANUAL 留证可空）。 */
    private Integer cellNo;

    /** 抓拍原因：NORMAL / MANUAL。 */
    private String reason;

    /** 服务端本地存储路径，不对外暴露。 */
    private String filePath;

    /** MIME 类型。 */
    private String mime;

    /** 字节大小。 */
    private Integer sizeBytes;

    /** 取药结论：TAKEN / UNTAKEN（可空，留证用）。 */
    private String takenResult;

    /** 抓拍时刻（UTC）。 */
    private OffsetDateTime capturedAt;

    /** 首次被查看的时刻（可空 = 未读）。 */
    private OffsetDateTime viewedAt;

    /** 清理时刻。 */
    private OffsetDateTime expireAt;

    private OffsetDateTime createdAt;
}
