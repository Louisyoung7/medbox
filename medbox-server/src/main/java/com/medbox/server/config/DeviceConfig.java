package com.medbox.server.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 设备模块的配置装配（{@code feat/device-core}）。
 *
 * <p>本类存在的唯一目的是注册 {@link DeviceProperties} —— 启动类上没有
 * {@code @ConfigurationPropertiesScan}，而 MQTT 侧的做法（把 {@code @EnableConfigurationProperties}
 * 打在各自模块的 {@code @Configuration} 上）已经定下了"配置随模块走"的风格，这里沿用。
 */
@Configuration
@EnableConfigurationProperties(DeviceProperties.class)
public class DeviceConfig {
}
