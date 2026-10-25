package com.medbox.server.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.medbox.server.common.security.AuthContext;
import com.medbox.server.config.CaptureProperties;
import com.medbox.server.domain.Capture;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.User;
import com.medbox.server.mapper.CaptureMapper;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.common.security.DeviceOwnerResolver;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.service.CaptureFileTokenService;
import com.medbox.server.support.TableInfoWarmup;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import javax.sql.DataSource;

/**
 * {@code feat/capture-core} 集成测试：端到端串起「设备 HMAC 上传 → 落盘/去重/过期」与
 * 「用户侧鉴权读取/列表/删除 → fileToken 文件流」，并覆盖更严格的可见性矩阵（护理/医生 40301）。
 *
 * <p>参照 {@code MedicineCoreWebIntegrationTest}：内嵌 H2 替换 DataSource（CI 无 Postgres，Flyway 已关），
 * Mapper 全打桩、不跑真实 SQL；落盘指向临时目录。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("CaptureCoreWebIntegrationTest：抓拍上传/查询/权限/fileToken 端到端")
class CaptureCoreWebIntegrationTest {

    private static final OffsetDateTime CAP = OffsetDateTime.parse("2026-10-04T00:00:00Z");
    private static Path tmpDir;

    static {
        try {
            tmpDir = Files.createTempDirectory("captures-test");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @org.springframework.test.context.DynamicPropertySource
    static void captureProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("medbox.capture.dir", () -> tmpDir.toString());
        registry.add("medbox.capture.upload-secret", () -> "test-upload-secret");
        registry.add("medbox.capture.file-token-secret", () -> "test-file-secret");
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtProvider jwtProvider;
    @Autowired CaptureFileTokenService fileTokenService;
    @Autowired com.medbox.server.config.CaptureProperties captureProperties;

    @org.springframework.test.context.bean.override.mockito.MockitoBean CaptureMapper captureMapper;
    @org.springframework.test.context.bean.override.mockito.MockitoBean DeviceOwnerResolver deviceOwnerResolver;
    @org.springframework.test.context.bean.override.mockito.MockitoBean UserMapper userMapper;
    @org.springframework.test.context.bean.override.mockito.MockitoBean GuardianRelationMapper guardianRelationMapper;

    @TestConfiguration
    static class TestCaptureConfig {
        @Bean
        @Primary
        public DataSource dataSource() throws IOException {
            if (tmpDir == null) {
                tmpDir = Files.createTempDirectory("captures-test");
            }
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("captures-test")
                    .build();
        }

        @Bean
        @Primary
        public CaptureProperties captureProperties() {
            CaptureProperties p = new CaptureProperties();
            p.setDir(tmpDir.toString());
            p.setRetentionDaysUnread(30);
            p.setRetentionDaysRead(7);
            p.setUploadSecret("test-upload-secret");
            p.setFileTokenSecret("test-file-secret");
            p.setFileTokenTtlMinutes(5);
            return p;
        }
    }

    @BeforeAll
    static void warmUp() {
        TableInfoWarmup.init(Capture.class, User.class, GuardianRelation.class);
    }

    @BeforeEach
    void setUp() {
        // 默认让 isElder 查询返回老人（FAMILY 监护路径需要）
        when(userMapper.selectOne(any())).thenReturn(elder("u-1001"));
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @AfterAll
    static void cleanTmp() {
        if (tmpDir != null) {
            deleteRecursively(tmpDir.toFile());
        }
    }

    private static void deleteRecursively(java.io.File f) {
        if (f.isDirectory()) {
            java.io.File[] children = f.listFiles();
            if (children != null) {
                for (java.io.File c : children) {
                    deleteRecursively(c);
                }
            }
        }
        f.delete();
    }

    // ——————————————— 工具 ———————————————

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    /** 以监护人 u-2001 登录，并把其与老人 u-1001 的关系设为指定角色/ACTIVE。 */
    private String guardianBearer(com.medbox.server.domain.enums.RelationRole role) {
        GuardianRelation r = new GuardianRelation();
        r.setGuardianId("u-2001");
        r.setElderId("u-1001");
        r.setRole(role);
        r.setStatus(com.medbox.server.domain.enums.RelationStatus.ACTIVE);
        when(guardianRelationMapper.selectList(any())).thenReturn(List.of(r));
        when(guardianRelationMapper.selectOne(any())).thenReturn(r);
        return bearer("u-2001", "GUARDIAN");
    }

    private User elder(String id) {
        User u = new User();
        u.setUserId(id);
        u.setRole(com.medbox.server.domain.enums.UserRole.ELDER);
        return u;
    }

    private Capture capWithElder(String elderId) {
        Capture c = new Capture();
        c.setCaptureId("cap-1");
        c.setDeviceId("CAM01");
        c.setElderId(elderId);
        c.setReason("NORMAL");
        c.setMime("image/jpeg");
        c.setCapturedAt(CAP);
        c.setExpireAt(CAP.plusDays(30));
        c.setViewedAt(null);
        return c;
    }

    private Page<Capture> pageWithOne() {
        Page<Capture> page = new Page<>(1, 20);
        page.setRecords(List.of(capWithElder("u-1001")));
        page.setTotal(1);
        return page;
    }

    private String deviceSign(String deviceId, long ts) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(captureProperties.getUploadSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(
                mac.doFinal((deviceId + ts).getBytes(StandardCharsets.UTF_8)));
    }

    private MockMultipartHttpServletRequestBuilder uploadRequest(String deviceId, String sign, String ts) {
        MockMultipartFile file = new MockMultipartFile("file", "shot.jpg", "image/jpeg", "bytes".getBytes());
        return multipart("/api/v1/devices/" + deviceId + "/captures")
                .file(file)
                .param("reason", "WRONG_DRUG")
                .param("planId", "p-1")
                .param("capturedAt", "2026-10-04T00:00:00Z")
                .header("X-Device-Id", deviceId)
                .header("X-Device-Sign", sign)
                .header("X-Timestamp", ts);
    }

    // ——————————————— 用例 ———————————————

    @Test
    @DisplayName("上传：设备 HMAC 通过 → 落盘 + 返回 captureId")
    void 上传成功落盘() throws Exception {
        when(deviceOwnerResolver.resolveElderId("CAM01")).thenReturn("u-1001");
        when(captureMapper.selectByPlanWithinWindow(any(), any(), anyInt())).thenReturn(null);
        when(captureMapper.nextCaptureId()).thenReturn("cap-1001");
        when(captureMapper.insert(any(Capture.class))).thenReturn(1);

        long ts = Instant.now().getEpochSecond();
        mockMvc.perform(uploadRequest("CAM01", deviceSign("CAM01", ts), String.valueOf(ts)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.captureId").value("cap-1001"));

        assertTrue(Files.exists(Path.of(captureProperties.getDir(), "2026-10-04").resolve("cap-1001.jpg")), "图片应已落盘");
    }

    @Test
    @DisplayName("上传：设备签名错误 → 40101")
    void 上传签名错误401() throws Exception {
        long ts = Instant.now().getEpochSecond();
        mockMvc.perform(uploadRequest("CAM01", "bad-sign", String.valueOf(ts)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("去重：同 planId + 60s 窗口内只保留第一张，不再落盘/入库")
    void 去重返回既有() throws Exception {
        Capture existing = new Capture();
        existing.setCaptureId("cap-9001");
        existing.setExpireAt(CAP.plusDays(30));
        when(deviceOwnerResolver.resolveElderId("CAM01")).thenReturn("u-1001");
        when(captureMapper.selectByPlanWithinWindow(any(), any(), anyInt())).thenReturn(existing);

        long ts = Instant.now().getEpochSecond();
        mockMvc.perform(uploadRequest("CAM01", deviceSign("CAM01", ts), String.valueOf(ts)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.captureId").value("cap-9001"));

        verify(captureMapper, never()).insert(any(Capture.class));
    }

    @Test
    @DisplayName("列表：ACTIVE 监护人(FAMILY) → 200，含 fileToken")
    void 列表监护人可见() throws Exception {
        when(captureMapper.selectPage(any(), any())).thenReturn(pageWithOne());
        String bearer = guardianBearer(com.medbox.server.domain.enums.RelationRole.FAMILY);
        mockMvc.perform(get("/api/v1/captures").param("elderId", "u-1001").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].captureId").value("cap-1"))
                .andExpect(jsonPath("$.data.list[0].fileToken").exists());
    }

    @Test
    @DisplayName("详情：FAMILY 监护人 → 200；NURSE/DOCTOR → 40301")
    void 详情权限矩阵() throws Exception {
        when(captureMapper.selectByCaptureId("cap-1")).thenReturn(capWithElder("u-1001"));

        String family = guardianBearer(com.medbox.server.domain.enums.RelationRole.FAMILY);
        mockMvc.perform(get("/api/v1/captures/cap-1").header("Authorization", family))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.captureId").value("cap-1"));

        String nurse = guardianBearer(com.medbox.server.domain.enums.RelationRole.NURSE);
        mockMvc.perform(get("/api/v1/captures/cap-1").header("Authorization", nurse))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        String doctor = guardianBearer(com.medbox.server.domain.enums.RelationRole.DOCTOR);
        mockMvc.perform(get("/api/v1/captures/cap-1").header("Authorization", doctor))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("删除：仅 FAMILY 监护人可删；ELDER 本人 40301")
    void 删除权限矩阵() throws Exception {
        Path delFile = tmpDir.resolve("del-cap-1.jpg");
        Files.write(delFile, "DEL".getBytes(StandardCharsets.UTF_8));
        Capture c = capWithElder("u-1001");
        c.setFilePath(delFile.toAbsolutePath().toString());
        when(captureMapper.selectByCaptureId("cap-1")).thenReturn(c);
        when(captureMapper.deleteByCaptureId("cap-1")).thenReturn(1);

        String family = guardianBearer(com.medbox.server.domain.enums.RelationRole.FAMILY);
        mockMvc.perform(delete("/api/v1/captures/cap-1").header("Authorization", family))
                .andExpect(status().isOk());

        // 老人本人无 WRITE_CAPTURE 权限
        mockMvc.perform(delete("/api/v1/captures/cap-1").header("Authorization", bearer("u-1001", "ELDER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("文件流：监护人 + 有效 fileToken → 200，首读标记 viewed_at")
    void 文件流首读标记() throws Exception {
        Path img = tmpDir.resolve("cap-1.jpg");
        Files.write(img, "IMG-BYTES".getBytes(StandardCharsets.UTF_8));
        Capture c = capWithElder("u-1001");
        c.setFilePath(img.toAbsolutePath().toString());
        when(captureMapper.selectByCaptureId("cap-1")).thenReturn(c);

        String token = fileTokenService.issue("cap-1");
        String bearer = guardianBearer(com.medbox.server.domain.enums.RelationRole.FAMILY);
        mockMvc.perform(get("/api/v1/captures/cap-1/file").param("token", token).header("Authorization", bearer))
                .andExpect(status().isOk());
        verify(captureMapper).markViewed(eq("cap-1"), any(), any());
    }

    @Test
    @DisplayName("文件流：fileToken 与路径不符 → 40001")
    void 文件流Token不符400() throws Exception {
        when(captureMapper.selectByCaptureId("cap-1")).thenReturn(capWithElder("u-1001"));
        String wrongToken = fileTokenService.issue("cap-2");
        String bearer = guardianBearer(com.medbox.server.domain.enums.RelationRole.FAMILY);
        mockMvc.perform(get("/api/v1/captures/cap-1/file").param("token", wrongToken).header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("未登录访问受保护端点 → 40101")
    void 未登录401() throws Exception {
        mockMvc.perform(get("/api/v1/captures").param("elderId", "u-1001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }
}
