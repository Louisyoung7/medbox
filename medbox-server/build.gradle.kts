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
    implementation("org.postgresql:postgresql")

    // 持久层：MyBatis-Plus（见 07 清单地基第 3 项的《持久层框架》，首个引入点是 feat/backend-auth）
    // Boot 4 专用 starter（不是 spring-boot3-starter），版本取 Maven Central 上的最新稳定版
    implementation("com.baomidou:mybatis-plus-spring-boot4-starter:3.5.17")
    // 分页插件 PaginationInnerInterceptor **不在** starter 里：3.5.9 起 MyBatis-Plus 把依赖 JSqlParser
    // 的拦截器拆到了这个可选模块，starter 不传递引入，不显式声明就会在编译期报
    // "cannot find symbol PaginationInnerInterceptor"（feat/device-core 的第一个分页接口踩到）。
    implementation("com.baomidou:mybatis-plus-jsqlparser:3.5.17")

    // JWT：jjwt 0.12.x。JSON provider 用 orgjson 而不是 jjwt-jackson —— jjwt-jackson 依赖 Jackson 2，
    // 会被 Boot 4 自带的 Jackson 3（tools.jackson）BOM 顶掉，运行时直接 NoClassDefFoundError
    implementation("io.jsonwebtoken:jjwt-api:0.12.7")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.7")
    runtimeOnly("io.jsonwebtoken:jjwt-orgjson:0.12.7")

    // 只借它的 BCryptPasswordEncoder（版本由 Boot 4 的 BOM 管理），不引入 Security 过滤器链
    implementation("org.springframework.security:spring-security-crypto")

    // MQTT 客户端（feat/backend-mqtt，见文档 03）：Eclipse Paho 直连本机 EMQX（localhost:1883，本期匿名）
    // 用 implementation 而不是 runtimeOnly —— 代码里有显式的 MqttClient / MqttConnectOptions / MqttCallback 调用。
    // 不用 Spring Integration MQTT：它底层仍是 Paho，但要精确控制「自动重连后重订阅 / QoS1 下行 ack 超时重发 / 连不上不阻塞启动」，
    // 被 EIP 抽象包一层后调试路径更长，故直接用 Paho。
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // WebSocket 推送骨架（feat/backend-ws，见文档 04）：用**原生 WebSocket**（WebSocketConfigurer +
    // TextWebSocketHandler），不用 STOMP / 消息代理 —— 文档 04 的帧是自定义 JSON（含 ping/pong、
    // 单调递增的事件 id 用于断线补推），STOMP 的订阅语义表达不了"按 lastEventId 补增量"。
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    // AI 洞察层（refactor/ai-insight-layer 清理后保留）：BYOK 模式，按老人 llm_config 动态构造 Chat 调用（openai-compatible）
    // 版本由上方 spring-ai-bom:2.0.1 统一管理（适配 Boot 4，Jackson 3 一致）。
    // 用 starter 以获得 spring-ai-commons（ChatClient 接口所在）传递依赖；但 starter 的 OpenAI 模型自动装配
    // matchIfMissing=true，会在缺全局 Key 时于上下文加载期创建 openAiSdk*Model Bean 而启动失败，
    // 故在 application.yml 用 spring.autoconfigure.exclude 关闭这 6 个自动装配类，
    // 全局不注入任何 Chat 单例，运行时由 AiConfig 工厂按 llm_config 动态构造 ChatClient。
    implementation("org.springframework.ai:spring-ai-starter-model-openai")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Boot 4 把 @WebMvcTest / @AutoConfigureMockMvc 拆到了独立模块（starter-test 不再自带）
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    // 集成测试用 @SpringBootTest + mock Mapper，但不连真实 PostgreSQL：register 是 @Transactional，
    // 进入方法时 Spring 要开 JDBC 连接。故内嵌 H2 充当 DataSource（见 DeviceCoreWebIntegrationTest 的
    // @TestConfiguration），事务可正常开启、Mapper 仍是 mock、不跑任何真实 SQL。
    testImplementation("com.h2database:h2")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // CI（GitHub Actions）里没有 PostgreSQL / EMQX：测试期关掉 Flyway 与 MQTT 连接，否则 @SpringBootTest 启动即失败。
    // 将来接 Testcontainers 做集成测试时，删掉这两行即可。
    environment("SPRING_FLYWAY_ENABLED", "false")
    environment("MEDBOX_MQTT_ENABLED", "false")
}
