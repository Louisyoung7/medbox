package com.medbox.server.domain;

import java.util.Map;

/**
 * 老人级环境阈值（{@code alarm_setting.env_threshold} JSONB），结构见文档 06 的 2.10 / 01 的 5.4。
 *
 * <p>以值对象承载，反序列化为强类型字段而非裸 {@code Map}；序列化回 JSONB 时由
 * {@link com.medbox.server.config.EnvThresholdJsonbTypeHandler} 处理。
 *
 * <p>任意字段为 {@code null} 表示该项不启用上下限（判定时跳过）。温度 / 湿度为百分比或摄氏度，
 * 光照为 lux，单位以文档 01 的 5.4 为准。
 */
public record EnvThreshold(Integer tempMin, Integer tempMax,
                           Integer humiMin, Integer humiMax,
                           Integer luxMax) {

    /** 从 JSONB 反序列化用的宽松构造（允许缺字段）。 */
    public static EnvThreshold fromMap(Map<String, Integer> m) {
        if (m == null) {
            return null;
        }
        return new EnvThreshold(
                m.get("tempMin"), m.get("tempMax"),
                m.get("humiMin"), m.get("humiMax"),
                m.get("luxMax"));
    }
}
