package com.medbox.server.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 抓拍模块配置装配（{@code feat/capture-core}）。
 *
 * <p>唯一目的：注册 {@link CaptureProperties}，使 {@code medbox.capture.*} 配置可被注入。
 * 与 {@code DeviceConfig} 注册 {@code DeviceProperties} 同一套路（启动类无
 * {@code @ConfigurationPropertiesScan}，配置随模块走）。
 */
@Configuration
@EnableConfigurationProperties(CaptureProperties.class)
public class CaptureConfig {
}
