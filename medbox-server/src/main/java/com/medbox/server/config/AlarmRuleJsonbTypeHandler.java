package com.medbox.server.config;

import java.util.Map;
import tools.jackson.databind.JavaType;

/**
 * {@code med_plan.alarm_rule} 的 JSONB 处理器：反序列化为 {@code Map<String,Boolean>}
 * （如 {@code {"miss":true,"wrongDrug":false}}）。见 {@link JsonbTypeHandler} 的说明。
 */
public class AlarmRuleJsonbTypeHandler extends JsonbTypeHandler<Map<String, Boolean>> {

    public AlarmRuleJsonbTypeHandler() {
        super(MAPPER.getTypeFactory().constructParametricType(Map.class, String.class, Boolean.class));
    }
}
