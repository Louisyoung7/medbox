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
