plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.medbox"
version = "0.0.1-SNAPSHOT"
description = "家用智能药品箱 · Java 后端"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

repositories {
    mavenCentral()
}

// Spring AI 2.x：适配 Spring Boot 4（Jackson 3 与 Boot 4 一致，无 Jackson 2 冲突）
// 见 07 清单地基第 1 项《构建工具约定》与 feat/backend-ai-infra
dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:2.0.1")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // 数据库：starter-jdbc 触发 DataSource 自动装配（HikariCP），Flyway 才能接管初始化
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // Boot 4 把各技术的自动装配拆成了独立模块，Flyway 的不在 spring-boot-autoconfigure 里：
    // 少了这一条，flyway-core 在 classpath 上也不会生效（无报错、静默不迁移）
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    // Flyway 10+ 把各数据库支持拆成独立模块，只引 flyway-core 会报 "Unsupported Database: PostgreSQL"
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // 持久层：MyBatis-Plus（见 07 清单地基第 3 项的《持久层框架》，首个引入点是 feat/backend-auth）
    // Boot 4 专用 starter（不是 spring-boot3-starter），版本取 Maven Central 上的最新稳定版
    implementation("com.baomidou:mybatis-plus-spring-boot4-starter:3.5.17")

    // JWT：jjwt 0.12.x。JSON provider 用 orgjson 而不是 jjwt-jackson —— jjwt-jackson 依赖 Jackson 2，
    // 会被 Boot 4 自带的 Jackson 3（tools.jackson）BOM 顶掉，运行时直接 NoClassDefFoundError
    implementation("io.jsonwebtoken:jjwt-api:0.12.7")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.7")
    runtimeOnly("io.jsonwebtoken:jjwt-orgjson:0.12.7")

    // 只借它的 BCryptPasswordEncoder（版本由 Boot 4 的 BOM 管理），不引入 Security 过滤器链
    implementation("org.springframework.security:spring-security-crypto")

    // AI 地基（feat/backend-ai-infra）：本地 OCR 调用（RestClient）+ 本地 Embedding（Ollama bge-m3）+ pgvector 向量库
    // 版本由上方 spring-ai-bom:2.0.1 统一管理（适配 Boot 4，Jackson 3 一致）
    implementation("org.springframework.ai:spring-ai-starter-model-ollama")
    implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
    // pgvector 的 JDBC vector 类型绑定由 spring-ai-starter-vector-store-pgvector 传递引入，无需显式声明

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Boot 4 把 @WebMvcTest / @AutoConfigureMockMvc 拆到了独立模块（starter-test 不再自带）
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // CI（GitHub Actions）里没有 PostgreSQL：测试期关掉 Flyway，否则 @SpringBootTest 启动即失败。
    // 将来接 Testcontainers 做集成测试时，删掉这一行即可。
    environment("SPRING_FLYWAY_ENABLED", "false")
}
