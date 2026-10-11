package com.medbox.server.ws;

import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.common.security.Permission;
import com.medbox.server.dto.R;
import com.medbox.server.service.AccessService;
import com.medbox.server.ws.payload.AlarmPayload;
import com.medbox.server.ws.payload.DeviceStatusPayload;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调试推送端点：<b>默认不装配</b>（{@code medbox.ws.debug-endpoint-enabled=false}）。
 *
 * <p><b>为什么需要它</b>：07 清单要求验收"wscat 连上后能收到后端主动推送的告警帧"，而真正的告警源
 * （{@code feat/miss-alarm}）是 P0 第 10 步、还没做。没有触发源就只能靠集成测试证明链路通，
 * 人工验收无从下手。故给一个开关控制的入口：<b>本地联调开、生产必须关</b>。
 *
 * <p><b>它不是后门</b>：路径落在 {@code /api/v1/**} → 由 {@code AuthInterceptor} 强制登录；
 * 再经 {@code AccessService.require(elderId, READ_ALARM)} 校验归属，越权照样 40301，
 * 能推的主题不超出调用者本来就能看到的范围。
 *
 * <p>{@code payload} 省略时按帧类型造一条示例帧，省得每次手写 JSON。
 */
@RestController
@RequestMapping("/api/v1/_debug")
@ConditionalOnProperty(prefix = "medbox.ws", name = "debug-endpoint-enabled", havingValue = "true")
public class WsDebugController {

    /** 调试推送请求。 */
    public record PublishRequest(String elderId, String type, Map<String, Object> payload) {
    }

    private final WsPushService pushService;
    private final AccessService accessService;

    public WsDebugController(WsPushService pushService, AccessService accessService) {
        this.pushService = pushService;
        this.accessService = accessService;
    }

    @PostMapping("/ws/publish")
    public R<WsPushService.WsPushResult> publish(@RequestBody(required = false) PublishRequest request) {
        BizAssert.isTrue(request != null, ErrorCode.BAD_REQUEST, "请求体不能为空");
        BizAssert.notBlank(request.elderId(), "elderId 不能为空");
        WsFrameType type = parseType(request.type());

        // 与 REST 同一套归属校验：能推哪个人，取决于你能看哪个人
        accessService.require(request.elderId(), Permission.READ_ALARM);

        Object payload = request.payload() != null ? request.payload() : samplePayload(type, request.elderId());
        WsPushService.WsPushResult result = pushService.publish(WsTopic.elder(request.elderId()), type, payload);
        return R.ok(result);
    }

    private static WsFrameType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return WsFrameType.ALARM;
        }
        WsFrameType type;
        try {
            type = WsFrameType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "未知帧类型：" + raw);
        }
        BizAssert.isTrue(type.pushable(), ErrorCode.BAD_REQUEST, "该帧类型不允许主动推送：" + type);
        return type;
    }

    private static Object samplePayload(WsFrameType type, String elderId) {
        if (type == WsFrameType.DEVICE_STATUS) {
            return new DeviceStatusPayload("BOXA1001", "MAIN", true, null);
        }
        return new AlarmPayload("a-debug-0001", "MISS", "WARN",
                "调试：08:00 计划服药未按时服用", elderId, List.of("m-205"));
    }
}
