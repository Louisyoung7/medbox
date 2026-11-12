package com.medbox.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.PlanSlot;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.support.TableInfoWarmup;
import java.util.List;
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

/**
 * 固定药格模型接口链路集成测试：MockMvc → Controller → 真实 PlanService → 打桩 Mapper
 * （H2 仅充当 DataSource 让事务可开，CI 无 PostgreSQL）。
 *
 * <p>覆盖：slots 计划创建、一老人一计划 40901、格号越界 40001、时间线每条含 cellNo 且不含药品。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CellModelWebIntegrationTest {

    private static final String ELDER_ID = "u-1001";
    private static final String GUARDIAN_ID = "u-2001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private MedPlanMapper medPlanMapper;

    @MockitoBean
    private DeviceMapper deviceMapper;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private GuardianRelationMapper guardianRelationMapper;

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(MedPlan.class, Device.class, User.class, GuardianRelation.class);
        User elder = new User();
        elder.setUserId(ELDER_ID);
        elder.setRole(UserRole.ELDER);
        when(userMapper.selectOne(any())).thenReturn(elder);

        GuardianRelation relation = new GuardianRelation();
        relation.setElderId(ELDER_ID);
        relation.setGuardianId(GUARDIAN_ID);
        relation.setStatus(RelationStatus.ACTIVE);
        when(guardianRelationMapper.selectList(any())).thenReturn(List.of(relation));
        when(guardianRelationMapper.selectOne(any())).thenReturn(relation);
    }

    @AfterEach
    void tearDown() {
        com.medbox.server.common.security.AuthContext.clear();
    }

    @TestConfiguration
    static class H2Config {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-cell-test")
                    .build();
        }
    }

    private String bearer() {
        return "Bearer " + jwtProvider.issue(GUARDIAN_ID, "GUARDIAN", TokenType.ACCESS);
    }

    private static PlanSlot slot(int cellNo, String time) {
        PlanSlot s = new PlanSlot();
        s.setCellNo(cellNo);
        s.setTime(time);
        return s;
    }

    private static MedPlan plan() {
        MedPlan p = new MedPlan();
        p.setId(1L);
        p.setPlanId("p-1001");
        p.setElderId(ELDER_ID);
        p.setName("日常服药");
        p.setSlots(List.of(slot(1, "08:00"), slot(2, "12:30"), slot(3, "18:00")));
        p.setStatus("ENABLED");
        p.setSyncState("PENDING");
        return p;
    }

    private static final String BODY = """
            {"elderId":"u-1001","name":"日常服药",
             "slots":[{"cellNo":1,"time":"08:00"},{"cellNo":2,"time":"12:30"}],
             "repeatRule":"DAILY"}
            """;

    @Test
    @DisplayName("POST /plans：slots 创建成功，回显格号排程且不含药品明细")
    void 创建按格计划() throws Exception {
        when(medPlanMapper.selectOne(any())).thenReturn(null);
        when(medPlanMapper.nextPlanId()).thenReturn("p-1001");
        when(medPlanMapper.insert(any(MedPlan.class))).thenReturn(1);

        mockMvc.perform(post("/api/v1/plans")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planId").value("p-1001"))
                .andExpect(jsonPath("$.data.status").value("ENABLED"))
                .andExpect(jsonPath("$.data.syncState").value("PENDING"))
                .andExpect(jsonPath("$.data.slots.length()").value(2))
                .andExpect(jsonPath("$.data.slots[0].cellNo").value(1))
                .andExpect(jsonPath("$.data.slots[0].time").value("08:00"))
                .andExpect(jsonPath("$.data.items").doesNotExist());
    }

    @Test
    @DisplayName("POST /plans：同一老人已有计划 → 40901（一老人一计划）")
    void 重复创建冲突() throws Exception {
        when(medPlanMapper.selectOne(any())).thenReturn(plan());

        mockMvc.perform(post("/api/v1/plans")
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));
    }

    @Test
    @DisplayName("POST /plans：格号越界 / 重复 / 时间非法 → 40001")
    void 槽位校验() throws Exception {
        String[] badBodies = {
            """
            {"elderId":"u-1001","slots":[{"cellNo":4,"time":"08:00"}]}
            """,
            """
            {"elderId":"u-1001","slots":[{"cellNo":1,"time":"08:00"},{"cellNo":1,"time":"09:00"}]}
            """,
            """
            {"elderId":"u-1001","slots":[{"cellNo":1,"time":"25:00"}]}
            """
        };
        for (String body : badBodies) {
            mockMvc.perform(post("/api/v1/plans")
                            .header(HttpHeaders.AUTHORIZATION, bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40001));
        }
    }

    @Test
    @DisplayName("GET /plans/{id}/next-doses：每条含 cellNo，不含药品信息")
    void 时间线按格展开() throws Exception {
        when(medPlanMapper.selectOne(any())).thenReturn(plan());
        Device device = new Device();
        device.setDeviceId("BOXA1001");
        device.setElderId(ELDER_ID);
        device.setDeviceType(DeviceType.MAIN);
        device.setTimezone("Asia/Shanghai");
        when(deviceMapper.selectOne(any())).thenReturn(device);

        mockMvc.perform(get("/api/v1/plans/{planId}/next-doses", "p-1001")
                        .param("days", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].cellNo").exists())
                .andExpect(jsonPath("$.data[0].scheduledTimeUtc").exists())
                .andExpect(jsonPath("$.data[0].items").doesNotExist());
    }
}
