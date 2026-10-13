package com.medbox.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.dto.PageResult;
import com.medbox.server.dto.device.DeviceDetailResponse;
import com.medbox.server.dto.device.DeviceRegisterRequest;
import com.medbox.server.dto.device.DeviceRegisterResponse;
import com.medbox.server.dto.device.DeviceStatusResponse;
import com.medbox.server.dto.device.DeviceSummaryResponse;
import com.medbox.server.service.DeviceService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code DeviceController} 的 Web 层契约测试（见文档 02 第 4 章）。
 *
 * <p>打桩 {@link DeviceService}，只验**接口契约**：路径、鉴权、参数校验、错误码与 HTTP 状态码、
 * 响应字段形状。业务规则（注册冲突、行级过滤、时间线）在
 * {@code DeviceCoreWebIntegrationTest} 里走真实 Service 验证。
 *
 * <p>token 由真实 {@link JwtProvider} 签发，第 ① 步不绕过（与 {@code UserControllerTest} 同款）。
 */
@WebMvcTest(controllers = DeviceController.class)
@Import(JwtProvider.class)
class DeviceControllerTest {

    private static final String ELDER_ID = "u-1001";
    private static final String GUARDIAN_ID = "u-2001";
    private static final String DEVICE_ID = "BOXA1001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private DeviceService deviceService;

    @AfterEach
    void tearDown() {
        com.medbox.server.common.security.AuthContext.clear();
    }

    // ————————————————— 鉴权 —————————————————

    @Test
    @DisplayName("① 未带 Authorization → 40101 / HTTP 401")
    void 未登录返回_40101() throws Exception {
        mockMvc.perform(get("/api/v1/devices"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    // ————————————————— 注册 —————————————————

    @Test
    @DisplayName("注册成功 → 200，回显主控与子设备")
    void 注册成功() throws Exception {
        when(deviceService.register(any(DeviceRegisterRequest.class))).thenReturn(registerResponse());

        mockMvc.perform(post("/api/v1/devices/register")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"BOXA1001","elderId":"u-1001",
                                 "sensors":[{"deviceId":"BOXA1001-S-TH01","deviceType":"TEMP_HUMI"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.deviceId").value(DEVICE_ID))
                .andExpect(jsonPath("$.data.deviceType").value("MAIN"))
                .andExpect(jsonPath("$.data.onlineState").value("OFFLINE"))
                .andExpect(jsonPath("$.data.sensors.length()").value(1))
                .andExpect(jsonPath("$.data.sensors[0].deviceType").value("TEMP_HUMI"));
    }

    @Test
    @DisplayName("deviceId 格式非法 → 40001")
    void 设备编号格式非法返回_40001() throws Exception {
        mockMvc.perform(post("/api/v1/devices/register")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"ab","elderId":"u-1001"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("elderId 缺失 → 40001")
    void 缺少老人编号返回_40001() throws Exception {
        mockMvc.perform(post("/api/v1/devices/register")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"BOXA1001"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("设备编号已登记 → 40901 / HTTP 409")
    void 重复登记返回_40901() throws Exception {
        when(deviceService.register(any(DeviceRegisterRequest.class)))
                .thenThrow(BizException.of(ErrorCode.CONFLICT, "该设备编号已登记"));

        mockMvc.perform(post("/api/v1/devices/register")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"BOXA1001","elderId":"u-1001"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("监护关系未生效 → 40302 / HTTP 403")
    void 监护关系未生效返回_40302() throws Exception {
        when(deviceService.register(any(DeviceRegisterRequest.class)))
                .thenThrow(BizException.of(ErrorCode.GUARDIAN_PENDING));

        mockMvc.perform(post("/api/v1/devices/register")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"BOXA1001","elderId":"u-1001"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40302));
    }

    @Test
    @DisplayName("老人本人注册 → 40301（WRITE_DEVICE 只对 FAMILY 开放）")
    void 老人自注册返回_40301() throws Exception {
        when(deviceService.register(any(DeviceRegisterRequest.class)))
                .thenThrow(BizException.of(ErrorCode.FORBIDDEN, "无权限访问该资源（WRITE_DEVICE）"));

        mockMvc.perform(post("/api/v1/devices/register")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"BOXA1001","elderId":"u-1001"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    // ————————————————— 四个查询 —————————————————

    @Test
    @DisplayName("设备列表 → 200，回显主控与子设备数量")
    void 设备列表() throws Exception {
        when(deviceService.list()).thenReturn(List.of(
                new DeviceSummaryResponse(DEVICE_ID, "MAIN", ELDER_ID, "medbox-box",
                        "Asia/Shanghai", "ONLINE", OffsetDateTime.parse("2026-10-13T01:00:00Z"), 3)));

        mockMvc.perform(get("/api/v1/devices")
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].deviceId").value(DEVICE_ID))
                .andExpect(jsonPath("$.data[0].sensorCount").value(3));
    }

    @Test
    @DisplayName("设备详情 → 200，含子设备列表")
    void 设备详情() throws Exception {
        when(deviceService.detail(DEVICE_ID)).thenReturn(new DeviceDetailResponse(
                DEVICE_ID, "MAIN", ELDER_ID, "medbox-box", "Asia/Shanghai", "ONLINE",
                OffsetDateTime.parse("2026-10-13T01:00:00Z"),
                OffsetDateTime.parse("2026-10-12T00:00:00Z"),
                List.of(new DeviceDetailResponse.SensorSummary(
                        "BOXA1001-S-TH01", "TEMP_HUMI", "OFFLINE", null))));

        mockMvc.perform(get("/api/v1/devices/{deviceId}", DEVICE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(GUARDIAN_ID, "GUARDIAN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deviceId").value(DEVICE_ID))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.data.sensors.length()").value(1))
                .andExpect(jsonPath("$.data.sensors[0].onlineState").value("OFFLINE"));
    }

    @Test
    @DisplayName("实时状态 → 200，只含在线状态与最后心跳")
    void 实时状态() throws Exception {
        when(deviceService.status(DEVICE_ID)).thenReturn(new DeviceStatusResponse(
                DEVICE_ID, "ONLINE", OffsetDateTime.parse("2026-10-13T01:00:00Z")));

        mockMvc.perform(get("/api/v1/devices/{deviceId}/status", DEVICE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onlineState").value("ONLINE"))
                .andExpect(jsonPath("$.data.lastHeartbeat").exists());
    }

    @Test
    @DisplayName("事件流水：不传分页参数时传给 Service 的是 null（默认值由 Service 兜）")
    void 事件流水默认分页() throws Exception {
        when(deviceService.events(anyString(), org.mockito.ArgumentMatchers.<Integer>isNull(),
                org.mockito.ArgumentMatchers.<Integer>isNull()))
                .thenReturn(PageResult.empty(1, 20));

        mockMvc.perform(get("/api/v1/devices/{deviceId}/events", DEVICE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.list.length()").value(0));
    }

    @Test
    @DisplayName("事件流水：分页参数原样传给 Service")
    void 事件流水传分页参数() throws Exception {
        when(deviceService.events(anyString(), anyInt(), anyInt())).thenReturn(PageResult.empty(2, 50));

        mockMvc.perform(get("/api/v1/devices/{deviceId}/events", DEVICE_ID)
                        .param("page", "2")
                        .param("size", "50")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ELDER_ID, "ELDER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(50));

        verify(deviceService).events(DEVICE_ID, 2, 50);
    }

    @Test
    @DisplayName("访问他人设备 → 40301 / HTTP 403，data 不出现")
    void 越权访问返回_40301() throws Exception {
        when(deviceService.status(DEVICE_ID))
                .thenThrow(BizException.of(ErrorCode.FORBIDDEN, "无权限访问该资源"));

        mockMvc.perform(get("/api/v1/devices/{deviceId}/status", DEVICE_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer("u-9999", "GUARDIAN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ————————————————— 辅助 —————————————————

    private String bearer(String userId, String role) {
        return "Bearer " + jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    private static DeviceRegisterResponse registerResponse() {
        return new DeviceRegisterResponse(
                DEVICE_ID, "MAIN", ELDER_ID, "medbox-box", "Asia/Shanghai", "OFFLINE",
                OffsetDateTime.parse("2026-10-13T01:00:00Z"),
                List.of(new DeviceRegisterResponse.RegisteredSensor("BOXA1001-S-TH01", "TEMP_HUMI")));
    }
}
