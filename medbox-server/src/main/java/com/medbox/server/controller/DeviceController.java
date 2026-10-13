package com.medbox.server.controller;

import com.medbox.server.dto.PageResult;
import com.medbox.server.dto.R;
import com.medbox.server.dto.device.DeviceDetailResponse;
import com.medbox.server.dto.device.DeviceEventResponse;
import com.medbox.server.dto.device.DeviceRegisterRequest;
import com.medbox.server.dto.device.DeviceRegisterResponse;
import com.medbox.server.dto.device.DeviceStatusResponse;
import com.medbox.server.dto.device.DeviceSummaryResponse;
import com.medbox.server.service.DeviceService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备管理接口（见文档 02 第 4 章）。
 *
 * <p>只做参数绑定与 {@code R<T>} 包装，**一切权限判定都在 {@link DeviceService} 里**
 * （与 {@code UserController} 同一形态）：注册走 {@code WRITE_DEVICE}，四个查询走 {@code READ_DEVICE}。
 * 未登录由 {@code AuthInterceptor} 统一兜 40101，这里不判。
 *
 * <p><b>{@code deviceId} 一律指药箱主控</b>：传感器子设备不是独立的资源入口，
 * 它们随主控登记、在详情的 {@code sensors} 里展示（文档 02 第 4 章表下说明）。
 * 传子设备编号进来按 40301 处理，不返回 40401（沿用"不区分不存在与无权限"的口径）。
 */
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    /** 药箱注册并绑定到老人（注册即生效；传感器子设备随主控一并登记）。 */
    @PostMapping("/register")
    public R<DeviceRegisterResponse> register(@Valid @RequestBody DeviceRegisterRequest request) {
        return R.ok(deviceService.register(request));
    }

    /** 我监护老人的药箱列表（只列主控，含在线状态与子设备数量）。 */
    @GetMapping
    public R<List<DeviceSummaryResponse>> list() {
        return R.ok(deviceService.list());
    }

    /** 设备详情：档案 + 下属传感器子设备及其在线状态。 */
    @GetMapping("/{deviceId}")
    public R<DeviceDetailResponse> detail(@PathVariable("deviceId") String deviceId) {
        return R.ok(deviceService.detail(deviceId));
    }

    /** 实时状态：在线与否 + 最后心跳时间。 */
    @GetMapping("/{deviceId}/status")
    public R<DeviceStatusResponse> status(@PathVariable("deviceId") String deviceId) {
        return R.ok(deviceService.status(deviceId));
    }

    /**
     * 设备事件流水（分页，按时间倒序）。
     *
     * <p>{@code page} / {@code size} 不传时由 {@code medbox.device.*} 的默认值兜底，
     * 因此这里刻意用 {@code Integer} 而不是 {@code defaultValue = "1"} —— 默认值只有一处来源，
     * 不会出现在注解里写一份、配置文件里写一份的漂移。
     */
    @GetMapping("/{deviceId}/events")
    public R<PageResult<DeviceEventResponse>> events(@PathVariable("deviceId") String deviceId,
                                                     @RequestParam(value = "page", required = false) Integer page,
                                                     @RequestParam(value = "size", required = false) Integer size) {
        return R.ok(deviceService.events(deviceId, page, size));
    }
}
