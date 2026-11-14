package com.medbox.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.Medicine;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.MedicineMapper;
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
 * 药品档案的集成测试（见文档 02c、06 的 2.4）。
 *
 * <p>真实链路：MockMvc → Controller → 真实 {@code MedicineService}
 * → 真实 {@code AccessService} → 真实 {@code DeviceOwnerResolverImpl} → 打桩的 Mapper。
 * 药品必归属老人（无公共字典）：列表只返回我监护老人的药、写要该老人的 WRITE_MEDICINE、
 * 越权一律 40301、PENDING 关系 40302、删除无引用校验等规则都在这里验。
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
    private static final String ELDER_ID_2 = "u-1002";
    private static final String GUARDIAN_ID = "u-2001";
    private static final String STRANGER_ID = "u-9999";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private MedicineMapper medicineMapper;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private GuardianRelationMapper guardianRelationMapper;

    @BeforeEach
    void setUp() {
        // Mapper 被打桩后 MyBatis 不会为实体建表信息，而 list 的 in(...) 要立即解析列名 —— 先预热
        TableInfoWarmup.init(Medicine.class, User.class, GuardianRelation.class);
        when(userMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<User>>any())).thenReturn(elder(ELDER_ID));
    }

    @AfterEach
    void tearDown() {
        com.medbox.server.common.security.AuthContext.clear();
    }

    // ————————————————— 药品列表 / 详情 —————————————————

    @Test
    @DisplayName("列表：监护人只返回我监护老人的药品，分页结构正确")
    void 监护人列表只返回监护老人的药() throws Exception {
        givenGuardianGuards(ELDER_ID, ELDER_ID_2);

        when(medicineMapper.selectPage(ArgumentMatchers.<Page<Medicine>>any(),
                ArgumentMatchers.<LambdaQueryWrapper<Medicine>>any()))
                .thenAnswer(inv -> {
                    Page<Medicine> page = inv.getArgument(0);
                    page.setRecords(List.of(
                            medicine("m-1001", ELDER_ID, "阿莫西林"),
                            medicine("m-1002", ELDER_ID_2, "二甲双胍")));
                    page.setTotal(2L);
                    return page;
                });

        mockMvc.perform(get("/api/v1/medicines").param("page", "1").param("size", "20")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list.length()").value(2))
                .andExpect(jsonPath("$.data.list[0].ownerElderId").value(ELDER_ID))
                .andExpect(jsonPath("$.data.list[1].ownerElderId").value(ELDER_ID_2));
    }

    @Test
    @DisplayName("列表：监护人没有任何 ACTIVE 关系 → 空分页且不查库")
    void 无监护关系列表为空() throws Exception {
        mockMvc.perform(get("/api/v1/medicines")
                        .header(HttpHeaders.AUTHORIZATION, bearer(STRANGER_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.list.length()").value(0));

        verify(medicineMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("详情：监护人可以读监护老人的药 → 200")
    void 监护人可读详情() throws Exception {
        givenGuardianGuards(ELDER_ID);
        when(medicineMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Medicine>>any()))
                .thenReturn(medicine("m-1001", ELDER_ID, "阿莫西林"));

        mockMvc.perform(get("/api/v1/medicines/{medicineId}", "m-1001")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.medicineId").value("m-1001"))
                .andExpect(jsonPath("$.data.ownerElderId").value(ELDER_ID));
    }

    @Test
    @DisplayName("详情：非监护 → 40301（防枚举，不是 40401）")
    void 非监护不可读详情() throws Exception {
        when(medicineMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Medicine>>any()))
                .thenReturn(medicine("m-1002", ELDER_ID, "二甲双胍"));
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(null);   // 陌生人无监护关系

        mockMvc.perform(get("/api/v1/medicines/{medicineId}", "m-1002")
                        .header(HttpHeaders.AUTHORIZATION, bearer(STRANGER_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("详情：监护关系 PENDING → 40302")
    void 关系未生效不可读详情() throws Exception {
        givenGuardianRelation(RelationStatus.PENDING);
        when(medicineMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Medicine>>any()))
                .thenReturn(medicine("m-1002", ELDER_ID, "二甲双胍"));

        mockMvc.perform(get("/api/v1/medicines/{medicineId}", "m-1002")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40302));
    }

    // ————————————————— 新建 —————————————————

    @Test
    @DisplayName("新建：缺 ownerElderId → 40001，不落库")
    void 新建缺归属() throws Exception {
        givenGuardianGuards(ELDER_ID);

        mockMvc.perform(post("/api/v1/medicines")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"阿莫西林","dosePerTime":"1","unit":"粒"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        verify(medicineMapper, never()).insert(any(Medicine.class));
    }

    @Test
    @DisplayName("新建：监护老人的 FAMILY → 200，业务号由服务端生成且归属回显")
    void 新建药品() throws Exception {
        givenGuardianGuards(ELDER_ID);
        when(medicineMapper.nextMedicineId()).thenReturn("m-1001");
        when(medicineMapper.insert(any(Medicine.class))).thenReturn(1);

        mockMvc.perform(post("/api/v1/medicines")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"阿莫西林","dosePerTime":"1","unit":"粒","ownerElderId":"u-1001"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.medicineId").value("m-1001"))
                .andExpect(jsonPath("$.data.ownerElderId").value(ELDER_ID));
    }

    @Test
    @DisplayName("新建：老人本人 → 40301（WRITE_MEDICINE 只对监护人开放）")
    void 老人不可建药() throws Exception {
        mockMvc.perform(post("/api/v1/medicines")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"阿莫西林","unit":"粒","ownerElderId":"u-1001"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        verify(medicineMapper, never()).insert(any(Medicine.class));
    }

    @Test
    @DisplayName("新建：非监护陌生人 → 40301")
    void 陌生人不可建药() throws Exception {
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

    @Test
    @DisplayName("新建：监护关系 PENDING → 40302")
    void 关系未生效不可建药() throws Exception {
        givenGuardianRelation(RelationStatus.PENDING);

        mockMvc.perform(post("/api/v1/medicines")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"二甲双胍","unit":"片","ownerElderId":"u-1001"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40302));

        verify(medicineMapper, never()).insert(any(Medicine.class));
    }

    // ————————————————— 更新 / 删除 —————————————————

    @Test
    @DisplayName("更新：忽略请求里的 ownerElderId（归属不可变）")
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
    @DisplayName("更新：非监护 → 40301")
    void 非监护不可更新() throws Exception {
        when(medicineMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Medicine>>any()))
                .thenReturn(medicine("m-1002", ELDER_ID, "二甲双胍"));
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(null);

        mockMvc.perform(post("/api/v1/medicines")
                        .header(HttpHeaders.AUTHORIZATION, bearer(STRANGER_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"medicineId":"m-1002","name":"二甲双胍缓释"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        verify(medicineMapper, never()).updateById(any(Medicine.class));
    }

    @Test
    @DisplayName("删除：无引用校验，监护人可以删除成功")
    void 删除无引用() throws Exception {
        givenGuardianGuards(ELDER_ID);
        when(medicineMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Medicine>>any()))
                .thenReturn(medicine("m-1001", ELDER_ID, "阿莫西林"));
        when(medicineMapper.deleteById(ArgumentMatchers.<Serializable>any())).thenReturn(1);

        mockMvc.perform(delete("/api/v1/medicines/{medicineId}", "m-1001")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("删除：非监护 → 40301")
    void 非监护不可删除() throws Exception {
        when(medicineMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Medicine>>any()))
                .thenReturn(medicine("m-1001", ELDER_ID, "阿莫西林"));
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(null);

        mockMvc.perform(delete("/api/v1/medicines/{medicineId}", "m-1001")
                        .header(HttpHeaders.AUTHORIZATION, bearer(STRANGER_ID, "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        verify(medicineMapper, never()).deleteById(ArgumentMatchers.<Serializable>any());
    }

    // ————————————————— 辅助 —————————————————

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    /** 把当前登录人配成「监护这些老人的 ACTIVE 监护人」，同时打桩 selectOne（findRelation）和 selectList（visibleElderIds）。 */
    private void givenGuardianGuards(String... elderIds) {
        List<GuardianRelation> relations = Arrays.stream(elderIds)
                .map(id -> relation(id, GUARDIAN_ID, RelationStatus.ACTIVE))
                .toList();
        when(guardianRelationMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(relations);
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(relations.isEmpty() ? null : relations.get(0));
    }

    /** 把当前登录人与 ELDER_ID 的关系配成指定状态（PENDING / REVOKED 用）。 */
    private void givenGuardianRelation(RelationStatus status) {
        GuardianRelation relation = relation(ELDER_ID, GUARDIAN_ID, status);
        when(guardianRelationMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(List.of(relation));
        when(guardianRelationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(relation);
    }

    private static Medicine medicine(String medicineId, String ownerElderId, String name) {
        Medicine m = new Medicine();
        m.setId(1L);
        m.setMedicineId(medicineId);
        m.setOwnerElderId(ownerElderId);
        m.setName(name);
        m.setDosePerTime("1");
        m.setUnit("片");
        m.setCreatedAt(OffsetDateTime.parse("2026-10-18T00:00:00Z"));
        return m;
    }

    private static GuardianRelation relation(String elderId, String guardianId, RelationStatus status) {
        GuardianRelation r = new GuardianRelation();
        r.setRelationId("r-1001");
        r.setElderId(elderId);
        r.setGuardianId(guardianId);
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
}
