package com.medbox.server.config;

import com.medbox.server.domain.EnvThreshold;
import tools.jackson.databind.JavaType;

/**
 * {@code alarm_setting.env_threshold} 的 JSONB 处理器：反序列化为 {@link EnvThreshold} 值对象
 * （如 {@code {"tempMin":10,"tempMax":30,"humiMin":30,"humiMax":70,"luxMax":500}}）。
 * 见 {@link JsonbTypeHandler} 的说明。
 */
public class EnvThresholdJsonbTypeHandler extends JsonbTypeHandler<EnvThreshold> {

    public EnvThresholdJsonbTypeHandler() {
        super(MAPPER.getTypeFactory().constructType(EnvThreshold.class));
    }
}
