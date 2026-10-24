package com.medbox.server.devtools;

import com.medbox.server.devtools.dto.MqttPublishRequest;
import com.medbox.server.devtools.dto.SeedResult;
import com.medbox.server.dto.R;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 联调辅助调试端点：<b>默认不装配</b>（{@code medbox.devtools.enabled=false}）。
 *
 * <p>提供两类能力（见文档 02 的 1.4 / 03 第 5 章）：
 * <ul>
 *   <li>{@code POST /api/v1/_debug/seed} —— 一键播种最小链路数据集（1 监护人 + 1 老人 + 药箱 + 传感器 + 药品/库存/计划）；</li>
 *   <li>{@code POST /api/v1/_debug/mqtt/publish} —— 服务端模拟设备上报，等价于 {@code mosquitto_pub}。</li>
 * </ul>
 *
 * <p><b>它不是后门</b>：路径落在 {@code /api/v1/**} → 被 {@code AuthInterceptor} 强制登录；
 * 再叠加 {@code medbox.devtools.enabled} 属性门禁，生产默认零装配、零暴露面。
 */
@RestController
@RequestMapping("/api/v1/_debug")
@ConditionalOnProperty(prefix = "medbox.devtools", name = "enabled", havingValue = "true")
public class DevtoolsController {

    private final DevtoolsSeedService seedService;
    private final DevtoolsMqttSimService mqttSimService;

    public DevtoolsController(DevtoolsSeedService seedService, DevtoolsMqttSimService mqttSimService) {
        this.seedService = seedService;
        this.mqttSimService = mqttSimService;
    }

    @PostMapping("/seed")
    public R<SeedResult> seed(@RequestParam(defaultValue = "false") boolean reset) {
        return R.ok(seedService.seed(reset));
    }

    @PostMapping("/mqtt/publish")
    public R<Void> publishMqtt(@RequestBody(required = false) MqttPublishRequest request) {
        mqttSimService.publish(request.deviceId(), request.subTopic(), request.productKey(), request.payload());
        return R.ok();
    }
}
