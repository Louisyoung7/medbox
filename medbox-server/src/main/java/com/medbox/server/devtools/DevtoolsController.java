package com.medbox.server.devtools;

import com.medbox.server.devtools.dto.MqttPublishRequest;
import com.medbox.server.devtools.dto.SeedResult;
import com.medbox.server.dto.R;
import com.medbox.server.service.PlanReminderScanner;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
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
    private final PlanReminderScanner planReminderScanner;

    public DevtoolsController(DevtoolsSeedService seedService, DevtoolsMqttSimService mqttSimService,
                              PlanReminderScanner planReminderScanner) {
        this.seedService = seedService;
        this.mqttSimService = mqttSimService;
        this.planReminderScanner = planReminderScanner;
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

    /**
     * 手动触发一次服药提醒扫描（绕过定时器，便于联调）。
     *
     * <p>与定时任务走完全相同代码路径（去重 / 已服两道拦截照常生效）。{@code now} 为可选 ISO 时刻
     * （{@code 2026-10-25T06:00:00Z} 或带偏移的 {@code 2026-10-25T14:00:00+08:00}），缺省用系统时钟——
     * 联调时常用它把"窗口"对准某个计划的服药时刻，立即看到 REMINDER 帧。
     *
     * <p>受 {@code medbox.plan-reminder.enabled} 约束：若被关掉，这里也只返回空汇总、不推送。
     */
    @PostMapping("/plan-reminder/trigger")
    public R<PlanReminderScanner.ScanSummary> triggerPlanReminder(
            @RequestParam(required = false) String now) {
        Clock clock = (now == null || now.isBlank())
                ? Clock.systemUTC()
                : Clock.fixed(parseNow(now), ZoneOffset.UTC);
        return R.ok(planReminderScanner.scan(clock));
    }

    private static Instant parseNow(String raw) {
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ex) {
            return OffsetDateTime.parse(raw).toInstant();
        }
    }
}
