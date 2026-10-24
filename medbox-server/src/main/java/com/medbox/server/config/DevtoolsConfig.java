package com.medbox.server.config;

import com.medbox.server.devtools.DevtoolsProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 联调辅助配置：注册 {@link DevtoolsProperties}（{@code medbox.devtools.*}）。
 *
 * <p>端点自身的装配由 {@code @ConditionalOnProperty(medbox.devtools.enabled)} 控制，
 * 本类只负责把配置属性绑定成 Bean，供 {@code devtools} 包下的 Controller / Service 注入。
 */
@Configuration
@EnableConfigurationProperties(DevtoolsProperties.class)
public class DevtoolsConfig {
}
