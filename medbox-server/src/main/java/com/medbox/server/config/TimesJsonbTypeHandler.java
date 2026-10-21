package com.medbox.server.config;

import java.util.List;
import tools.jackson.databind.JavaType;

/**
 * {@code med_plan.times} 的 JSONB 处理器：反序列化为 {@code List<String>}
 * （如 {@code ["08:00","20:00"]}）。见 {@link JsonbTypeHandler} 的说明。
 */
public class TimesJsonbTypeHandler extends JsonbTypeHandler<List<String>> {

    public TimesJsonbTypeHandler() {
        super(MAPPER.getTypeFactory().constructParametricType(List.class, String.class));
    }
}
