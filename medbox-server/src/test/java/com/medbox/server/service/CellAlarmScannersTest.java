package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medbox.server.config.AlarmProperties;
import com.medbox.server.config.PlanReminderProperties;
import com.medbox.server.domain.Alarm;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedRecord;
import com.medbox.server.domain.PlanReminder;
import com.medbox.server.domain.PlanSlot;
import com.medbox.server.mapper.AlarmMapper;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.MedRecordMapper;
import com.medbox.server.mapper.PlanReminderMapper;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import com.medbox.server.ws.payload.ReminderPayload;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** MISS / REMINDER 按格判定与去重键（07 §2、§4）。 */
@SuppressWarnings("unchecked")
class CellAlarmScannersTest {

    private static final String ELDER_ID = "u-1001";
    private static final String PLAN_ID = "p-1001";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final MedPlanMapper medPlanMapper = mock(MedPlanMapper.class);
    private final MedRecordMapper medRecordMapper = mock(MedRecordMapper.class);
    private final AlarmMapper alarmMapper = mock(AlarmMapper.class);
    private final PlanReminderMapper planReminderMapper = mock(PlanReminderMapper.class);
    private final DeviceMapper deviceMapper = mock(DeviceMapper.class);
    private final DeviceTimezoneResolver timezoneResolver = mock(DeviceTimezoneResolver.class);
    private final MissToleranceResolver toleranceResolver = mock(MissToleranceResolver.class);
    private final AlarmProperties alarmProperties = mock(AlarmProperties.class);
    private final PlanReminderProperties reminderProperties = mock(PlanReminderProperties.class);
    private final PlanSlotResolver slotResolver = new PlanSlotResolver();

    private static PlanSlot slot(int cellNo, String time) {
        PlanSlot s = new PlanSlot();
        s.setCellNo(cellNo);
        s.setTime(time);
        return s;
    }

    private static MedPlan plan() {
        MedPlan p = new MedPlan();
        p.setPlanId(PLAN_ID);
        p.setElderId(ELDER_ID);
        p.setSlots(List.of(slot(1, "08:00"), slot(2, "12:30")));
        p.setStatus("ENABLED");
        return p;
    }

    private static Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private void commonStubs() {
        when(timezoneResolver.resolve(anyString())).thenReturn(SHANGHAI);
        when(toleranceResolver.resolveMinutes(any(), anyString())).thenReturn(15);
        when(alarmProperties.isEnabled()).thenReturn(true);
        when(alarmProperties.getLookbackMinutes()).thenReturn(1440L);
        when(alarmProperties.isSkipWhenDeviceOffline()).thenReturn(false);
        when(reminderProperties.isEnabled()).thenReturn(true);
        when(reminderProperties.getLeadTimeMinutes()).thenReturn(10L);
        when(reminderProperties.isSkipWhenDeviceOffline()).thenReturn(false);
    }

    @Test
    @DisplayName("MISS：某格某槽无任何记录 → 告警只提格号时间、dedup_key 带格号")
    void 漏服按格判定() {
        commonStubs();
        when(medPlanMapper.selectDueForMissScan()).thenReturn(List.of(plan()));
        when(medRecordMapper.selectByPlanAndTimeRange(any(), any(), any())).thenReturn(List.of());
        when(alarmMapper.nextAlarmId()).thenReturn("a-1001");
        when(alarmMapper.insert(any(Alarm.class))).thenReturn(1);
        ObjectProvider<WsPushService> ws = mock(ObjectProvider.class);
        when(ws.getIfAvailable()).thenReturn(null);

        new MissAlarmScanner(medPlanMapper, medRecordMapper, alarmMapper, deviceMapper,
                toleranceResolver, timezoneResolver, slotResolver, alarmProperties, ws)
                .scan(fixedClock("2026-11-12T04:00:00Z"));

        ArgumentCaptor<Alarm> captor = ArgumentCaptor.forClass(Alarm.class);
        verify(alarmMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        Alarm alarm = captor.getValue();
        assertEquals(1, alarm.getCellNo());
        assertEquals("MISS", alarm.getType());
        assertTrue(alarm.getMessage().contains("1 号格"));
        assertFalse(alarm.getMessage().contains("缺"));
        assertTrue(alarm.getDedupKey().startsWith("MISS:" + PLAN_ID + ":1:"));
    }

    @Test
    @DisplayName("MISS：该格该槽已有记录（TAKEN / UNTAKEN 都算）→ 不告警")
    void 有记录不告警() {
        commonStubs();
        when(medPlanMapper.selectDueForMissScan()).thenReturn(List.of(plan()));
        MedRecord record = new MedRecord();
        record.setCellNo(1);
        record.setPlanTime(OffsetDateTime.parse("2026-11-12T00:00:00Z"));
        record.setTakenResult("UNTAKEN");
        MedRecord second = new MedRecord();
        second.setCellNo(2);
        second.setPlanTime(OffsetDateTime.parse("2026-11-11T04:30:00Z"));
        second.setTakenResult("TAKEN");
        when(medRecordMapper.selectByPlanAndTimeRange(any(), any(), any()))
                .thenReturn(List.of(record, second));
        ObjectProvider<WsPushService> ws = mock(ObjectProvider.class);

        new MissAlarmScanner(medPlanMapper, medRecordMapper, alarmMapper, deviceMapper,
                toleranceResolver, timezoneResolver, slotResolver, alarmProperties, ws)
                .scan(fixedClock("2026-11-12T04:00:00Z"));

        verify(alarmMapper, never()).insert(any(Alarm.class));
    }

    @Test
    @DisplayName("REMINDER：去重键带格号，帧为 {planId, planTime, cellNo} 不含药品")
    void 提醒按格推送() {
        commonStubs();
        when(medPlanMapper.selectDueForReminder()).thenReturn(List.of(plan()));
        when(medRecordMapper.countTaken(any(), any(), any())).thenReturn(0);
        when(planReminderMapper.insert(any(PlanReminder.class))).thenReturn(1);
        WsPushService pusher = mock(WsPushService.class);
        ObjectProvider<WsPushService> ws = mock(ObjectProvider.class);
        when(ws.getIfAvailable()).thenReturn(pusher);

        new PlanReminderScanner(medPlanMapper, medRecordMapper, planReminderMapper, deviceMapper,
                timezoneResolver, slotResolver, reminderProperties, ws)
                .scan(fixedClock("2026-11-11T23:55:00Z"));

        ArgumentCaptor<PlanReminder> reminder = ArgumentCaptor.forClass(PlanReminder.class);
        verify(planReminderMapper).insert(reminder.capture());
        assertEquals(1, reminder.getValue().getCellNo());
        assertTrue(reminder.getValue().getDedupKey().startsWith("REMINDER:" + PLAN_ID + ":1:"));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(pusher).publish(anyString(), any(WsFrameType.class), payload.capture());
        assertTrue(payload.getValue() instanceof ReminderPayload);
        assertEquals(1, ((ReminderPayload) payload.getValue()).cellNo());
    }

    @Test
    @DisplayName("REMINDER：该格该槽已服 → 已服拦截，不写去重账本")
    void 已服跳过提醒() {
        commonStubs();
        when(medPlanMapper.selectDueForReminder()).thenReturn(List.of(plan()));
        when(medRecordMapper.countTaken(any(), any(), any())).thenReturn(1);
        ObjectProvider<WsPushService> ws = mock(ObjectProvider.class);

        new PlanReminderScanner(medPlanMapper, medRecordMapper, planReminderMapper, deviceMapper,
                timezoneResolver, slotResolver, reminderProperties, ws)
                .scan(fixedClock("2026-11-11T23:55:00Z"));

        verify(planReminderMapper, never()).insert(any(PlanReminder.class));
    }
}
