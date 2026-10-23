package com.medbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.config.AlarmProperties;
import com.medbox.server.domain.Alarm;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedPlanItem;
import com.medbox.server.domain.MedRecord;
import com.medbox.server.domain.Medicine;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.mapper.AlarmMapper;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.MedPlanItemMapper;
import com.medbox.server.mapper.MedRecordMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.MedicineMapper;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import com.medbox.server.ws.WsTopic;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

/**
 * {@link MissAlarmScanner} 单元测试：纯 Mockito，不连库、不启 Spring。
 *
 * <p>重点验四件事：① 任一 item 缺失即生成 1 条 MISS（message 列出全部缺失药）；
 * ② 全部 item 都有记录时不告警；③ {@code dedup_key} 冲突命中跳过、不重复推 WS；
 * ④ 设备离线 / 无 MAIN 设备时跳过（{@code skipWhenDeviceOffline=true}）。
 */
class MissAlarmScannerTest {

    private static final String ELDER = "u-1001";
    private static final String PLAN = "p-1001";
    private static final String SLOT_INSTANT = "2026-10-23T00:00:00Z"; // 本地 08:00 Asia/Shanghai
    private static final Instant NOW = Instant.parse("2026-10-23T00:30:00Z"); // 本地 08:30，已过 15 分钟容忍

    private final MedPlanMapper medPlanMapper = mock(MedPlanMapper.class);
    private final MedPlanItemMapper medPlanItemMapper = mock(MedPlanItemMapper.class);
    private final MedRecordMapper medRecordMapper = mock(MedRecordMapper.class);
    private final AlarmMapper alarmMapper = mock(AlarmMapper.class);
    private final DeviceMapper deviceMapper = mock(DeviceMapper.class);
    private final MedicineMapper medicineMapper = mock(MedicineMapper.class);
    private final MissToleranceResolver toleranceResolver = mock(MissToleranceResolver.class);
    private final DeviceTimezoneResolver timezoneResolver = mock(DeviceTimezoneResolver.class);
    private final PlanSlotResolver planSlotResolver = mock(PlanSlotResolver.class);
    private final AlarmProperties props = new AlarmProperties();
    private final ObjectProvider<WsPushService> wsProvider = mock(ObjectProvider.class);

    private final WsPushService wsPushService = mock(WsPushService.class);

    private MissAlarmScanner scanner;

    /** 本地 08:00（Asia/Shanghai，+08:00 偏移），对应 UTC 00:00 = SLOT_INSTANT。 */
    private OffsetDateTime slot() {
        return OffsetDateTime.of(2026, 10, 23, 8, 0, 0, 0, ZoneOffset.ofHours(8));
    }

    @BeforeEach
    void setUp() {
        props.setEnabled(true);
        props.setLookbackMinutes(1440);
        props.setSkipWhenDeviceOffline(true);
        when(wsProvider.getIfAvailable()).thenReturn(wsPushService);
        when(alarmMapper.nextAlarmId()).thenReturn("a-1001");
        when(toleranceResolver.resolveMinutes(any(), anyString())).thenReturn(15);
        when(timezoneResolver.resolve(ELDER)).thenReturn(ZoneId.of("Asia/Shanghai"));
        // 默认：窗口内只展开出 1 个槽（本地 08:00，+08:00 偏移）
        when(planSlotResolver.expandSlots(any(), any(), any(), any()))
                .thenReturn(List.of(slot()));
        when(medPlanItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(twoItems());
        when(deviceMapper.selectMainByElderId(ELDER)).thenReturn(onlineDevice());

        scanner = new MissAlarmScanner(medPlanMapper, medPlanItemMapper, medRecordMapper, alarmMapper,
                deviceMapper, medicineMapper, toleranceResolver, timezoneResolver, planSlotResolver,
                props, wsProvider);
    }

    private MedPlan enabledPlan() {
        MedPlan p = new MedPlan();
        p.setPlanId(PLAN);
        p.setElderId(ELDER);
        p.setStatus("ENABLED");
        p.setSyncState("PENDING");
        p.setTimes(List.of("08:00"));
        return p;
    }

    private List<MedPlanItem> twoItems() {
        MedPlanItem a = new MedPlanItem();
        a.setItemId("pi-1");
        a.setMedicineId("m-1");
        MedPlanItem b = new MedPlanItem();
        b.setItemId("pi-2");
        b.setMedicineId("m-2");
        return List.of(a, b);
    }

    private Device onlineDevice() {
        Device d = new Device();
        d.setDeviceId("BOXA1");
        d.setOnlineState(OnlineState.ONLINE);
        return d;
    }

    private void stubMedicine(String id, String name) {
        Medicine m = new Medicine();
        m.setMedicineId(id);
        m.setName(name);
        when(medicineMapper.selectById(id)).thenReturn(m);
    }

    @Test
    @DisplayName("全部缺失：生成 1 条 MISS，message 列出全部缺失药，并经 WS 推送")
    void allMissingRaisesOneMiss() {
        when(medPlanMapper.selectDueForMissScan()).thenReturn(List.of(enabledPlan()));
        when(medRecordMapper.selectByPlanAndTimeRange(any(), any(), any())).thenReturn(List.of());
        stubMedicine("m-1", "阿莫西林");
        stubMedicine("m-2", "维生素D");

        scanner.scan(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

        ArgumentCaptor<Alarm> captor = ArgumentCaptor.forClass(Alarm.class);
        verify(alarmMapper).insert(captor.capture());
        Alarm alarm = captor.getValue();
        assertThat(alarm.getType()).isEqualTo("MISS");
        assertThat(alarm.getLevel()).isEqualTo("WARN");
        assertThat(alarm.getElderId()).isEqualTo(ELDER);
        assertThat(alarm.getPlanId()).isEqualTo(PLAN);
        assertThat(alarm.getMedicineId()).isEqualTo("m-1"); // 第一个缺失药
        assertThat(alarm.getDedupKey()).isEqualTo("MISS:" + PLAN + ":" + SLOT_INSTANT);
        assertThat(alarm.getMessage()).contains("08:00").contains("阿莫西林").contains("维生素D");

        verify(wsPushService).publish(eq(WsTopic.elder(ELDER)), eq(WsFrameType.ALARM), any());
    }

    @Test
    @DisplayName("全部 item 都有记录：不生成告警、不推送")
    void allPresentNoAlarm() {
        when(medPlanMapper.selectDueForMissScan()).thenReturn(List.of(enabledPlan()));
        MedRecord r1 = new MedRecord();
        r1.setPlanItemId("pi-1");
        r1.setPlanTime(slot());
        MedRecord r2 = new MedRecord();
        r2.setPlanItemId("pi-2");
        r2.setPlanTime(slot());
        when(medRecordMapper.selectByPlanAndTimeRange(any(), any(), any())).thenReturn(List.of(r1, r2));

        scanner.scan(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

        verify(alarmMapper, never()).insert(any(Alarm.class));
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("部分缺失：仍判 MISS（只缺一种）")
    void partialMissingRaisesMiss() {
        when(medPlanMapper.selectDueForMissScan()).thenReturn(List.of(enabledPlan()));
        MedRecord r1 = new MedRecord();
        r1.setPlanItemId("pi-1");
        r1.setPlanTime(slot());
        when(medRecordMapper.selectByPlanAndTimeRange(any(), any(), any())).thenReturn(List.of(r1));
        stubMedicine("m-2", "维生素D");

        scanner.scan(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

        verify(alarmMapper).insert(any(Alarm.class));
        verify(wsPushService).publish(any(), eq(WsFrameType.ALARM), any());
    }

    @Test
    @DisplayName("去重：dedup_key 冲突命中则跳过，不重复落库也不重复推送")
    void dedupConflictSkips() {
        when(medPlanMapper.selectDueForMissScan()).thenReturn(List.of(enabledPlan()));
        when(medRecordMapper.selectByPlanAndTimeRange(any(), any(), any())).thenReturn(List.of());
        stubMedicine("m-1", "阿莫西林");
        stubMedicine("m-2", "维生素D");
        when(alarmMapper.insert(any(Alarm.class))).thenThrow(new DuplicateKeyException("dup dedup_key"));

        scanner.scan(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

        // insert 被调用（随后抛唯一索引冲突），但异常被内部吞掉、不再推送 WS
        verify(alarmMapper, atLeastOnce()).insert(any(Alarm.class));
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("设备离线（skipWhenDeviceOffline=true）：跳过该老人，不告警")
    void offlineDeviceSkipped() {
        props.setSkipWhenDeviceOffline(true);
        Device offline = new Device();
        offline.setDeviceId("BOXA1");
        offline.setOnlineState(OnlineState.OFFLINE);
        when(deviceMapper.selectMainByElderId(ELDER)).thenReturn(offline);
        when(medPlanMapper.selectDueForMissScan()).thenReturn(List.of(enabledPlan()));

        scanner.scan(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

        verify(alarmMapper, never()).insert(any(Alarm.class));
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("enabled=false：扫描整体不跑")
    void disabledNoScan() {
        props.setEnabled(false);
        when(medPlanMapper.selectDueForMissScan()).thenReturn(List.of(enabledPlan()));

        scanner.scan(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

        verify(medPlanMapper, never()).selectDueForMissScan();
        verify(alarmMapper, never()).insert(any(Alarm.class));
    }
}
