package com.medbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
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
import com.medbox.server.support.TableInfoWarmup;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import com.medbox.server.ws.WsTopic;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@link PlanReminderScanner} 集成测试：真实 Spring 链路（Bean 装配 / 调度配置），
 * 仅把 Mapper 与判定协作组件换成替身，不连 PostgreSQL（H2 替换 DataSource）。
 *
 * <p>重点验：① 候选计划经 {@link PlanReminderMapper} 落去重记录、并经 {@link WsPushService}
 * 唯一出口推送 {@code REMINDER} 帧；② 去重命中（{@code dedup_key} 冲突）不二次推送。调度由
 * {@code SchedulingConfig} 装载（与 WS 开关解耦）。
 */
@SpringBootTest
class PlanReminderIntegrationTest {

    @TestConfiguration
    static class H2DataSourceConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-test-reminder")
                    .build();
        }
    }

    private static final String ELDER = "u-1001";
    private static final String PLAN = "p-1001";
    private static final String SLOT_INSTANT = "2026-10-23T00:05:00Z"; // 本地 08:05 Asia/Shanghai
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z"); // 本地 08:00

    @Autowired
    private PlanReminderScanner scanner;

    @MockitoBean
    private MedPlanMapper medPlanMapper;
    @MockitoBean
    private MedPlanItemMapper medPlanItemMapper;
    @MockitoBean
    private MedRecordMapper medRecordMapper;
    @MockitoBean
    private PlanReminderMapper planReminderMapper;
    @MockitoBean
    private DeviceMapper deviceMapper;
    @MockitoBean
    private MedicineMapper medicineMapper;
    @MockitoBean
    private DeviceTimezoneResolver timezoneResolver;
    @MockitoBean
    private PlanSlotResolver planSlotResolver;
    @MockitoBean
    private PlanReminderProperties planReminderProperties;
    @MockitoBean
    private WsPushService wsPushService;

    @BeforeEach
    void setUp() {
        // Mapper 被 MockitoBean 打桩，不跑真实 SQL；预热实体元数据仅为满足 MyBatis-Plus 上下文初始化
        TableInfoWarmup.init(MedPlan.class, MedPlanItem.class, Medicine.class, Device.class, PlanReminder.class);

        when(planReminderProperties.isEnabled()).thenReturn(true);
        when(planReminderProperties.getLeadTimeMinutes()).thenReturn(10L);
        when(planReminderProperties.isSkipWhenDeviceOffline()).thenReturn(false);
        when(timezoneResolver.resolve(ELDER)).thenReturn(ZoneId.of("Asia/Shanghai"));
        when(planSlotResolver.expandSlots(any(), any(), any(), any()))
                .thenReturn(List.of(OffsetDateTime.parse(SLOT_INSTANT)));
        when(medRecordMapper.countTaken(any(), any())).thenReturn(0);

        MedPlan plan = new MedPlan();
        plan.setPlanId(PLAN);
        plan.setElderId(ELDER);
        plan.setStatus("ENABLED");
        plan.setSyncState("PENDING");
        plan.setTimes(List.of("08:00"));
        when(medPlanMapper.selectDueForReminder()).thenReturn(List.of(plan));

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
        when(medPlanItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(a, b));

        Device online = new Device();
        online.setDeviceId("BOXA1");
        online.setOnlineState(OnlineState.ONLINE);
        when(deviceMapper.selectMainByElderId(ELDER)).thenReturn(online);

        Medicine m1 = new Medicine();
        m1.setMedicineId("m-1");
        m1.setName("阿莫西林");
        Medicine m2 = new Medicine();
        m2.setMedicineId("m-2");
        m2.setName("维生素D");
        when(medicineMapper.selectById("m-1")).thenReturn(m1);
        when(medicineMapper.selectById("m-2")).thenReturn(m2);
    }

    @Test
    @DisplayName("集成链路：未来 slot 落去重记录并经 WS 推送 REMINDER 帧")
    void scansAndPushesReminder() {
        scanner.scan(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

        verify(planReminderMapper, atLeastOnce()).insert(any(PlanReminder.class));
        verify(wsPushService).publish(eq(WsTopic.elder(ELDER)), eq(WsFrameType.REMINDER), any());
    }

    @Test
    @DisplayName("去重：同一槽重复扫描命中 dedup_key 唯一约束冲突，不二次推送")
    void dedupOnRescan() {
        scanner.scan(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

        // 第一次扫描已落 1 条并推送 1 次；第二次 insert 抛唯一约束冲突，应被内部吞掉、不再推送
        when(planReminderMapper.insert(any(PlanReminder.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));

        scanner.scan(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

        verify(planReminderMapper, atLeastOnce()).insert(any(PlanReminder.class));
        verify(wsPushService, times(1)).publish(anyString(), eq(WsFrameType.REMINDER), any());
    }
}
