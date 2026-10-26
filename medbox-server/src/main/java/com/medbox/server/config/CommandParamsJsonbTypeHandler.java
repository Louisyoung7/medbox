package com.medbox.server.config;

import java.util.Map;

/**
 * {@code device_command.params}（JSONB）的类型处理器：把列焊死成 {@code Map<String, Object>}。
 * 泛型基类无法被 MyBatis 无参实例化，故按 {@link TimesJsonbTypeHandler} 同法补一个具体子类。
 */
public class CommandParamsJsonbTypeHandler extends JsonbTypeHandler<Map<String, Object>> {

    public CommandParamsJsonbTypeHandler() {
        super(MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
    }
}
