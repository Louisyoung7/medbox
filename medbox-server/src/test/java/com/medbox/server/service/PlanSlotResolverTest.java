package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medbox.server.domain.PlanSlot;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 固定药格槽展开 / 按格就近槽的规则表测试（07 §1.2、§2、§4）。 */
class PlanSlotResolverTest {

    private final PlanSlotResolver resolver = new PlanSlotResolver();
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static PlanSlot slot(int cellNo, String time) {
        PlanSlot s = new PlanSlot();
        s.setCellNo(cellNo);
        s.setTime(time);
        return s;
    }

    @Test
    @DisplayName("展开：每个槽带格号，按设备时区把本地墙钟换成 UTC 瞬时")
    void 展开带格号的槽() {
        List<PlanSlotResolver.SlotInstant> slots = resolver.expandSlots(
                Instant.parse("2026-11-11T22:00:00Z"), Instant.parse("2026-11-12T02:00:00Z"),
                SHANGHAI, List.of(slot(1, "08:00"), slot(2, "09:00")));

        assertEquals(2, slots.size());
        assertEquals(1, slots.get(0).cellNo());
        assertEquals(Instant.parse("2026-11-12T00:00:00Z"), slots.get(0).slotTime().toInstant());
        assertEquals(2, slots.get(1).cellNo());
        assertEquals(Instant.parse("2026-11-12T01:00:00Z"), slots.get(1).slotTime().toInstant());
    }

    @Test
    @DisplayName("展开：非法格号或非法时间字符串跳过该槽，不影响其它槽")
    void 非法槽跳过() {
        List<PlanSlotResolver.SlotInstant> slots = resolver.expandSlots(
                Instant.parse("2026-11-11T22:00:00Z"), Instant.parse("2026-11-12T02:00:00Z"),
                SHANGHAI, List.of(slot(4, "08:00"), slot(1, "25:00"), slot(3, "10:00")));

        assertEquals(1, slots.size());
        assertEquals(3, slots.get(0).cellNo());
    }

    @Test
    @DisplayName("就近槽：只匹配同格号的槽；该格无排程返回 null、on_time 也无判定依据")
    void 按格就近槽() {
        List<PlanSlot> slots = List.of(slot(1, "08:00"), slot(2, "12:30"));
        Instant at = Instant.parse("2026-11-12T00:05:00Z");

        assertEquals(Instant.parse("2026-11-12T00:00:00Z"),
                resolver.nearestSlot(at, SHANGHAI, slots, 1).toInstant());
        assertNull(resolver.nearestSlot(at, SHANGHAI, slots, 3));
        assertNull(resolver.nearestSlot(at, SHANGHAI, slots, 4));
    }

    @Test
    @DisplayName("格号边界：仅 1/2/3 合法")
    void 格号边界() {
        assertTrue(PlanSlotResolver.isValidCellNo(1));
        assertTrue(PlanSlotResolver.isValidCellNo(2));
        assertTrue(PlanSlotResolver.isValidCellNo(3));
        assertFalse(PlanSlotResolver.isValidCellNo(0));
        assertFalse(PlanSlotResolver.isValidCellNo(4));
        assertFalse(PlanSlotResolver.isValidCellNo(null));
    }
}
