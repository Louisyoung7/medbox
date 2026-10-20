package com.medbox.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.Medicine;
import com.medbox.server.domain.MedicineStock;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.RelationRole;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.MedicineMapper;
import com.medbox.server.mapper.MedicineStockMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.support.TableInfoWarmup;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
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

/**
 * 药品档案 + 库存的集成测试（{@code feat/medicine-core}，见文档 02 第 5 章 / 06 的 2.4、2.5）。
 *
 * <p>真实链路：MockMvc → Controller → 真实 {@code MedicineService}/{@code MedicineStockService}
 * → 真实 {@code AccessService} → 真实 {@code DeviceOwnerResolverImpl} → 打桩的 Mapper。
 * 公共药写要 FAMILY 监护人、私有药写要监护关系、越权一律 40301、删除有库存引用 40901 等规则都在这里验。
 *
 * <p><b>不连真实 PostgreSQL</b>：{@code save/delete} 是 {@code @Transactional}，进入方法要开 JDBC 连接，
 * 用内嵌 H2 替换 DataSource（Mapper 仍是 mock、不跑真实 SQL）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MedicineCoreWebIntegrationTest {

    @TestConfiguration
    static class H2DataSourceConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-test")
                    .build();
        }
    }

    private static final String ELDER_ID = "u-1001";
    private static final String GUARDIAN_ID = "u-2001";
    private static final String STRANGER_ID = "u-9999";
    private static final String DEVICE_ID = "BOXA1001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private MedicineMapper medicineMapper;

    @MockitoBean
    private MedicineStockMapper medicineStockMapper;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private GuardianRelationMapper guardianRelationMapper;

    @MockitoBean
    private DeviceMapper deviceMapper;

    @BeforeEach
    void setUp() {
        // Mapper 被打桩后 MyBatis 不会为实体建表信息，而 list/medicineNames 的 in(...) 要立即解析列名 —— 先预热
        TableInfoWarmup.init(Medicine.class, MedicineStock.class, User.class, GuardianRelation.class, Device.class);
        when(userMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<User>>any())).thenReturn(elder(ELDER_ID));
    }

    @AfterEach
    void tearDown() {
        com.medbox.server.common.security.AuthContext.clear();
    }

    // ————————————————— 药品列表 / 详情 —————————————————

    @Test
    @DisplayName("列表：FAMILY 监护人看到公共药 + 我监护老人的私有药，分页结构正确")
    void 监护人列表返回公共与私有() throws Exception {
        givenGuardianGuards(ELDER_ID);

        when(medicineMapper.selectPage(ArgumentMatchers.<Page<Medicine>>any(),
                ArgumentMatchers.<LambdaQueryWrapper<Medicine>>any()))
                .thenAnswer(inv -> {
                    Page<Medicine> page = inv.getArgument(0);
                    page.setRecords(List.of(
                            medicine("m-1001", null, "阿莫西林"),   // 公共
                            medicine("m-1002", ELDER_ID, "二甲双胍"))); // 私有
                    page.setTotal(2L);
                    return page;
                });

        mockMvc.perform(get("/api/v1/medicines").param("page", "1").param("size", "20")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list.length()").value(2))
                .andExpect(jsonPath("$.data.list[0].medicineId").value("m-1001"))
                .andExpect(jsonPath("$.data.list[1].medicineId").value("m-1002"));
    }

    @Test
    @DisplayName("详情：公共药任何人可读 → 200")
    void 公共药详情() throws Exception {
        when(medicineMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Medicine>>any()))
                .thenReturn(medicine("m-1001", null, "阿莫西林"));

        mockMvc.perform(get("/api/v1/medicines/{medicineId}", "m-1001")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.medicineId").value("m-1001"));
    }

    @Test
    @DisplayName("详情：私有药非监护 → 40301（防枚举，不是 40401）")
    void 私有药非监护不可读() throws Exception {
        when(medicineMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Medicine>>any()))
                .thenReturn(medicine("m-1002", ELDER_ID, "二甲双胍"));
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(null);   // 陌生人无监护关系

        mockMvc.perform(get("/api/v1/medicines/{medicineId}", "m-1002")
                        .header(HttpHeaders.AUTHORIZATION, bearer(STRANGER_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    // ————————————————— 新建 —————————————————

    @Test
    @DisplayName("新建公共药:FAMILY 监护人 → 200,业务号由服务端生成")
    void 新建公共药() throws Exception {
        givenGuardianGuards(ELDER_ID);
        when(medicineMapper.nextMedicineId()).thenReturn("m-1001");
        when(medicineMapper.insert(any(Medicine.class))).thenReturn(1);

        mockMvc.perform(post("/api/v1/medicines")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"阿莫西林","spec":"0.25g","dosePerTime":"1","unit":"粒"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.medicineId").value("m-1001"))
                .andExpect(jsonPath("$.data.ownerElderId").doesNotExist()); // 公共药 owner 为 null，被 non_null 省略
    }

    @Test
    @DisplayName("新建公共药：老人本人 → 40301(WRITE_MEDICINE 只对 FAMILY 开放)")
    void 老人不可建公共药() throws Exception {
        mockMvc.perform(post("/api/v1/medicines")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"阿莫西林","spec":"0.25g","unit":"粒"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        verify(medicineMapper, never()).insert(any(Medicine.class));
    }

    @Test
    @DisplayName("新建私有药：监护该老人的 FAMILY → 200")
    void 新建私有药() throws Exception {
        givenGuardianGuards(ELDER_ID);
        when(medicineMapper.nextMedicineId()).thenReturn("m-1002");
        when(medicineMapper.insert(any(Medicine.class))).thenReturn(1);

        mockMvc.perform(post("/api/v1/medicines")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"二甲双胍","spec":"0.5g","unit":"片","ownerElderId":"u-1001"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.medicineId").value("m-1002"))
                .andExpect(jsonPath("$.data.ownerElderId").value(ELDER_ID));
    }

    @Test
    @DisplayName("新建私有药：非监护陌生人 → 40301")
    void 陌生人不可建私有药() throws Exception {
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(null);

        mockMvc.perform(post("/api/v1/medicines")
                        .header(HttpHeaders.AUTHORIZATION, bearer(STRANGER_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"二甲双胍","unit":"片","ownerElderId":"u-1001"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        verify(medicineMapper, never()).insert(any(Medicine.class));
    }

    // ————————————————— 更新 / 删除 —————————————————

    @Test
    @DisplayName("更新：忽略请求里的 ownerElderId(归属不可变)")
    void 更新不改归属() throws Exception {
        givenGuardianGuards(ELDER_ID);
        when(medicineMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Medicine>>any()))
                .thenReturn(medicine("m-1002", ELDER_ID, "二甲双胍"));
        when(medicineMapper.updateById(any(Medicine.class))).thenReturn(1);

        mockMvc.perform(post("/api/v1/medicines")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"medicineId":"m-1002","name":"二甲双胍缓释","ownerElderId":"u-9999"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.medicineId").value("m-1002"))
                .andExpect(jsonPath("$.data.ownerElderId").value(ELDER_ID)); // 还是 u-1001，没被改成 u-9999
    }

    @Test
    @DisplayName("删除：仍有库存引用 → 40901")
    void 删除被库存引用返回_40901() throws Exception {
        givenGuardianGuards(ELDER_ID);
        when(medicineMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Medicine>>any()))
                .thenReturn(medicine("m-1001", null, "阿莫西林"));
        when(medicineStockMapper.selectCount(ArgumentMatchers.<LambdaQueryWrapper<MedicineStock>>any()))
                .thenReturn(1L);   // 有库存引用

        mockMvc.perform(delete("/api/v1/medicines/{medicineId}", "m-1001")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));

        verify(medicineMapper, never()).deleteById(ArgumentMatchers.<Serializable>any());
    }

    @Test
    @DisplayName("删除：无库存引用 → 200")
    void 删除无引用() throws Exception {
        givenGuardianGuards(ELDER_ID);
        when(medicineMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Medicine>>any()))
                .thenReturn(medicine("m-1001", null, "阿莫西林"));
        when(medicineStockMapper.selectCount(ArgumentMatchers.<LambdaQueryWrapper<MedicineStock>>any()))
                .thenReturn(0L);
        when(medicineMapper.deleteById(ArgumentMatchers.<Serializable>any())).thenReturn(1);

        mockMvc.perform(delete("/api/v1/medicines/{medicineId}", "m-1001")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ————————————————— 库存 —————————————————

    @Test
    @DisplayName("库存列表：FAMILY 监护人看该药箱 → 200，带药名")
    void 库存列表带药名() throws Exception {
        givenGuardianGuards(ELDER_ID);
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(mainDevice(DEVICE_ID, ELDER_ID));
        when(medicineStockMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<MedicineStock>>any()))
                .thenReturn(List.of(stock("s-1001", DEVICE_ID, "m-1001", 5)));
        when(medicineMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<Medicine>>any()))
                .thenReturn(List.of(medicine("m-1001", null, "阿莫西林")));

        mockMvc.perform(get("/api/v1/devices/{deviceId}/stocks", DEVICE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].stockId").value("s-1001"))
                .andExpect(jsonPath("$.data[0].medicineName").value("阿莫西林"));
    }

    @Test
    @DisplayName("库存列表：设备不存在 → 40301（防设备编号枚举）")
    void 设备不存在返回_40301() throws Exception {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(null);

        mockMvc.perform(get("/api/v1/devices/{deviceId}/stocks", "BOXA9999")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("库存录入：FAMILY 监护人 → 200，新建一条库存")
    void 库存录入新建() throws Exception {
        givenGuardianGuards(ELDER_ID);
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(mainDevice(DEVICE_ID, ELDER_ID));
        when(medicineMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Medicine>>any()))
                .thenReturn(medicine("m-1001", null, "阿莫西林"));
        when(medicineStockMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<MedicineStock>>any()))
                .thenReturn(null);   // 还没有这条库存 → 新建
        when(medicineStockMapper.nextStockId()).thenReturn("s-1001");
        when(medicineStockMapper.insert(any(MedicineStock.class))).thenReturn(1);

        mockMvc.perform(put("/api/v1/devices/{deviceId}/stocks/{medicineId}", DEVICE_ID, "m-1001")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stockId").value("s-1001"))
                .andExpect(jsonPath("$.data.quantity").value(5))
                .andExpect(jsonPath("$.data.medicineName").value("阿莫西林"));
    }

    @Test
    @DisplayName("补药入库：数量累加 → 200（5 + 3 = 8）")
    void 补药入库累加() throws Exception {
        givenGuardianGuards(ELDER_ID);
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(mainDevice(DEVICE_ID, ELDER_ID));
        when(medicineMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Medicine>>any()))
                .thenReturn(medicine("m-1001", null, "阿莫西林"));
        when(medicineStockMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<MedicineStock>>any()))
                .thenReturn(stock("s-1001", DEVICE_ID, "m-1001", 5));   // 已有 5
        when(medicineStockMapper.updateById(any(MedicineStock.class))).thenReturn(1);

        mockMvc.perform(post("/api/v1/devices/{deviceId}/stocks/{medicineId}/in", DEVICE_ID, "m-1001")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":3}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quantity").value(8));
    }

    @Test
    @DisplayName("库存录入：引用的药品不存在 → 40401")
    void 库存引用药品不存在返回_40401() throws Exception {
        givenGuardianGuards(ELDER_ID);
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(mainDevice(DEVICE_ID, ELDER_ID));
        when(medicineMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Medicine>>any()))
                .thenReturn(null);   // 药品不存在

        mockMvc.perform(put("/api/v1/devices/{deviceId}/stocks/{medicineId}", DEVICE_ID, "m-9999")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":5}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    // ————————————————— 辅助 —————————————————

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    /** 把当前登录人配成「监护这些老人的 FAMILY 监护人」，同时打桩 selectOne（findRelation）和 selectList（visibleElderIds / requireFamilyGuardian）。 */
    private void givenGuardianGuards(String... elderIds) {
        List<GuardianRelation> relations = Arrays.stream(elderIds)
                .map(id -> relation(id, GUARDIAN_ID, RelationRole.FAMILY, RelationStatus.ACTIVE))
                .toList();
        when(guardianRelationMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(relations);
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(relations.isEmpty() ? null : relations.get(0));
    }

    private static Medicine medicine(String medicineId, String ownerElderId, String name) {
        Medicine m = new Medicine();
        m.setId(1L);
        m.setMedicineId(medicineId);
        m.setOwnerElderId(ownerElderId);
        m.setName(name);
        m.setSpec("10mg");
        m.setDosePerTime("1");
        m.setUnit("片");
        m.setCreatedAt(OffsetDateTime.parse("2026-10-18T00:00:00Z"));
        return m;
    }

    private static MedicineStock stock(String stockId, String deviceId, String medicineId, int quantity) {
        MedicineStock s = new MedicineStock();
        s.setId(1L);
        s.setStockId(stockId);
        s.setDeviceId(deviceId);
        s.setElderId(ELDER_ID);
        s.setMedicineId(medicineId);
        s.setQuantity(quantity);
        return s;
    }

    private static GuardianRelation relation(String elderId, String guardianId,
                                             RelationRole role, RelationStatus status) {
        GuardianRelation r = new GuardianRelation();
        r.setRelationId("r-1001");
        r.setElderId(elderId);
        r.setGuardianId(guardianId);
        r.setRole(role);
        r.setStatus(status);
        return r;
    }

    private static User elder(String userId) {
        User u = new User();
        u.setUserId(userId);
        u.setRole(UserRole.ELDER);
        u.setName("张三");
        return u;
    }

    private static Device mainDevice(String deviceId, String elderId) {
        Device d = new Device();
        d.setDeviceId(deviceId);
        d.setElderId(elderId);
        // parentDeviceId 留空 = 主控，resolver 直接取 elder_id，不会二次查库
        return d;
    }
}
