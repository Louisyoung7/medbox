package com.medbox.server.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.PlanSlot;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.testsupport.PostgresInfraTestBase;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link MedPlanMapper} 的真实 SQL 验证：业务号序列、JSONB（{@code slots} / {@code alarm_rule}）
 * 往返，以及漏服 / 提醒扫描的过滤口径（只取 ENABLED 且非 SYNC_FAILED）。
 */
@Tag("infra")
@DisplayName("MedPlanMapper：真实 PostgreSQL 序列、JSONB 与扫描过滤")
class MedPlanMapperInfraTest extends PostgresInfraTestBase {

    @Autowired
    private MedPlanMapper medPlanMapper;

    @Autowired
    private UserMapper userMapper;

    @Test
    @DisplayName("nextPlanId 返回 p- 业务号且递增")
    void nextPlanIdReturnsIncreasingBizId() {
        String first = medPlanMapper.nextPlanId();
        String second = medPlanMapper.nextPlanId();

        assertTrue(first.matches("p-\\d+"), "业务号格式不对：" + first);
        assertTrue(bizNo(first) < bizNo(second), "业务号应递增：" + first + " -> " + second);
    }

    @Test
    @DisplayName("slots / alarm_rule 的 JSONB 写入后可按主键读回")
    void jsonbColumnsRoundTrip() {
        MedPlan plan = newPlan("ENABLED", "PENDING");

        PlanSlot morning = new PlanSlot();
        morning.setCellNo(1);
        morning.setTime("08:00");
        PlanSlot evening = new PlanSlot();
        evening.setCellNo(3);
        evening.setTime("20:00");
        plan.setSlots(List.of(morning, evening));
        plan.setAlarmRule(Map.of("miss", true, "env", false));

        assertEquals(1, medPlanMapper.insert(plan));
        assertNotNull(plan.getId(), "自增主键应回填");

        MedPlan loaded = medPlanMapper.selectById(plan.getId());
        assertNotNull(loaded);
        assertEquals(plan.getPlanId(), loaded.getPlanId());
        assertNotNull(loaded.getSlots(), "slots JSONB 未读回");
        assertEquals(2, loaded.getSlots().size());
        assertEquals(1, loaded.getSlots().get(0).getCellNo());
        assertEquals("08:00", loaded.getSlots().get(0).getTime());
        assertEquals(3, loaded.getSlots().get(1).getCellNo());
        assertEquals("20:00", loaded.getSlots().get(1).getTime());
        assertEquals(Boolean.TRUE, loaded.getAlarmRule().get("miss"));
        assertEquals(Boolean.FALSE, loaded.getAlarmRule().get("env"));
    }

    @Test
    @DisplayName("扫描只取 ENABLED 且 sync_state 非 SYNC_FAILED 的计划")
    void scanSkipsDisabledAndSyncFailed() {
        MedPlan enabled = newPlan("ENABLED", "PENDING");
        MedPlan disabled = newPlan("DISABLED", "PENDING");
        MedPlan syncFailed = newPlan("ENABLED", "SYNC_FAILED");
        medPlanMapper.insert(enabled);
        medPlanMapper.insert(disabled);
        medPlanMapper.insert(syncFailed);

        Set<String> missScanIds = medPlanMapper.selectDueForMissScan().stream()
                .map(MedPlan::getPlanId).collect(Collectors.toSet());
        Set<String> reminderScanIds = medPlanMapper.selectDueForReminder().stream()
                .map(MedPlan::getPlanId).collect(Collectors.toSet());

        for (Set<String> ids : List.of(missScanIds, reminderScanIds)) {
            assertTrue(ids.contains(enabled.getPlanId()), "ENABLED + PENDING 应参与扫描");
            assertFalse(ids.contains(disabled.getPlanId()), "DISABLED 不应参与扫描");
            assertFalse(ids.contains(syncFailed.getPlanId()), "SYNC_FAILED 不应参与扫描");
        }
    }

    /** 造一个 elder_id 唯一（uk_med_plan_elder）的计划，slots 必填。 */
    private MedPlan newPlan(String status, String syncState) {
        String elderId = userMapper.nextUserId();
        PlanSlot slot = new PlanSlot();
        slot.setCellNo(1);
        slot.setTime("08:00");

        MedPlan plan = new MedPlan();
        plan.setPlanId(medPlanMapper.nextPlanId());
        plan.setElderId(elderId);
        plan.setName("infra-" + plan.getPlanId());
        plan.setSlots(List.of(slot));
        plan.setRepeatRule("DAILY");
        plan.setStatus(status);
        plan.setSyncState(syncState);
        return plan;
    }

    private static long bizNo(String planId) {
        return Long.parseLong(planId.substring("p-".length()));
    }
}
