package com.medbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
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
import com.medbox.server.ws.WsSessionManager;
import com.medbox.server.ws.WsTopic;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link PlanReminderScanner} 集成测试：真实 Spring 链路（Bean 装配 / 调度配置 / 真实 WS 推送出口），
 * 仅把 Mapper 与判定协作组件换成替身，不连 PostgreSQL（H2 替换 DataSource）。
 *
 * <p><b>与旧版（mock {@code WsPushService}）不同</b>：这里用真实的 {@code WsPushService} + {@code WsSessionManager}，
 * 往管理器里注册一个记录消息的测试会话，断言 REMINDER 帧<b>真的被广播到订阅连接</b>（含 {@code medicines[]} 载荷），
 * 从而覆盖"推送链路"而非只在替身上验证"调了 publish"。调度由 {@code SchedulingConfig} 装载（与 WS 开关解耦）。
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

    /** 真实的 WS 连接注册表（ws 包默认装配）。 */
    @Autowired
    private WsSessionManager sessionManager;

    /** 应用统一的 Jackson（与 WsPushService 序列化同一套，解析帧 JSON 不出偏差）。 */
    @Autowired
    private ObjectMapper objectMapper;

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

    /** 记录广播消息的测试会话（注册进真实 WsSessionManager）。 */
    private WebSocketSession testSession;

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

        // 注册一个订阅 elder:ELDER 的测试会话，记录它收到的所有帧
        testSession = mock(WebSocketSession.class);
        when(testSession.getId()).thenReturn("reminder-test-session");
        when(testSession.isOpen()).thenReturn(true);
        sessionManager.register(testSession, List.of(WsTopic.elder(ELDER)));
    }

    @AfterEach
    void tearDown() {
        sessionManager.unregister(testSession);
    }

    @Test
    @DisplayName("集成链路：未来 slot 落去重记录并经真实 WS 广播 REMINDER 帧，medicines[] 带全")
    void scansAndPushesReminderOverWs() {
        PlanReminderScanner.ScanSummary summary =
                scanner.scan(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

        // ① 去重记录落库
        verify(planReminderMapper, atLeastOnce()).insert(any(PlanReminder.class));
        // ② 扫描汇总计数正确
        assertThat(summary.pushed()).isEqualTo(1);
        assertThat(summary.scannedPlans()).isEqualTo(1);

        // ③ 真的有帧广播到订阅连接，且是 REMINDER + 含两种药
        try {
            ArgumentCaptor<TextMessage> cap = ArgumentCaptor.forClass(TextMessage.class);
            verify(testSession, atLeastOnce()).sendMessage(cap.capture());
            JsonNode frame = objectMapper.readTree(cap.getValue().getPayload());
            assertThat(frame.get("type").asText()).isEqualTo("REMINDER");
            assertThat(frame.get("topic").asText()).isEqualTo(WsTopic.elder(ELDER));
            JsonNode medicines = frame.get("payload").get("medicines");
            assertThat(medicines).hasSize(2);
            List<String> names = new ArrayList<>();
            medicines.forEach(n -> names.add(n.get("medicineName").asText()));
            assertThat(names).containsExactlyInAnyOrder("阿莫西林", "维生素D");
        } catch (java.io.IOException ex) {
            throw new RuntimeException("解析 REMINDER 帧失败", ex);
        }
    }

    @Test
    @DisplayName("去重：同一槽重复扫描命中 dedup_key 唯一约束冲突，不二次推送（真实 WS 只收到一帧）")
    void dedupOnRescanOverWs() {
        scanner.scan(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

        // 第一次扫描已落 1 条并推送 1 次；第二次 insert 抛唯一约束冲突，应被内部吞掉、不再推送
        when(planReminderMapper.insert(any(PlanReminder.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));

        scanner.scan(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));

        verify(planReminderMapper, atLeastOnce()).insert(any(PlanReminder.class));
        // 真实 WS 会话只收到一次 REMINDER 帧
        try {
            verify(testSession, times(1)).sendMessage(any(TextMessage.class));
        } catch (java.io.IOException ex) {
            throw new RuntimeException("校验 WS 投递失败", ex);
        }
    }
}
