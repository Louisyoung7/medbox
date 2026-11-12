package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medbox.server.domain.Device;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedRecord;
import com.medbox.server.domain.PlanSlot;
import com.medbox.server.dto.capture.CaptureDispenseInput;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.MedRecordMapper;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** 服药事件判定核心（07 §1）：按格准入、按格就近槽、库存不联动、领域事件带格号。 */
class DispenseConsumeServiceTest {

    private static final String CAMERA_ID = "BOXA-01-S-CAM01";
    private static final String MAIN_ID = "BOXA-01";
    private static final String ELDER_ID = "u-1001";
    private static final String PLAN_ID = "p-1001";

    private final MedRecordMapper medRecordMapper = mock(MedRecordMapper.class);
    private final MedPlanMapper medPlanMapper = mock(MedPlanMapper.class);
    private final DeviceMapper deviceMapper = mock(DeviceMapper.class);
    private final DeviceTimezoneResolver timezoneResolver = mock(DeviceTimezoneResolver.class);
    private final MissToleranceResolver toleranceResolver = mock(MissToleranceResolver.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final PlanSlotResolver slotResolver = new PlanSlotResolver();
    private final DispenseConsumeService service = new DispenseConsumeService(
            medRecordMapper, medPlanMapper, deviceMapper, timezoneResolver,
            toleranceResolver, slotResolver, eventPublisher);

    @BeforeEach
    void setUp() {
        Device camera = new Device();
        camera.setDeviceId(CAMERA_ID);
        camera.setParentDeviceId(MAIN_ID);
        Device main = new Device();
        main.setDeviceId(MAIN_ID);
        main.setElderId(ELDER_ID);
        when(deviceMapper.selectOne(any())).thenReturn(camera, main);
        when(timezoneResolver.resolve(anyString())).thenReturn(ZoneId.of("Asia/Shanghai"));
        when(toleranceResolver.resolveMinutes(any(), anyString())).thenReturn(15);
        when(medRecordMapper.nextRecordId()).thenReturn("mr-1001");
        when(medRecordMapper.insert(any(MedRecord.class))).thenReturn(1);
    }

    private static MedPlan plan(String elderId, List<PlanSlot> slots) {
        MedPlan p = new MedPlan();
        p.setPlanId(PLAN_ID);
        p.setElderId(elderId);
        p.setSlots(slots);
        p.setStatus("ENABLED");
        return p;
    }

    private static PlanSlot slot(int cellNo, String time) {
        PlanSlot s = new PlanSlot();
        s.setCellNo(cellNo);
        s.setTime(time);
        return s;
    }

    private static CaptureDispenseInput input(String planId, Integer cellNo) {
        CaptureDispenseInput i = new CaptureDispenseInput();
        i.setCameraDeviceId(CAMERA_ID);
        i.setCaptureId("cap-1001");
        i.setPlanId(planId);
        i.setCellNo(cellNo);
        i.setTakenResult("TAKEN");
        i.setCapturedAt(OffsetDateTime.parse("2026-11-12T00:05:00Z"));
        i.setSource("CAMERA");
        return i;
    }

    @Test
    @DisplayName("合法格号：落记录带 cell_no，按该格就近槽重算 plan_time，事件带格号")
    void 合法上报落记录() {
        when(medPlanMapper.selectOne(any())).thenReturn(
                plan(ELDER_ID, List.of(slot(1, "08:00"), slot(2, "12:30"))));

        Optional<MedRecord> result = service.consume(input(PLAN_ID, 1));

        assertTrue(result.isPresent());
        MedRecord record = result.get();
        assertEquals(1, record.getCellNo());
        assertEquals("TAKEN", record.getTakenResult());
        assertNotNull(record.getPlanTime());
        assertEquals(java.time.Instant.parse("2026-11-12T00:00:00Z"),
                record.getPlanTime().toInstant());
        assertEquals(Boolean.TRUE, record.getOnTime());

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertTrue(event.getValue() instanceof DispenseRecordedEvent);
        assertEquals(1, ((DispenseRecordedEvent) event.getValue()).getCellNo());
    }

    @Test
    @DisplayName("该格无排程：仍落记录，但 plan_time / on_time 为 null")
    void 该格无排程() {
        when(medPlanMapper.selectOne(any())).thenReturn(plan(ELDER_ID, List.of(slot(1, "08:00"))));

        MedRecord record = service.consume(input(PLAN_ID, 3)).orElseThrow();

        assertEquals(3, record.getCellNo());
        assertNull(record.getPlanTime());
        assertNull(record.getOnTime());
    }

    @Test
    @DisplayName("准入：planId 为空或格号越界 → 丢弃，不落记录、不发事件")
    void 准入丢弃() {
        assertTrue(service.consume(input(null, 1)).isEmpty());
        assertTrue(service.consume(input(PLAN_ID, 4)).isEmpty());
        assertTrue(service.consume(input(PLAN_ID, null)).isEmpty());
        verify(medRecordMapper, never()).insert(any(MedRecord.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("准入：计划归属老人与设备不一致（跨老人伪造）→ 丢弃")
    void 跨老人伪造丢弃() {
        when(medPlanMapper.selectOne(any())).thenReturn(plan("u-9999", List.of(slot(1, "08:00"))));

        assertTrue(service.consume(input(PLAN_ID, 1)).isEmpty());
        verify(medRecordMapper, never()).insert(any(MedRecord.class));
    }

    @Test
    @DisplayName("准入：计划不存在 → 丢弃；DISABLED 计划仍落服药事实")
    void 停用计划仍落记录() {
        when(medPlanMapper.selectOne(any())).thenReturn(null);
        assertTrue(service.consume(input(PLAN_ID, 1)).isEmpty());

        MedPlan disabled = plan(ELDER_ID, List.of(slot(1, "08:00")));
        disabled.setStatus("DISABLED");
        when(medPlanMapper.selectOne(any())).thenReturn(disabled);
        assertTrue(service.consume(input(PLAN_ID, 1)).isPresent());
    }

    @Test
    @DisplayName("takenResult 非法 / 缺失兜底 UNTAKEN，不产生第三态")
    void 取药结论归一() {
        assertEquals("TAKEN", DispenseConsumeService.normalizeTakenResult(" taken "));
        assertEquals("UNTAKEN", DispenseConsumeService.normalizeTakenResult(null));
        assertEquals("UNTAKEN", DispenseConsumeService.normalizeTakenResult("UNCERTAIN"));
    }
}
