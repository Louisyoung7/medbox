package com.medbox.server.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务总开关（feat/miss-alarm）。
 *
 * <p><b>为什么独立出来</b>：{@code @EnableScheduling} 原本挂在 {@code WsConfig} 上，而 {@code WsConfig}
 * 带 {@code medbox.ws.enabled} 条件装配——关掉 WS 会把整个调度（含本分支的漏服扫描）一起关掉。
 * 漏服扫描不应依赖 WS 开关，故把 {@code @EnableScheduling} 收到这里，**无条件启用**。
 *
 * <p>同时在此注册 {@link AlarmProperties}，使 {@code medbox.alarm.miss.*} 配置可被
 * {@code MissAlarmScanner} 注入（与 {@code DeviceConfig} 注册 {@code DeviceProperties} 同一套路）。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AlarmProperties.class)
public class SchedulingConfig {
}
