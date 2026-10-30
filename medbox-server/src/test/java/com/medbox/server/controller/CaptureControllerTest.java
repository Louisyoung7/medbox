package com.medbox.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.service.DeviceCaptureAuth;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.dto.PageResult;
import com.medbox.server.dto.capture.CaptureResponse;
import com.medbox.server.dto.capture.CaptureUploadResponse;
import com.medbox.server.service.CaptureService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@link CaptureController} 契约测试（@WebMvcTest）：只验证参数绑定 + 调用 Service 的接线，
 * 文件端点返回二进制（不走 R 包裹）。鉴权矩阵由 {@code CaptureCoreWebIntegrationTest} 覆盖。
 *
 * <p>参照 {@code DeviceControllerTest}：@WebMvcTest 会加载真实 {@code AuthInterceptor}，
 * 故 GET/DELETE 需带由 {@link JwtProvider} 签发的有效 JWT；上传端点被拦截器排除，无需 JWT。
 */
@WebMvcTest(controllers = CaptureController.class)
@org.springframework.context.annotation.Import(JwtProvider.class)
@DisplayName("CaptureController：接口契约")
class CaptureControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtProvider jwtProvider;
    @MockitoBean CaptureService captureService;
    @MockitoBean DeviceCaptureAuth deviceCaptureAuth;

    private String bearer;

    @BeforeEach
    void setUp() {
        bearer = "Bearer " + jwtProvider.issue("u-1001", "FAMILY", TokenType.ACCESS);
    }

    private CaptureResponse sampleResponse() {
        return new CaptureResponse("cap-1", "CAM01", "u-1001", "m-1", "p-1", "NORMAL", null,
                "image/jpeg", 10, new BigDecimal("0.9"),
                OffsetDateTime.parse("2026-10-04T00:00:00Z"), null,
                OffsetDateTime.parse("2026-11-03T00:00:00Z"), "tok-1");
    }

    @Test
    @DisplayName("上传：设备 HMAC 校验通过 → 200 返回 captureId/expireAt")
    void 上传成功() throws Exception {
        when(deviceCaptureAuth.verify(eq("CAM01"), any(), any())).thenReturn("CAM01");
        when(captureService.upload(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new CaptureUploadResponse("cap-1", OffsetDateTime.parse("2026-11-03T00:00:00Z")));

        MockMultipartFile file = new MockMultipartFile("file", "shot.jpg", "image/jpeg", "bytes".getBytes());
        mockMvc.perform(multipart("/api/v1/devices/CAM01/captures")
                        .file(file)
                        .param("reason", "LOW_CONFIDENCE")
                        .param("planId", "p-1")
                        .header("X-Device-Id", "CAM01")
                        .header("X-Device-Sign", "sign")
                        .header("X-Timestamp", "123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.captureId").value("cap-1"));
    }

    @Test
    @DisplayName("上传：设备签名错误 → 40101")
    void 上传签名错误401() throws Exception {
        when(deviceCaptureAuth.verify(any(), any(), any()))
                .thenThrow(BizException.of(ErrorCode.UNAUTHORIZED, "设备签名校验失败"));
        MockMultipartFile file = new MockMultipartFile("file", "shot.jpg", "image/jpeg", "bytes".getBytes());
        mockMvc.perform(multipart("/api/v1/devices/CAM01/captures")
                        .file(file)
                        .header("X-Device-Id", "CAM01")
                        .header("X-Device-Sign", "bad")
                        .header("X-Timestamp", "123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("上传：X-Device-Id 与路径不一致 → 40001")
    void 上传设备ID不符400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "shot.jpg", "image/jpeg", "bytes".getBytes());
        mockMvc.perform(multipart("/api/v1/devices/CAM01/captures")
                        .file(file)
                        .header("X-Device-Id", "OTHER")
                        .header("X-Device-Sign", "sign")
                        .header("X-Timestamp", "123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("列表：带 JWT → 200，返回含 fileToken 的元数据（不含文件流）")
    void 列表成功() throws Exception {
        when(captureService.list(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(PageResult.of(List.of(sampleResponse()), 1L, 1, 20));

        MockHttpServletRequestBuilder req = get("/api/v1/captures")
                .param("elderId", "u-1001")
                .param("reason", "NORMAL")
                .header("Authorization", bearer);
        mockMvc.perform(req)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].captureId").value("cap-1"))
                .andExpect(jsonPath("$.data.list[0].fileToken").value("tok-1"));
    }

    @Test
    @DisplayName("详情：带 JWT → 200")
    void 详情成功() throws Exception {
        when(captureService.detail("cap-1")).thenReturn(sampleResponse());
        mockMvc.perform(get("/api/v1/captures/cap-1").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.captureId").value("cap-1"));
    }

    @Test
    @DisplayName("删除：带 JWT → 200")
    void 删除成功() throws Exception {
        mockMvc.perform(delete("/api/v1/captures/cap-1").header("Authorization", bearer))
                .andExpect(status().isOk());
    }
}
