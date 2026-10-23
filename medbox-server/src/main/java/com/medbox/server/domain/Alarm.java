package com.medbox.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 告警，对应 {@code alarm} 表（见文档 06 的 2.9，表由 V1 建；V12 补 {@code alarm_biz_id_seq} 序列与 {@code dedup_key} 列）。
 *
 * <p>本实体由 {@code feat/miss-alarm} 首次写入（MISS 漏服告警）；后续 {@code env-alarm} /
 * {@code alarm-device-offline} / {@code wrong-drug-alarm} 复用同一张表与 {@code dedup_key} 去重列。
 * {@code type} / {@code level} / {@code handleState} 是 VARCHAR：允许值由文档 06 §2.9 / 02 第 8 章定义，
 * 取值校验放 Service 层（{@code BizAssert}），不引枚举以免超出 06 schema。
 *
 * <p><b>业务 ID {@code alarmId}</b> 走 {@code alarm_biz_id_seq} 序列（V12 建），前缀 {@code a-}，
 * 取号方法见 {@link com.medbox.server.mapper.AlarmMapper#nextAlarmId()}。
 *
 * <p><b>命名</b>：{@code readState} / {@code handleState} 自动映射到 {@code read_state} / {@code handle_state}
 * （与 {@link AlarmSetting#missTolerateMin} → {@code miss_tolerate_min} 同一套 MP 下划线命名策略）。
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

    /** 关联计划（MISS / WRONG_DRUG 必带，ENV / DEVICE_OFFLINE 等可空）。 */
    private String planId;

    /** 关联药品（MISS 记第一个缺失药；WRONG_DRUG / EXPIRED 必带；多药时取其一，全量见 WS 的 medicineIds）。 */
    private String medicineId;

    /** 告警类型：MISS / WRONG_DRUG / EXPIRED / ENV / DEVICE_OFFLINE / DEVICE_FAULT。 */
    private String type;

    /** 严重程度：INFO / WARN / CRITICAL。 */
    private String level;

    /** 可直接展示的文案，如"08:00 计划服药未按时服用：缺 阿莫西林、维生素D"。 */
    private String message;

    /** 是否已读，默认 false。 */
    private Boolean readState;

    /** 处理状态：OPEN / HANDLED，默认 OPEN。 */
    private String handleState;

    /** 去重键（V12 新增）：MISS=MISS:{planId}:{slotUTC}；唯一索引冲突即跳过，避免重复告警。 */
    private String dedupKey;

    private OffsetDateTime createdAt;
}
