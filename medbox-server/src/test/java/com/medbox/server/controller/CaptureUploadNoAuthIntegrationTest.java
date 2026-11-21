package com.medbox.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.medbox.server.common.security.DeviceOwnerResolver;
import com.medbox.server.domain.Capture;
import com.medbox.server.mapper.CaptureMapper;
import com.medbox.server.service.AccessService;
import com.medbox.server.service.CaptureFileTokenService;
import com.medbox.server.service.DispenseConsumeService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /api/v1/devices/{deviceId}/captures} 无签名上传的集成测试。
 *
 * <p>本次变更去掉了上传侧 HMAC（{@code X-Device-Sign} / {@code X-Timestamp} 与 ±5min 时间窗），
 * 只保留 {@code X-Device-Id} 与路径一致 + 库内登记归属校验。本测试走真实
 * {@code CaptureService}（落盘 / 去重 / fileToken 签发），Mapper 与归属解析打桩，
 * H2 @Primary 供事务开连接，不连外部 PostgreSQL。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("抓拍上传无签名：身份校验与 fileToken 回归")
class CaptureUploadNoAuthIntegrationTest {

    private static final String CAMERA_ID = "BOXA-DEBUG-01-S-CAM01";
    private static final String ELDER_ID = "e-1001";
    private static Path tmpDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        if (tmpDir == null) {
            tmpDir = Files.createTempDirectory("capture-noauth-it");
        }
        r.add("medbox.capture.dir", () -> tmpDir.toString());
        r.add("medbox.capture.file-token-secret", () -> "test-file-token-secret-for-noauth-it-0123456789");
    }

    /** 嵌入式 H2 替换 PostgreSQL DataSource（CI 无外部 DB，事务可开、不跑真实 SQL）。 */
    @TestConfiguration
    static class H2DataSourceConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-test-capture-noauth")
                    .build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CaptureFileTokenService fileTokenService;

    @MockitoBean
    private CaptureMapper captureMapper;

    @MockitoBean
    private DeviceOwnerResolver deviceOwnerResolver;

    @MockitoBean
    private AccessService accessService;

    @MockitoBean
    private DispenseConsumeService dispenseConsumeService;

    @BeforeEach
    void setUp() {
        when(deviceOwnerResolver.resolveElderId(CAMERA_ID)).thenReturn(ELDER_ID);
        when(captureMapper.nextCaptureId()).thenReturn("cap-noauth-1");
        when(captureMapper.insert(any(Capture.class))).thenReturn(1);
        when(dispenseConsumeService.consume(any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("不带 X-Device-Sign/X-Timestamp 上传成功：落库 + 返回 captureId")
    void 无签名上传成功() throws Exception {
        mockMvc.perform(multipart("/api/v1/devices/{deviceId}/captures", CAMERA_ID)
                        .file(image())
                        .param("reason", "NORMAL")
                        .param("planId", "p-3301")
                        .param("cellNo", "1")
                        .param("takenResult", "TAKEN")
                        .header("X-Device-Id", CAMERA_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.captureId").value("cap-noauth-1"));

        verify(captureMapper).insert(any(Capture.class));
    }

    @Test
    @DisplayName("X-Device-Id 与路径不一致 → 40001，且不落库")
    void 身份不一致返回40001() throws Exception {
        mockMvc.perform(multipart("/api/v1/devices/{deviceId}/captures", CAMERA_ID)
                        .file(image())
                        .param("reason", "NORMAL")
                        .param("takenResult", "UNTAKEN")
                        .header("X-Device-Id", "OTHER-CAMERA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        verify(captureMapper, never()).insert(any(Capture.class));
    }

    @Test
    @DisplayName("缺少 X-Device-Id → 40001，且不落库")
    void 缺设备身份Header返回40001() throws Exception {
        mockMvc.perform(multipart("/api/v1/devices/{deviceId}/captures", CAMERA_ID)
                        .file(image())
                        .param("reason", "NORMAL")
                        .param("takenResult", "UNTAKEN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        verify(captureMapper, never()).insert(any(Capture.class));
    }

    @Test
    @DisplayName("fileToken 签发/校验链路未受删签名影响（读图照常可用）")
    void fileToken链路仍正常() {
        String token = fileTokenService.issue("cap-noauth-1");
        org.junit.jupiter.api.Assertions.assertEquals("cap-noauth-1", fileTokenService.verify(token));
    }

    private static MockMultipartFile image() {
        return new MockMultipartFile("file", "capture.jpg", MediaType.IMAGE_JPEG_VALUE, "fake-image".getBytes());
    }
}
