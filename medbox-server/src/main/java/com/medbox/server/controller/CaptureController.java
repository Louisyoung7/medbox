package com.medbox.server.controller;

import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.service.DeviceCaptureAuth;
import com.medbox.server.dto.PageResult;
import com.medbox.server.dto.R;
import com.medbox.server.dto.capture.CaptureResponse;
import com.medbox.server.dto.capture.CaptureUploadResponse;
import com.medbox.server.service.CaptureService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 抓拍图片接口（见文档 02 的 4.1）。
 *
 * <p>本控制器只做参数绑定 + {@code R<T>} 包装；<b>所有权限判定在 {@link CaptureService} 里</b>
 * （与 {@code DeviceController} 同一形态）。五个端点：
 * <ul>
 *   <li>{@code POST /api/v1/devices/{deviceId}/captures} —— 设备 HMAC 上传（从 JWT 拦截器排除，本类自带设备鉴权）；</li>
 *   <li>{@code GET  /api/v1/captures} —— 列表（按 reason / 时间区间过滤）；</li>
 *   <li>{@code GET  /api/v1/captures/{captureId}} —— 详情；</li>
 *   <li>{@code GET  /api/v1/captures/{captureId}/file} —— 图片流（fileToken 校验 + 监护关系复核）；</li>
 *   <li>{@code DELETE /api/v1/captures/{captureId}} —— 删除（仅 FAMILY）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class CaptureController {

    private final CaptureService captureService;
    private final DeviceCaptureAuth deviceCaptureAuth;

    public CaptureController(CaptureService captureService, DeviceCaptureAuth deviceCaptureAuth) {
        this.captureService = captureService;
        this.deviceCaptureAuth = deviceCaptureAuth;
    }

    /**
     * 设备上传抓拍：摄像头子设备走设备身份（HMAC 签名 + ±5 分钟时间窗），不走用户 JWT。
     * 路径 deviceId 必须与 Header {@code X-Device-Id} 一致，签名对其计算。
     */
    @PostMapping("/devices/{deviceId}/captures")
    public R<CaptureUploadResponse> upload(@PathVariable("deviceId") String deviceId,
                                          HttpServletRequest request,
                                          @RequestParam("file") MultipartFile file,
                                          @RequestParam(value = "reason", required = false) String reason,
                                          @RequestParam(value = "medicineId", required = false) String medicineId,
                                          @RequestParam(value = "planId", required = false) String planId,
                                          @RequestParam(value = "confidence", required = false) BigDecimal confidence,
                                          @RequestParam(value = "capturedAt", required = false)
                                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime capturedAt) {
        String headerDeviceId = request.getHeader("X-Device-Id");
        BizAssert.badRequestIf(headerDeviceId == null || !headerDeviceId.equals(deviceId),
                "X-Device-Id 与路径 deviceId 不一致");
        deviceCaptureAuth.verify(deviceId, request.getHeader("X-Device-Sign"), request.getHeader("X-Timestamp"));
        return R.ok(captureService.upload(deviceId, file, reason, medicineId, planId, confidence, capturedAt));
    }

    @GetMapping("/captures")
    public R<PageResult<CaptureResponse>> list(@RequestParam("elderId") String elderId,
                                              @RequestParam(value = "reason", required = false) String reason,
                                              @RequestParam(value = "from", required = false)
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                                              @RequestParam(value = "to", required = false)
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
                                              @RequestParam(value = "page", required = false) Integer page,
                                              @RequestParam(value = "size", required = false) Integer size) {
        return R.ok(captureService.list(elderId, reason, from, to,
                page == null ? 1 : page, size == null ? 20 : size));
    }

    @GetMapping("/captures/{captureId}")
    public R<CaptureResponse> detail(@PathVariable("captureId") String captureId) {
        return R.ok(captureService.detail(captureId));
    }

    /**
     * 读取图片流：返回二进制（不走统一响应体）。fileToken 失效或监护关系不符由 Service 抛 40101/40301。
     */
    @GetMapping("/captures/{captureId}/file")
    public ResponseEntity<Resource> file(@RequestParam("token") String token,
                                        @PathVariable("captureId") String captureId) {
        return captureService.file(token, captureId);
    }

    @DeleteMapping("/captures/{captureId}")
    public R<Void> delete(@PathVariable("captureId") String captureId) {
        captureService.delete(captureId);
        return R.ok();
    }
}
