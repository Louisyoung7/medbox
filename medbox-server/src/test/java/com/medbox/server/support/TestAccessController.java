package com.medbox.server.support;

import com.medbox.server.common.security.AccessIdentity;
import com.medbox.server.common.security.Permission;
import com.medbox.server.dto.R;
import com.medbox.server.service.AccessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅测试源集使用的控制器：造出"受权限保护的业务接口"，供 {@code AccessWebIntegrationTest}
 * 用 MockMvc 打真实链路（Controller → 真实 {@code AccessService} → 打桩 Mapper）。
 *
 * <p>放在 test 源集，不会打进生产包；生产代码不要引用它。
 */
@RestController
@RequestMapping("/api/v1/_test/access")
public class TestAccessController {

    private final AccessService accessService;

    public TestAccessController(AccessService accessService) {
        this.accessService = accessService;
    }

    /** 模拟"按老人读服药计划"：{@code GET /api/v1/_test/access/plan?elderId=u-1001} */
    @GetMapping("/plan")
    public R<String> readPlan(@RequestParam("elderId") String elderId) {
        AccessIdentity identity = accessService.require(elderId, Permission.READ_PLAN);
        return R.ok(identity.kind().name());
    }

    /** 模拟"按老人改服药计划"：{@code GET /api/v1/_test/access/plan-write?elderId=u-1001} */
    @GetMapping("/plan-write")
    public R<String> writePlan(@RequestParam("elderId") String elderId) {
        AccessIdentity identity = accessService.require(elderId, Permission.WRITE_PLAN);
        return R.ok(identity.kind().name());
    }

    /** 模拟"按设备读实时状态"（走 DeviceOwnerResolver 扩展点） */
    @GetMapping("/device")
    public R<String> readDevice(@RequestParam("deviceId") String deviceId) {
        AccessIdentity identity = accessService.requireDevice(deviceId, Permission.READ_DEVICE);
        return R.ok(identity.kind().name());
    }

    /** 模拟列表接口的行级过滤：返回可见老人数量 */
    @GetMapping("/visible")
    public R<Integer> visibleCount() {
        return R.ok(accessService.visibleElderIds().size());
    }
}
