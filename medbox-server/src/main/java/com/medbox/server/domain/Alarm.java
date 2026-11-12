package com.medbox.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 告警，对应 {@code alarm} 表（见文档 06 的 2.9 / 07 §2、§3）。
 *
 * <p>{@code type} / {@code level} / {@code handleState} 是 VARCHAR，取值校验放 Service 层。
 * MISS 告警带 {@code cellNo} 与 {@code planId}，message 只提格号与时间；其它类型 {@code cellNo} 为空。
 *
 * <p><b>业务 ID {@code alarmId}</b> 走 {@code alarm_biz_id_seq} 序列，前缀 {@code a-}。
 */
@Data
@TableName("alarm")
public class Alarm {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务 ID，如 {@code a-1001}（V12 的 alarm_biz_id_seq）。 */
    private String alarmId;

    /** 归属老人（{@code user.user_id}，ELDER）。 */
    private String elderId;

    /** 关联设备（药箱主控 {@code device_id}）；ENV / DEVICE_OFFLINE 等也带。 */
    private String deviceId;

    /** 关联计划（MISS 必带，ENV / DEVICE_OFFLINE 等可空）。 */
    private String planId;

    /** MISS 关联格号 1 / 2 / 3；其它类型为空。 */
    private Integer cellNo;

    /** 告警类型：MISS / ENV / DEVICE_OFFLINE / DEVICE_FAULT。 */
    private String type;

    /** 严重程度：INFO / WARN / CRITICAL。 */
    private String level;

    /** 可直接展示的文案，如「08:00 1 号格（早间）未按时取药」。 */
    private String message;

    /** 是否已读，默认 false。 */
    private Boolean readState;

    /** 处理状态：OPEN / HANDLED，默认 OPEN。 */
    private String handleState;

    /** 去重键：MISS=MISS:{planId}:{cellNo}:{slotUTC}；唯一索引冲突即跳过。 */
    private String dedupKey;

    private OffsetDateTime createdAt;
}
