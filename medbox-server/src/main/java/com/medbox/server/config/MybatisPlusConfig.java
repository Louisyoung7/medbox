package com.medbox.server.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置（持久层选型见 07 清单地基第 3 项）。
 *
 * <p>本分支是第一个引入 MyBatis-Plus 的功能分支：只做 {@code @MapperScan}，
 * 不启用分页插件等（等 {@code feat/record-query} 之类真正用到列表分页时再加）。
 */
@Configuration
@MapperScan("com.medbox.server.mapper")
public class MybatisPlusConfig {
}
