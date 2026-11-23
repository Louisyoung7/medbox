package com.medbox.server.testsupport;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * infra 测试基类：用 Testcontainers 起**真实 PostgreSQL**，让 Flyway 迁移与 Mapper SQL 真正执行。
 *
 * <p>与 {@code ./gradlew test}（H2 + Mock Mapper，不碰真实 SQL）互补：
 * 这里验证的是「迁移脚本能不能在真 PG 上跑通」「注解 SQL 的列名 / 序列 / JSONB 处理器对不对」，
 * 由 {@code ./gradlew infraTest} 执行（见 {@code medbox-spec/08_测试体系与CI.md}）。
 *
 * <p><b>容器只起一次</b>：{@code POSTGRES} 是声明在本基类的静态字段，JVM 初始化基类时就启动，
 * 所有子类共用；各子类只拿到自己的 Spring 上下文（配置相同则上下文也被 Spring 缓存复用）。
 * 静态初始化块保证「容器先起、上下文后建」，避免 {@link DynamicPropertySource} 拿不到端口。
 *
 * <p>镜像用 {@code pgvector/pgvector:pg17} 而不是官方 {@code postgres:17}：V1 里有
 * {@code CREATE EXTENSION vector}（V18 才 drop），全新库必须先有 pgvector 扩展才能跑完迁移。
 * 与本地开发用的镜像保持一致（见 {@code medbox-server/docker-compose.yml} 的说明）。
 * 缺 Docker 时 Testcontainers 直接报错，不静默跳过（AGENTS.md 3.1 的要求）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class PostgresInfraTestBase {

    /** 全部 infra 测试共用的 PostgreSQL 容器。 */
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("pgvector/pgvector:pg17")
                    .withDatabaseName("medbox")
                    .withUsername("medbox")
                    .withPassword("medbox");

    static {
        POSTGRES.start();
    }

    /** 把容器的连接信息注入 Spring，覆盖 application.yml 里的本机 PostgreSQL 地址。 */
    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
