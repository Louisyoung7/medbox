package com.medbox.server.config;

import com.medbox.server.domain.PlanSlot;
import java.util.List;

/**
 * {@code med_plan.slots} 的 JSONB 处理器：反序列化为 {@code List<PlanSlot>}
 * （如 {@code [{"cellNo":1,"time":"08:00"}]}）。见 {@link JsonbTypeHandler} 的说明。
 */
public class SlotsJsonbTypeHandler extends JsonbTypeHandler<List<PlanSlot>> {

    public SlotsJsonbTypeHandler() {
        super(MAPPER.getTypeFactory().constructParametricType(List.class, PlanSlot.class));
    }
}
