package com.medbox.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedPlanItem;
import com.medbox.server.domain.Medicine;
import com.medbox.server.domain.MedicineStock;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.MedicineMapper;
import com.medbox.server.mapper.MedicineStockMapper;
import com.medbox.server.mapper.MedPlanItemMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.mqtt.DownlinkPublisher;
import com.medbox.server.mqtt.LoggingDownlinkFailureListener;
import com.medbox.server.mqtt.MqttProperties;
import com.medbox.server.mqtt.MqttTransport;
import com.medbox.server.mqtt.MqttTransportException;
import com.medbox.server.mqtt.PendingAckRegistry;
import com.medbox.server.support.TableInfoWarmup;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.Assert;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 计划下发设备集成测试（{@code feat/plan-sync}，见文档 03 第 3 章 + 07）。
 *
 * <p><b>MQTT 全程不连真 broker</b>：测试环境 {@code MEDBOX_MQTT_ENABLED=false} 关掉了 {@code MqttConfig}，
 * 这里手动补上 {@link DownlinkPublisher} / {@link PendingAckRegistry} / {@link MqttProperties} 等最小 Bean，
 * 并用 {@link FakeMqttTransport} 假实现记录下行消息 —— 既有"真实链路（MockMvc → Controller → PlanService
 * → 事件 → MqttPlanScheduleSync → DownlinkPublisher）"，又不需要 EMQX。
 *
 * <p>覆盖：保存后 {@code down/schedule} 正确下发（UPSERT 含 items 数组 / DELETE 无 items）；
 * ack {@code OK} → {@code sync_state=SYNCED_TO_DEVICE}、{@code FAILED} / 超时放弃 → {@code SYNC_FAILED}；
 * 超时按账本重发（含首发共 3 次）。
 */
@SpringBootTest(properties = {
        "medbox.mqtt.ack-timeout-ms=300",
        "medbox.mqtt.retry-scan-interval-ms=100",
        "medbox.mqtt.max-attempts=3"
})
@AutoConfigureMockMvc
class PlanSyncWebIntegrationTest {

    private static final String ELDER_ID = "u-1001";
    private static final String GUARDIAN_ID = "u-2001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private PendingAckRegistry pendingAckRegistry;

    @Autowired
    private MqttTransport mqttTransport;

    @Autowired
    private MqttProperties mqttProps;

    @Autowired
    private ObjectMapper jsonMapper;

    @MockitoBean
    private MedPlanMapper medPlanMapper;

    @MockitoBean
    private MedPlanItemMapper medPlanItemMapper;

    @MockitoBean
    private MedicineMapper medicineMapper;

    @MockitoBean
    private MedicineStockMapper medicineStockMapper;

    @MockitoBean
    private DeviceMapper deviceMapper;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private GuardianRelationMapper guardianRelationMapper;

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(MedPlan.class, MedPlanItem.class, Medicine.class,
                MedicineStock.class, Device.class, User.class, com.medbox.server.domain.GuardianRelation.class);
        when(userMapper.selectOne(any())).thenReturn(elder(ELDER_ID));
        // 确保账本调度线程已启动（个别测试上下文 SmartLifecycle 未必自动拉起）
        if (!pendingAckRegistry.isRunning()) {
            pendingAckRegistry.start();
        }
    }

    @AfterEach
    void tearDown() {
        com.medbox.server.common.security.AuthContext.clear();
        ((FakeMqttTransport) mqttTransport).clear();
    }

    // ———————————————— 创建 → UPSERT 正确下发 ————————————————

    @Test
    @DisplayName("创建：down/schedule 正确下发，topic 形态正确且含完整 items 数组与信封")
    void 创建下发UPSERT载荷正确() throws Exception {
        givenGuardianGuards(ELDER_ID);
        givenMainDevice(ELDER_ID, "PK001");
        givenMedicine("m-1001");
        givenStock(ELDER_ID, "m-1001", 5, LocalDate.parse("2026-12-31"));
        givenPlan("p-1001", List.of("08:00"), "DAILY", 15);
        givenItems("p-1001", "pi-1001", "m-1001", "1", "片");

        when(medPlanMapper.nextPlanId()).thenReturn("p-1001");
        when(medPlanMapper.insert(any(MedPlan.class))).thenReturn(1);
        when(medPlanItemMapper.nextItemId()).thenReturn("pi-1001");
        when(medPlanItemMapper.insert(any(MedPlanItem.class))).thenReturn(1);

        mockMvc.perform(post("/api/v1/plans")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":"u-1001","name":"早餐后",
                                 "times":["08:00"],"repeatRule":"DAILY","missAlarmAfterMin":15,
                                 "items":[{"medicineId":"m-1001","dose":"1","unit":"片"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.syncState").value("PENDING"));

        waitUntilPublishCountAtLeast(1);
        Assert.isTrue("medbox/PK001/BOXA1001/down/schedule".equals(topicAt(0)),
                "topic 形态不符: " + topicAt(0));

        JsonNode payload = jsonMapper.readTree(payloadAt(0));
        Assert.isTrue(payload.get("msgId").asText() != null && !payload.get("msgId").asText().isBlank(),
                "缺少 msgId 信封");
        Assert.isTrue(payload.get("needAck").asBoolean(), "needAck 应为 true");
        Assert.isTrue("UPSERT".equals(payload.get("op").asText()), "op 应为 UPSERT");
        Assert.isTrue("p-1001".equals(payload.get("planId").asText()), "planId 不符");
        Assert.isTrue(payload.get("times").size() == 1 && "08:00".equals(payload.get("times").get(0).asText()),
                "times 不符");
        Assert.isTrue("DAILY".equals(payload.get("repeat").asText()), "repeat 应为 DAILY");
        Assert.isTrue(payload.get("alarm").get("missAfterMin").asInt() == 15, "alarm.missAfterMin 不符");
        Assert.isTrue(payload.get("items").size() == 1, "items 数组长度不符");
        JsonNode item = payload.get("items").get(0);
        Assert.isTrue("pi-1001".equals(item.get("itemId").asText()), "itemId 不符");
        Assert.isTrue("m-1001".equals(item.get("medicineId").asText()), "item.medicineId 不符");
        Assert.isTrue("1".equals(item.get("dose").asText()), "item.dose 不符");
        Assert.isTrue("片".equals(item.get("unit").asText()), "item.unit 不符");

        pendingAckRegistry.settle(payload.get("msgId").asText(), true, null);
    }

    // ———————————————— ack OK → SYNCED_TO_DEVICE ————————————————

    @Test
    @DisplayName("创建 + ack OK → sync_state 翻成 SYNCED_TO_DEVICE")
    void 创建后ack成功翻终态已同步() throws Exception {
        givenGuardianGuards(ELDER_ID);
        givenMainDevice(ELDER_ID, "PK001");
        givenMedicine("m-1001");
        givenStock(ELDER_ID, "m-1001", 5, LocalDate.parse("2026-12-31"));
        givenPlan("p-1001", List.of("08:00"), "DAILY", 15);
        givenItems("p-1001", "pi-1001", "m-1001", "1", "片");

        when(medPlanMapper.nextPlanId()).thenReturn("p-1001");
        when(medPlanMapper.insert(any(MedPlan.class))).thenReturn(1);
        when(medPlanItemMapper.nextItemId()).thenReturn("pi-1001");
        when(medPlanItemMapper.insert(any(MedPlanItem.class))).thenReturn(1);

        mockMvc.perform(post("/api/v1/plans")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":"u-1001","times":["08:00"],"repeatRule":"DAILY",
                                 "items":[{"medicineId":"m-1001","dose":"1","unit":"片"}]}
                                """))
                .andExpect(status().isOk());

        pendingAckRegistry.settle(captureMsgId(), true, null);
        assertSyncState("SYNCED_TO_DEVICE");
    }

    // ———————————————— ack FAILED → SYNC_FAILED ————————————————

    @Test
    @DisplayName("创建 + ack FAILED → sync_state 翻成 SYNC_FAILED")
    void 创建后ack失败翻终态失败() throws Exception {
        givenGuardianGuards(ELDER_ID);
        givenMainDevice(ELDER_ID, "PK001");
        givenMedicine("m-1001");
        givenStock(ELDER_ID, "m-1001", 5, LocalDate.parse("2026-12-31"));
        givenPlan("p-2002", List.of("20:00"), "DAILY", null);
        givenItems("p-2002", "pi-2001", "m-1001", "2", "片");

        when(medPlanMapper.nextPlanId()).thenReturn("p-2002");
        when(medPlanMapper.insert(any(MedPlan.class))).thenReturn(1);
        when(medPlanItemMapper.nextItemId()).thenReturn("pi-2001");
        when(medPlanItemMapper.insert(any(MedPlanItem.class))).thenReturn(1);

        mockMvc.perform(post("/api/v1/plans")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":"u-1001","times":["20:00"],"repeatRule":"DAILY",
                                 "items":[{"medicineId":"m-1001","dose":"2","unit":"片"}]}
                                """))
                .andExpect(status().isOk());

        pendingAckRegistry.settle(captureMsgId(), false, "medicine_not_found");
        assertSyncState("SYNC_FAILED");
    }

    // ———————————————— 停用 → DELETE 下发 ————————————————

    @Test
    @DisplayName("停用（PATCH DISABLED）→ down/schedule 下发 op=DELETE，无 items")
    void 停用下发DELETE() throws Exception {
        givenGuardianGuards(ELDER_ID);
        givenMainDevice(ELDER_ID, "PK001");
        when(medPlanMapper.selectOne(any())).thenReturn(plan("p-1001", ELDER_ID, "ENABLED"));
        when(medPlanMapper.updateById(any(MedPlan.class))).thenReturn(1);
        when(medPlanItemMapper.selectList(any())).thenReturn(List.of());

        mockMvc.perform(patch("/api/v1/plans/{planId}/status", "p-1001")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        waitUntilPublishCountAtLeast(1);
        Assert.isTrue("medbox/PK001/BOXA1001/down/schedule".equals(topicAt(0)),
                "topic 形态不符: " + topicAt(0));
        JsonNode payload = jsonMapper.readTree(payloadAt(0));
        Assert.isTrue("DELETE".equals(payload.get("op").asText()), "op 应为 DELETE");
        Assert.isTrue("p-1001".equals(payload.get("planId").asText()), "planId 不符");
        Assert.isTrue(payload.get("items") == null || payload.get("items").isNull(),
                "DELETE 不应带 items");

        pendingAckRegistry.settle(payload.get("msgId").asText(), true, null);
    }

    // ———————————————— 超时重发 3 次 + 放弃 → SYNC_FAILED ————————————————

    @Test
    @DisplayName("无 ack：账本按超时重发，含首发共 3 次后放弃 → SYNC_FAILED")
    void 超时重发三次后放弃() throws Exception {
        givenGuardianGuards(ELDER_ID);
        givenMainDevice(ELDER_ID, "PK001");
        givenMedicine("m-1001");
        givenStock(ELDER_ID, "m-1001", 5, LocalDate.parse("2026-12-31"));
        givenPlan("p-1001", List.of("08:00"), "DAILY", 15);
        givenItems("p-1001", "pi-1001", "m-1001", "1", "片");

        when(medPlanMapper.nextPlanId()).thenReturn("p-1001");
        when(medPlanMapper.insert(any(MedPlan.class))).thenReturn(1);
        when(medPlanItemMapper.nextItemId()).thenReturn("pi-1001");
        when(medPlanItemMapper.insert(any(MedPlanItem.class))).thenReturn(1);

        mockMvc.perform(post("/api/v1/plans")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":"u-1001","times":["08:00"],"repeatRule":"DAILY",
                                 "items":[{"medicineId":"m-1001","dose":"1","unit":"片"}]}
                                """))
                .andExpect(status().isOk());

        // 关掉自动调度，改为确定性手动驱动 rescan（与定时扫描走同一逻辑，等价于"超时重发"）
        pendingAckRegistry.stop();
        waitUntilPublishCountAtLeast(1); // 首发已发生
        Assert.isTrue(pendingAckRegistry.snapshot().size() == 1,
                "账本应登记 1 条待回执，实际=" + pendingAckRegistry.snapshot().size());
        Assert.isTrue(mqttProps.getAckTimeoutMs() == 300L, "ackTimeoutMs 应为 300，实际=" + mqttProps.getAckTimeoutMs());

        // ackTimeoutMs=300ms：等过期后 rescan 触发重发；含首发共 3 次（2 次重发）
        Thread.sleep(360);
        int r1 = pendingAckRegistry.rescan();
        Assert.isTrue(r1 >= 1, "第 1 次 rescan 未重发 r1=" + r1);
        Thread.sleep(360);
        int r2 = pendingAckRegistry.rescan();
        Assert.isTrue(r2 >= 1, "第 2 次 rescan 未重发 r2=" + r2);
        Thread.sleep(360);
        pendingAckRegistry.rescan(); // attempts 达 max → 放弃

        Assert.isTrue(fakeTransport().payloads.size() >= 3,
                "含首发应共 3 次下发，实际=" + fakeTransport().payloads.size());
        for (int i = 0; i < fakeTransport().payloads.size(); i++) {
            Assert.isTrue("medbox/PK001/BOXA1001/down/schedule".equals(topicAt(i)),
                    "第 " + i + " 次下发 topic 不符");
        }

        // 放弃后翻终态
        assertSyncState("SYNC_FAILED");
    }

    // ———————————————— 辅助 ————————————————

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    private FakeMqttTransport fakeTransport() {
        return (FakeMqttTransport) mqttTransport;
    }

    private String topicAt(int i) {
        return fakeTransport().topics.get(i);
    }

    private byte[] payloadAt(int i) {
        return fakeTransport().payloads.get(i);
    }

    private void waitUntilPublishCountAtLeast(int n) {
        long deadline = System.currentTimeMillis() + 6000;
        while (fakeTransport().payloads.size() < n && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Assert.isTrue(fakeTransport().payloads.size() >= n,
                "下行次数不足：期望≥" + n + " 实际=" + fakeTransport().payloads.size());
    }

    /** 从下行记录里取出本次下发的 msgId（用于手动 simulate ack）。 */
    private String captureMsgId() throws Exception {
        waitUntilPublishCountAtLeast(1);
        JsonNode payload = jsonMapper.readTree(payloadAt(0));
        return payload.get("msgId").asText();
    }

    /** 校验 onComplete 把 med_plan.sync_state 翻成了期望值（列名 + 参数值双重断言）。 */
    @SuppressWarnings("unchecked")
    private void assertSyncState(String expected) {
        var wrapperCaptor = org.mockito.ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(medPlanMapper, timeout(5000)).update(isNull(), wrapperCaptor.capture());
        LambdaUpdateWrapper<MedPlan> wrapper = (LambdaUpdateWrapper<MedPlan>) wrapperCaptor.getValue();
        String sqlSet = wrapper.getSqlSet();
        Assert.isTrue(sqlSet != null && sqlSet.toLowerCase().contains("sync_state"),
                "SET 应含 sync_state，实际: " + sqlSet);
        Assert.isTrue(wrapper.getParamNameValuePairs().containsValue(expected),
                "sync_state 应= " + expected + "，实际参数: " + wrapper.getParamNameValuePairs());
    }

    private void givenGuardianGuards(String... elderIds) {
        List<com.medbox.server.domain.GuardianRelation> relations = java.util.Arrays.stream(elderIds)
                .map(id -> relation(id, GUARDIAN_ID, RelationStatus.ACTIVE))
                .toList();
        when(guardianRelationMapper.selectList(any())).thenReturn(relations);
        when(guardianRelationMapper.selectOne(any()))
                .thenReturn(relations.isEmpty() ? null : relations.get(0));
    }

    /** 老人 MAIN 设备（同时供 PlanService 时区解析与监听器设备解析；含 productKey 以拼 topic）。 */
    private void givenMainDevice(String elderId, String productKey) {
        Device device = new Device();
        device.setDeviceId("BOXA1001");
        device.setElderId(elderId);
        device.setDeviceType(DeviceType.MAIN);
        device.setTimezone("Asia/Shanghai");
        device.setProductKey(productKey);
        when(deviceMapper.selectOne(any())).thenReturn(device);
    }

    private void givenMedicine(String medicineId) {
        when(medicineMapper.selectOne(any())).thenReturn(medicine(medicineId, "阿莫西林"));
    }

    private void givenStock(String elderId, String medicineId, int quantity, LocalDate expiry) {
        MedicineStock stock = new MedicineStock();
        stock.setElderId(elderId);
        stock.setMedicineId(medicineId);
        stock.setQuantity(quantity);
        stock.setExpiryDate(expiry);
        when(medicineStockMapper.selectList(any())).thenReturn(List.of(stock));
    }

    /** stub medPlanMapper.selectOne 回查（监听器 UPSERT 时组装载荷用）。 */
    private void givenPlan(String planId, List<String> times, String repeat, Integer miss) {
        when(medPlanMapper.selectOne(any())).thenReturn(plan(planId, ELDER_ID, times, repeat, miss, "ENABLED"));
    }

    private void givenItems(String planId, String itemId, String medicineId, String dose, String unit) {
        when(medPlanItemMapper.selectList(any()))
                .thenReturn(List.of(item(itemId, planId, medicineId, dose, unit)));
    }

    private static MedPlan plan(String planId, String elderId, String status) {
        return plan(planId, elderId, List.of("08:00"), "DAILY", 15, status);
    }

    private static MedPlan plan(String planId, String elderId, List<String> times,
                               String repeat, Integer miss, String status) {
        MedPlan p = new MedPlan();
        p.setId(1L);
        p.setPlanId(planId);
        p.setElderId(elderId);
        p.setTimes(times);
        p.setRepeatRule(repeat);
        p.setMissAlarmAfterMin(miss);
        p.setStatus(status);
        return p;
    }

    private static MedPlanItem item(String itemId, String planId, String medicineId, String dose, String unit) {
        MedPlanItem it = new MedPlanItem();
        it.setItemId(itemId);
        it.setPlanId(planId);
        it.setMedicineId(medicineId);
        it.setDose(dose);
        it.setUnit(unit);
        return it;
    }

    private static Medicine medicine(String medicineId, String name) {
        Medicine m = new Medicine();
        m.setMedicineId(medicineId);
        m.setName(name);
        return m;
    }

    private static com.medbox.server.domain.GuardianRelation relation(String elderId, String guardianId, RelationStatus status) {
        com.medbox.server.domain.GuardianRelation r = new com.medbox.server.domain.GuardianRelation();
        r.setElderId(elderId);
        r.setGuardianId(guardianId);
        r.setStatus(status);
        return r;
    }

    private static User elder(String userId) {
        User u = new User();
        u.setUserId(userId);
        u.setRole(UserRole.ELDER);
        return u;
    }

    @TestConfiguration
    static class MqttForTestConfig {
        /** H2 替身 DataSource（CI 无 Postgres，见 AGENTS.md 3.1）。 */
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-plan-sync-test")
                    .build();
        }

        /** 测试用 MQTT 配置：短超时 + 3 次总下发，便于验证"重发 3 次"。 */
        @Bean
        public MqttProperties mqttProperties() {
            MqttProperties props = new MqttProperties();
            props.setTopicPrefix("medbox");
            props.setAckTimeoutMs(300L);
            props.setRetryScanIntervalMs(100L);
            props.setMaxAttempts(3);
            return props;
        }

        @Bean
        public com.medbox.server.mqtt.DownlinkFailureListener downlinkFailureListener() {
            return new LoggingDownlinkFailureListener();
        }

        @Bean
        public PendingAckRegistry pendingAckRegistry(MqttProperties props,
                                                    com.medbox.server.mqtt.DownlinkFailureListener failureListener) {
            return new PendingAckRegistry(props, failureListener);
        }

        @Bean
        public DownlinkPublisher downlinkPublisher(MqttTransport transport,
                                                  DeviceMapper deviceMapper,
                                                  PendingAckRegistry registry,
                                                  MqttProperties props,
                                                  ObjectMapper objectMapper) {
            return new DownlinkPublisher(transport, deviceMapper, registry, props, objectMapper);
        }

        /** 记录所有下行的假 Transport（替代真 EMQX 连接）。 */
        @Bean
        public MqttTransport mqttTransport() {
            return new FakeMqttTransport();
        }
    }

    /** 记录所有下行的假 Transport（替代真 EMQX 连接）。 */
    static class FakeMqttTransport implements MqttTransport {
        final CopyOnWriteArrayList<String> topics = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<byte[]> payloads = new CopyOnWriteArrayList<>();

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void publish(String topic, byte[] payload, int qos, boolean retained)
                throws MqttTransportException {
            topics.add(topic);
            payloads.add(payload);
        }

        void clear() {
            topics.clear();
            payloads.clear();
        }
    }
}
