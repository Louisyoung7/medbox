package com.medbox.server.dto.alarm;

import com.medbox.server.domain.AlarmSetting;
import com.medbox.server.domain.EnvThreshold;
import java.time.OffsetDateTime;

/**
 * 老人级告警阈值配置响应，对应 {@code GET /alarm-settings} 与 {@code PUT /alarm-settings} 回显
 * （见文档 02 第 8 章）。
 *
 * <p>未配置过阈值的老人返回全部字段为 null（前端按"系统默认"展示）；{@code envThreshold}
 * 任意子字段为 null 表示该上下限未启用。
 */
public record AlarmSettingResponse(Integer expiringAheadDays,
                                   Integer missTolerateMin,
                                   EnvThreshold envThreshold,
                                   OffsetDateTime updatedAt) {

    public static AlarmSettingResponse from(AlarmSetting s) {
        if (s == null) {
            return new AlarmSettingResponse(null, null, null, null);
        }
        return new AlarmSettingResponse(
                s.getExpiringAheadDays(), s.getMissTolerateMin(),
                s.getEnvThreshold(), s.getUpdatedAt());
    }
}
