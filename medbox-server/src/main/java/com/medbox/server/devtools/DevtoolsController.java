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
 * <p><b>它不是后门</b>：端点由 {@code @ConditionalOnProperty(medbox.devtools.enabled)} 控制装配，
 * 该属性为 false 时 {@code DevtoolsController} 根本不创建（访问即 40401），生产零暴露面。
 * 因此这里<b>不要求登录</b>——能在本地调通它，本身就说明 {@code devtools.enabled=true}（即本地开发模式），
 * 再加登录只是冗余摩擦；请求体/响应也完全基于写死的最小数据集 ID，与调用者身份无关。
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
