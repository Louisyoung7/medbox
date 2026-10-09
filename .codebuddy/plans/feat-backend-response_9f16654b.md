---
name: feat-backend-response
overview: 在 medbox-server/ 落地统一响应 R<T>、错误码枚举、全局异常处理与 traceId 透传（07 清单地基第 2 项 feat/backend-response），并用 MockMvc 单测保证任意异常都返回统一结构，最后同步勾选 07 待办并补 CHANGELOG。
todos:
  - id: sync-main
    content: 切主分支拉取最新代码与文档，再回到 feat/backend-response 并 rebase
    status: completed
  - id: error-code-layer
    content: 实现 ErrorCode 枚举、BizException 与 BizAssert 断言工具
    status: completed
    dependencies:
      - sync-main
  - id: response-dto
    content: 实现统一响应 R<T> 与分页包装 PageResult<T>
    status: completed
    dependencies:
      - error-code-layer
  - id: trace-filter
    content: 实现 TraceIdFilter（MDC + X-Trace-Id 响应头）并注册
    status: completed
    dependencies:
      - response-dto
  - id: global-handler
    content: 实现 GlobalExceptionHandler 并补齐 application.yml 的 404 与日志配置
    status: completed
    dependencies:
      - trace-filter
  - id: unit-tests
    content: 编写 MockMvc 单测覆盖成功、校验失败、业务异常、404、500 等场景
    status: completed
    dependencies:
      - global-handler
  - id: build-verify
    content: 执行 ./gradlew build 与 bootRun 冒烟，确认 actuator 健康检查未被包裹
    status: completed
    dependencies:
      - unit-tests
  - id: docs-sync
    content: 勾选 07 第 2 项、补 02 口径说明与 CHANGELOG V1.12，与代码同提交
    status: completed
    dependencies:
      - build-verify
---

## 产品概述

在 Java 后端 `medbox-server/` 落地「统一响应、异常与错误码」地基（对应 07 清单地基第 2 项 `feat/backend-response`），使后续所有业务分支复用同一套响应包装与错误码体系。

## 核心功能

- **统一响应 `R<T>`**：所有 REST 接口返回 `{code, message, data, traceId, timestamp}`，`code=0` 为成功，`timestamp` 为 epoch 秒，`traceId` 用于排障。
- **错误码枚举**：严格按文档 02 §1.2 落地 `40001 40101 40102 40301 40302 40401 40901 42901 50000 50310`，并绑定对应 HTTP 状态码与默认中文提示。
- **全局异常处理**：参数校验失败、业务异常、资源不存在、未捕获异常等所有出口都返回统一结构；`/medbox/actuator/**` 保持 Spring 原生格式不被包裹。
- **traceId 透传**：轻量过滤器读取 `X-Request-Id`（无则生成短 UUID），写入 MDC 与响应头 `X-Trace-Id`，并注入日志格式，使响应与日志可对齐。
- **可复用工具**：`PageResult<T>` 分页包装（page/size/total/list）与业务断言工具（notFound/conflict/forbidden 等快捷抛出），供后续分支直接复用。
- **验收**：MockMvc 单元测试覆盖成功、校验失败、业务异常、未知路径、系统异常等场景；`./gradlew build` 在 CI 中通过。

## 技术栈

- 沿用现有：`medbox-server/` 的 **Spring Boot 4.1.1 + Java 21 + Gradle（Kotlin DSL，Wrapper 9.7.1）**，依赖仅 `spring-boot-starter-web / actuator / validation`、Lombok、`spring-boot-starter-test`。**本期不新增任何依赖**（traceId 自研过滤器，不引入 Micrometer Tracing）。
- 基础包 `com.medbox.server`，context-path `/medbox`，接口前缀 `/api/v1`。

## 实现思路

以「包装层 + 异常层 + 过滤器」三块最小地基实现：Controller 统一返回 `R<T>`，业务侧通过 `BizAssert` / `BizException` 抛错，`@RestControllerAdvice` 兜底把一切异常翻译成 `R` 并按错误码映射 HTTP 状态码；`OncePerRequestFilter` 在请求入口生成 traceId 并贯穿日志与响应。

关键技术决策与权衡：

1. **自研 traceId 过滤器**（用户已选）：零依赖、可控；代价是没有 spanId / 跨服务传播，局域网单体阶段够用。`X-Request-Id` 只作为 traceId 来源，**幂等去重不在本期**（属 `feat/backend-auth`）。
2. **HTTP 状态码与业务码双轨**：响应体 `code` 用业务码，HTTP 状态按文档表格同步（如 40101→401），既满足文档又便于小程序按 HTTP 层快速分流。
3. **Advice 限定 `basePackages = "com.medbox.server.controller"`**：避免污染 actuator 健康检查（`GET /medbox/actuator/health` 必须保持原生 200 结构，是 scaffold 的验收项）。
4. **404 统一结构**：开启 `spring.mvc.throw-exception-if-no-handler-found=true` + `spring.web.resources.add-mappings=false`，让无匹配路径抛出可被 Advice 捕获的异常并映射为 `40401`。
5. **不新增调试端点**（用户已选）：验收靠 `@WebMvcTest` + MockMvc，避免多出一个文档外接口。

## 实现要点（防回归）

- **Jackson 3**：DTO 尽量不直接 import Jackson 注解；`data` 为 null 时受全局 `default-property-inclusion: non_null` 影响被省略（文档允许「失败时可为 null」），最终口径补进 02 §1.1；若实测注解可用再在 `data` 上加 `ALWAYS`。
- **Spring Boot 4 API**：`ResponseEntityExceptionHandler` / `ErrorResponse` 相关签名以实际编译为准，编译报错时按实际 API 调整，不臆造。
- **异常覆盖面**：`BizException` → `MethodArgumentNotValidException` / `HandlerMethodValidationException`（40001，取首个字段错误信息）→ `ConstraintViolationException` → `HttpMessageNotReadableException`（40001，JSON 解析失败不要回显原始报文）→ `MissingServletRequestParameterException` / 参数类型不匹配（40001）→ `NoResourceFoundException` / `NoHandlerFoundException`（40401）→ `HttpRequestMethodNotSupportedException`（40001）→ 兜底 `Throwable`（50000）。
- **日志**：兜底分支以 error 级别记录（含 traceId、URI），**不把堆栈与请求体回显给客户端**，防信息泄漏；避免高频 4xx 打 error 造成日志刷屏（4xx 用 warn/info）。
- **过滤器清理**：`finally` 中 `MDC.remove("traceId")`，防止线程池复用导致串号。
- **性能**：过滤器仅一次 UUID 生成 + MDC 写入，无 IO；异常映射为常量查表，无反射开销。
- **爆炸半径**：只新增类与两条配置，不改已有类、不改接口契约；actuator 行为保持不变。

## 架构设计

请求链路：`TraceIdFilter`（生成/透传 traceId → MDC → 响应头）→ Controller（返回 `R<T>`，或抛 `BizException`）→ `GlobalExceptionHandler`（异常 → `R` + HTTP 状态）→ 客户端。

```mermaid
flowchart LR
  A[Client] --> B[TraceIdFilter<br/>X-Request-Id / UUID → MDC]
  B --> C[Controller /api/v1/**]
  C -->|成功| D[R.ok(data)]
  C -->|BizException| E[GlobalExceptionHandler]
  C -->|其他异常| E
  E --> F[R.fail(code,message)<br/>HTTP 状态按错误码映射]
  B -->|X-Trace-Id 响应头| A
```

## 目录结构

```
medbox-server/src/main/java/com/medbox/server/
├── common/
│   ├── exception/
│   │   ├── ErrorCode.java          # [NEW] 错误码枚举：code / httpStatus / 默认中文 message；含 SUCCESS(0,200,"success") 与文档 10 个错误码
│   │   ├── BizException.java       # [NEW] 业务异常：持 ErrorCode，可覆盖 message（如校验细节）；构造走静态工厂 of(...)
│   │   └── BizAssert.java          # [NEW] 断言工具：isTrue/notNull/notBlank/notFound/conflict/forbidden/unauthorized 等，失败抛 BizException
│   └── web/
│       ├── TraceIdFilter.java      # [NEW] OncePerRequestFilter + @Order(最高优先级-10)：读 X-Request-Id，无则生成短 UUID；MDC.put("traceId")；响应头 X-Trace-Id；finally 清理
│       └── GlobalExceptionHandler.java # [NEW] @RestControllerAdvice(basePackages="com.medbox.server.controller")：按上文覆盖面映射异常 → R + HTTP 状态
├── dto/
│   ├── R.java                      # [NEW] 统一响应：code/message/data/traceId/timestamp；静态工厂 ok()/ok(data)/fail(ErrorCode)/fail(ErrorCode,message)；traceId 取 MDC，timestamp 取 epoch 秒
│   └── PageResult.java             # [NEW] 分页包装：page/size/total/list + of(list,total,page,size) 工厂，供后续所有列表接口复用
medbox-server/src/main/resources/
└── application.yml                 # [MODIFY] 新增 spring.mvc.throw-exception-if-no-handler-found=true、spring.web.resources.add-mappings=false、logging.pattern.console/level 带 %X{traceId}；其余不动
medbox-server/src/test/java/com/medbox/server/
├── common/web/GlobalExceptionHandlerTest.java  # [NEW] @WebMvcTest + MockMvc：覆盖成功/参数校验失败/业务异常(40401)/未知路径(40401)/系统异常(50000)/缺参/JSON 解析失败，断言 code、message、HTTP 状态、traceId 非空
├── dto/RSerialTest.java                        # [NEW] R 结构与 PageResult 序列化/工厂方法断言（含 timestamp 为秒级、data 为 null 的口径）
└── support/TestErrorController.java            # [NEW] 仅测试源集使用的控制器与请求 DTO，造出各类异常场景，不进生产包
medbox-spec/
├── 07_特性分支清单_后端.md            # [MODIFY] 地基第 2 项改 - [x]
├── 02_REST接口设计.md                 # [MODIFY] §1.1 补 traceId 来源（X-Request-Id / 自动生成）、X-Trace-Id 响应头、data 为 null 时的口径
└── CHANGELOG.md                     # [MODIFY] 新增 [V1.12] 条目：改了什么 +「影响哪个端/目录」表（medbox-server ✅ / medbox-miniapp 需对齐字段）
```

## 关键代码结构

```java
// 统一响应：所有 Controller 的返回类型
public final class R<T> {
    private final int code;        // 0=成功，非0=业务错误码
    private final String message;  // 成功为 "success"
    private final T data;          // 失败时可为 null
    private final String traceId;  // 取自 MDC("traceId")
    private final long timestamp;  // epoch 秒

    public static <T> R<T> ok(T data);
    public static <T> R<T> ok();
    public static <T> R<T> fail(ErrorCode ec);
    public static <T> R<T> fail(ErrorCode ec, String message);
}

// 错误码：code 与 HTTP 状态、默认提示一一对应（02 §1.2）
public enum ErrorCode {
    SUCCESS(0, 200, "success"),
    BAD_REQUEST(40001, 400, "参数校验失败"),
    UNAUTHORIZED(40101, 401, "未登录或 Token 已过期"),
    BAD_CREDENTIALS(40102, 401, "账号或密码错误"),
    FORBIDDEN(40301, 403, "无权限访问该资源"),
    GUARDIAN_PENDING(40302, 403, "监护关系未生效"),
    NOT_FOUND(40401, 404, "资源不存在"),
    CONFLICT(40901, 409, "状态冲突"),
    TOO_MANY_REQUESTS(42901, 429, "请求过于频繁"),
    INTERNAL_ERROR(50000, 500, "服务端内部错误"),
    AI_UNAVAILABLE(50310, 503, "AI 服务暂不可用");

    public final int code();
    public final int httpStatus();
    public final String message();
}
```