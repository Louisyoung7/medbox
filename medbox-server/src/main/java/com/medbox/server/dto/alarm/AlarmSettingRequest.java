package com.medbox.server.dto.alarm;

import com.medbox.server.domain.EnvThreshold;

/**
 * 老人级告警阈值配置请求，对应 {@code PUT /alarm-settings?elderId=}（见文档 02 第 8 章）。
 *
 * <p>所有字段可选：仅提供的字段会被覆盖（其余保持原值），便于前端只改某一项。
 * 不提供任何字段时等同"仅刷新 updatedAt"（幂等）。
 *
 * @param expiringAheadDays 临期提前预警天数（天）
 * @param missTolerateMin   漏服容忍时长（分钟），老人级；为空表示沿用系统默认 15 分钟
 * @param envThreshold      环境上下限（JSONB，结构见 {@link EnvThreshold}）
 */
public record AlarmSettingRequest(Integer expiringAheadDays,
                                  Integer missTolerateMin,
                                  EnvThreshold envThreshold) {
}
