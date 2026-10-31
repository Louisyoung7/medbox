package com.medbox.server.service;

import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * 依从性统计周期枚举（见文档 02 第 7 章、07 的 {@code feat/record-report}）。
 *
 * <p>采用<b>滚动窗口</b>：从「当前时刻」往前推固定时长，窗口终点恒为 now。时区对窗口长度无影响
 * （固定 24h / 7d / 30d），仅在 {@link AdherenceService} 做本地日分桶时才用到老人设备时区。
 */
public enum AdherencePeriod {

    /** 最近 24 小时。 */
    DAY(Duration.ofDays(1)),

    /** 最近 7 天。 */
    WEEK(Duration.ofDays(7)),

    /** 最近 30 天。 */
    MONTH(Duration.ofDays(30));

    private final Duration back;

    AdherencePeriod(Duration back) {
        this.back = back;
    }

    /**
     * 解析周期参数；非法（含 null / 空串）→ {@link ErrorCode#BAD_REQUEST}（40001）。
     */
    public static AdherencePeriod of(String value) {
        if (value == null || value.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "period 不能为空，需为 day|week|month");
        }
        try {
            return AdherencePeriod.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "period 非法，需为 day|week|month: " + value);
        }
    }

    /** 窗口起点 = now - 滚动时长。 */
    public Instant start(Instant now) {
        return now.minus(back);
    }
}
