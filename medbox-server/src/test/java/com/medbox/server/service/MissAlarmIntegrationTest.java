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
 * {@link MissAlarmScanner} 集成测试：真实 Spring 链路（Bean 装配 / 事务 / 调度配置），
 * 仅把 Mapper 与判定协作组件换成替身，不连 PostgreSQL（H2 替换 DataSource）。
 *
 * <p>重点验：① 真实 {@link Alarm} 实体经 MyBatis-Plus 落库路径（H2 下 LambdaInsert 走实体元数据，
 * 与真实 PG 一致，确保 {@code tableName} / 字段映射无误）；② 落库后通过 {@link WsPushService}
 * 唯一出口推送 ALARM 帧。调度由 {@code SchedulingConfig} 装载（与 WS 开关解耦）。
 */
@SpringBootTest
class MissAlarmIntegrationTest {

    @TestConfiguration
    static class H2DataSourceConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-test-miss")
                    .build();
        }
    }

    private static final String ELDER = "u-1001";
    private static final String PLAN = "p-1001";
    private static final String SLOT_INSTANT = "2026-10-23T00:00:00Z";
    private static final Instant NOW = Instant.parse("2026-10-23T00:30:00Z");

    @Autowired
    private MissAlarmScanner scanner;

    @MockitoBean
    private MedPlanMapper medPlanMapper;
    @MockitoBean
    private MedPlanItemMapper medPlanItemMapper;
    @MockitoBean
    private MedRecordMapper medRecordMapper;
    @MockitoBean
    private AlarmMapper alarmMapper;
    @MockitoBean
    private DeviceMapper deviceMapper;
    @MockitoBean
    private MedicineMapper medicineMapper;
    @MockitoBean
    private MissToleranceResolver toleranceResolver;
    @MockitoBean
    private DeviceTimezoneResolver timezoneResolver;
    @MockitoBean
    private PlanSlotResolver planSlotResolver;
    @MockitoBean
    private AlarmProperties alarmProperties;
    @MockitoBean
    private WsPushService wsPushService;

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(MedPlan.class, MedPlanItem.class, MedRecord.class, Alarm.class, Medicine.class, Device.class);

        when(alarmProperties.isEnabled()).thenReturn(true);
        when(alarmProperties.getLookbackMinutes()).thenReturn(1440L);
        when(alarmProperties.isSkipWhenDeviceOffline()).thenReturn(true);
        when(alarmMapper.nextAlarmId()).thenReturn("a-1001");
        when(toleranceResolver.resolveMinutes(any(), anyString())).thenReturn(15);
        when(timezoneResolver.resolve(ELDER)).thenReturn(ZoneId.of("Asia/Shanghai"));
        when(planSlotResolver.expandSlots(any(), any(), any(), any()))
                .thenReturn(List.of(OffsetDateTime.parse(SLOT_INSTANT)));

        MedPlan plan = new MedPlan();
        plan.setPlanId(PLAN);
        plan.setElderId(ELDER);
        plan.setStatus("ENABLED");
        plan.setSyncState("PENDING");
        plan.setTimes(List.of("08:00"));
        when(medPlanMapper.selectDueForMissScan()).thenReturn(List.of(plan));

        MedPlanItem a = new MedPlanItem();
        a.setItemId("pi-1");
        a.setMedicineId("m-1");
        MedPlanItem b = new MedPlanItem();
        b.setItemId("pi-2");
        b.setMedicineId("m-2");
        when(medPlanItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(a, b));

        when(medRecordMapper.selectByPlanAndTimeRange(any(), any(), any())).thenReturn(List.of());

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
    @DisplayName("集成链路：缺服槽落库 MISS 告警并经 WS 推送 ALARM 帧")
    void scansAndRaisesMissAlarm() {
        scanner.scan(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

        verify(alarmMapper, atLeastOnce()).insert(any(Alarm.class));
        verify(wsPushService).publish(eq(WsTopic.elder(ELDER)), eq(WsFrameType.ALARM), any());
    }

    @Test
    @DisplayName("去重：同一槽重复扫描命中 dedup_key 唯一索引冲突，不二次推送")
    void dedupOnRescan() {
        scanner.scan(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

        // 第一次扫描已落库 1 条并推送 1 次；第二次 insert 抛唯一索引冲突，应被内部吞掉、不再推送
        when(alarmMapper.insert(any(Alarm.class))).thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));

        scanner.scan(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

        // 仍至少调用过一次 insert（含被吞掉的第二次），但 WS 推送只发生 1 次
        verify(alarmMapper, atLeastOnce()).insert(any(Alarm.class));
        verify(wsPushService, times(1)).publish(anyString(), eq(WsFrameType.ALARM), any());
    }
}
