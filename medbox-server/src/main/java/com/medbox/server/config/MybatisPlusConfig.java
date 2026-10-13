package com.medbox.server.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 装配（见 README 第 6 节）。
 *
 * <p><b>分页插件在 {@code feat/device-core} 补上</b>：前面的地基分支只有单条查询与
 * 盲更新，不需要分页；设备事件流水是第一个真正的分页列表
 * （{@code GET /devices/{deviceId}/events}），故在此装配。
 *
 * <p><b>不装分页插件会发生什么</b>：{@code selectPage} 不报错，但会退化成
 * "查全量再内存截取"，{@code total} 也恒为 0 —— 典型的静默错误，务必先装插件。
 *
 * <p><b>必须显式指定 {@code DbType.POSTGRESQL}</b>：分页方言靠它选，
 * 不指定时 MP 会按 JDBC URL 猜，猜错就会拼出错误的 {@code LIMIT} 语法。
 */
@Configuration
@MapperScan("com.medbox.server.mapper")
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }
}
