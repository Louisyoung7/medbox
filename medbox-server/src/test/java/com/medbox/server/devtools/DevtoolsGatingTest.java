package com.medbox.server.devtools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medbox.server.config.DevtoolsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 门禁确定性验证：{@code DevtoolsController} 由 {@code @ConditionalOnProperty(medbox.devtools.enabled)}
 * 控制装配。本测试直接验证两种属性下的 Bean 注册结果，证明生产默认（false）下零暴露面，
 * 不依赖 MockMvc / 拦截器整条服务链，结果稳定可重复。
 */
class DevtoolsGatingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    private ApplicationContextRunner base(boolean enabled) {
        return runner
                .withUserConfiguration(DevtoolsConfig.class)
                .withPropertyValues("medbox.devtools.enabled=" + enabled)
                .withBean(DevtoolsController.class, () -> new DevtoolsController(
                        Mockito.mock(DevtoolsSeedService.class),
                        Mockito.mock(DevtoolsMqttSimService.class),
                        Mockito.mock(com.medbox.server.service.PlanReminderScanner.class)));
    }

    @Test
    @DisplayName("devtools 关闭（默认）：控制器不装配")
    void disabledNotRegistered() {
        base(false).run(ctx -> assertFalse(ctx.containsBean("devtoolsController"),
                "medbox.devtools.enabled=false 时 DevtoolsController 不应装配"));
    }

    @Test
    @DisplayName("devtools 开启：控制器装配")
    void enabledRegistered() {
        base(true).run(ctx -> assertTrue(ctx.containsBean("devtoolsController"),
                "medbox.devtools.enabled=true 时 DevtoolsController 应装配"));
    }
}
