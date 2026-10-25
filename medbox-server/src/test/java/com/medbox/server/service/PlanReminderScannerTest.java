package com.medbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.config.PlanReminderProperties;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedPlanItem;
import com.medbox.server.domain.Medicine;
import com.medbox.server.domain.PlanReminder;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.MedPlanItemMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.MedRecordMapper;
import com.medbox.server.mapper.MedicineMapper;
import com.medbox.server.mapper.PlanReminderMapper;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import com.medbox.server.ws.WsTopic;
import com.medbox.server.ws.payload.ReminderPayload;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

/**
 * {@link PlanReminderScanner} 单元测试：纯 Mockito，不连库、不启 Spring。
 *
 * <p>重点验四件事：① 未来窗口内的 slot 推 {@code REMINDER} 帧且 {@code medicines[]} 带全；
 * ② 已服（{@code med_record} 存在）跳过；③ 去重表冲突命中跳过、不重复推；
 * ④ 设备离线（{@code skipWhenDeviceOffline=true}）跳过；⑤ {@code enabled=false} 整体不跑。
 */
class PlanReminderScannerTest {

    private static final String ELDER = "u-1001";
    private static final String PLAN = "p-1001";
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z"); // 本地 08:00 Asia/Shanghai

    private final MedPlanMapper medPlanMapper = mock(MedPlanMapper.class);
    private final MedPlanItemMapper medPlanItemMapper = mock(MedPlanItemMapper.class);
    private final MedRecordMapper medRecordMapper = mock(MedRecordMapper.class);
    private final PlanReminderMapper planReminderMapper = mock(PlanReminderMapper.class);
    private final DeviceMapper deviceMapper = mock(DeviceMapper.class);
    private final MedicineMapper medicineMapper = mock(MedicineMapper.class);
    private final DeviceTimezoneResolver timezoneResolver = mock(DeviceTimezoneResolver.class);
    private final PlanSlotResolver planSlotResolver = mock(PlanSlotResolver.class);
    private final PlanReminderProperties props = new PlanReminderProperties();
    private final ObjectProvider<WsPushService> wsProvider = mock(ObjectProvider.class);
    private final WsPushService wsPushService = mock(WsPushService.class);

    private PlanReminderScanner scanner;

    private OffsetDateTime futureSlot() {
        // 本地 08:05（Asia/Shanghai，+08:00 偏移），对应 UTC 00:05，落在 [now, now+10min] 内
        return OffsetDateTime.of(2026, 10, 23, 8, 5, 0, 0, ZoneOffset.ofHours(8));
    }

    @BeforeEach
    void setUp() {
        props.setEnabled(true);
        props.setLeadTimeMinutes(10);
        props.setSkipWhenDeviceOffline(false);
        when(wsProvider.getIfAvailable()).thenReturn(wsPushService);
        when(timezoneResolver.resolve(ELDER)).thenReturn(ZoneId.of("Asia/Shanghai"));
        when(planSlotResolver.expandSlots(any(), any(), any(), any())).thenReturn(List.of(futureSlot()));
        when(medPlanItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(twoItems());
        when(deviceMapper.selectMainByElderId(ELDER)).thenReturn(onlineDevice());

        scanner = new PlanReminderScanner(medPlanMapper, medPlanItemMapper, medRecordMapper,
                planReminderMapper, deviceMapper, medicineMapper, timezoneResolver, planSlotResolver,
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
        a.setDose("1");
        a.setUnit("片");
        MedPlanItem b = new MedPlanItem();
        b.setItemId("pi-2");
        b.setMedicineId("m-2");
        b.setDose("2");
        b.setUnit("粒");
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
    @DisplayName("未来窗口内的 slot：推 REMINDER 帧，medicines[] 带全两种药，planTime 为 UTC")
    void futureSlotPushesReminder() {
        when(medPlanMapper.selectDueForReminder()).thenReturn(List.of(enabledPlan()));
        when(medRecordMapper.countTaken(any(), any())).thenReturn(0);
        stubMedicine("m-1", "阿莫西林");
        stubMedicine("m-2", "维生素D");

        scanner.scan(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

        ArgumentCaptor<ReminderPayload> captor = ArgumentCaptor.forClass(ReminderPayload.class);
        verify(wsPushService).publish(eq(WsTopic.elder(ELDER)), eq(WsFrameType.REMINDER), captor.capture());
        ReminderPayload payload = captor.getValue();
        assertThat(payload.planId()).isEqualTo(PLAN);
        assertThat(payload.planTime()).isEqualTo("2026-10-23T00:05:00Z"); // 本地 08:05 = UTC 00:05
        assertThat(payload.medicines()).hasSize(2);
        assertThat(payload.medicines()).extracting(ReminderPayload.ReminderMedicine::medicineName)
                .containsExactlyInAnyOrder("阿莫西林", "维生素D");
        assertThat(payload.medicines()).extracting(ReminderPayload.ReminderMedicine::dose)
                .containsExactlyInAnyOrder("1", "2");
    }

    @Test
    @DisplayName("窗口内无未来 slot：不推送、不落去重")
    void noSlotInWindowNoPush() {
        when(medPlanMapper.selectDueForReminder()).thenReturn(List.of(enabledPlan()));
        when(planSlotResolver.expandSlots(any(), any(), any(), any())).thenReturn(List.of());

        scanner.scan(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

        verify(planReminderMapper, never()).insert(any(PlanReminder.class));
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("已服跳过：该 slot 已有 med_record，不提醒")
    void alreadyTakenSkips() {
        when(medPlanMapper.selectDueForReminder()).thenReturn(List.of(enabledPlan()));
        when(medRecordMapper.countTaken(any(), any())).thenReturn(1);

        scanner.scan(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

        verify(planReminderMapper, never()).insert(any(PlanReminder.class));
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("去重：dedup_key 冲突命中则跳过，不再推送")
    void dedupConflictSkips() {
        when(medPlanMapper.selectDueForReminder()).thenReturn(List.of(enabledPlan()));
        when(medRecordMapper.countTaken(any(), any())).thenReturn(0);
        stubMedicine("m-1", "阿莫西林");
        stubMedicine("m-2", "维生素D");
        when(planReminderMapper.insert(any(PlanReminder.class))).thenThrow(new DuplicateKeyException("dup dedup_key"));

        scanner.scan(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

        // insert 被调用（随后抛唯一索引冲突），但异常被内部吞掉、不再推送 WS
        verify(planReminderMapper, atLeastOnce()).insert(any(PlanReminder.class));
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("设备离线（skipWhenDeviceOffline=true）：跳过，不提醒")
    void offlineDeviceSkipped() {
        props.setSkipWhenDeviceOffline(true);
        Device offline = new Device();
        offline.setDeviceId("BOXA1");
        offline.setOnlineState(OnlineState.OFFLINE);
        when(deviceMapper.selectMainByElderId(ELDER)).thenReturn(offline);
        when(medPlanMapper.selectDueForReminder()).thenReturn(List.of(enabledPlan()));

        scanner.scan(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

        verify(planReminderMapper, never()).insert(any(PlanReminder.class));
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("enabled=false：扫描整体不跑")
    void disabledNoScan() {
        props.setEnabled(false);
        when(medPlanMapper.selectDueForReminder()).thenReturn(List.of(enabledPlan()));

        scanner.scan(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

        verify(medPlanMapper, never()).selectDueForReminder();
        verify(wsPushService, never()).publish(any(), any(), any());
    }
}
