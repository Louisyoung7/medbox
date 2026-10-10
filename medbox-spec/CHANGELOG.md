# 变更记录（CHANGELOG）

> 本文件记录文档集的每次变更，便于**各端（后端 / 小程序 / Web 前端）** 判断"要不要跟着改、影响哪些模块"。
>
> **本文件不再维护版本号**（2026-10-10 起）：条目用**日期**定位，**最新的在最上面**。历史条目里的 `V15` / `V16` 等编号是旧标识，**原样保留、不要回去改**，正文对它们的引用仍然有效。
>
> **条目格式（简洁优先）**：
>
> ```markdown
> ## [YYYY-MM-DD] 一句话说清这次改了什么
>
> - **改了哪个文件**：一句话改了什么（限 3 条以内）
> - 影响端：`medbox-server/` 是 / 否 · `medbox-miniapp/` 是 / 否 · 嵌入式 否
> ```
>
> 只写**别人需要跟着改的事**：新增 / 改动的接口字段、表、配置、跨分支约定、踩过的坑。**不写实现细节与文件清单**（那些看 commit 与代码），**不每次都画"影响提示"大表格** —— 一行 `影响端` 能说清就不展开。
>
> **V1.8 起代码与文档同仓**（`medbox-server/` / `medbox-miniapp/` / `medbox-admin/` + `medbox-spec/`），不再需要跨仓库同步；V1.7 及以前的"代码仓库"表述指当时的独立仓库。

## [2026-10-10] 后端落地权限与监护关系校验（`feat/backend-authz`）

`GET /users/me` 终于可用，小程序冷启动的静默续期打通。落地了**集中式权限矩阵**与四步校验顺序，后续功能分支（`medicine-core` / `plan-core` / `alarm-core` / `ai-chat` ...）统一调 `AccessService.require(elderId, Permission)`，不再各自写角色判断。

- **`02`**：1.3 新增 **1.3.1 权限判定规则表** —— 四步校验顺序 × 角色 × 资源归属 → 放行 / `40301` / `40302`，含 **19 项 `Permission` 集中矩阵**与边界口径（PENDING 优先于权限判定；REVOKED 与"无关系"同码；抓拍对护理 / 医生显式拒绝）
- **`06`**：2.2 `guardian_relation` 新增 **`relation_id` 业务列**（`r-1001`，供 P1 `feat/guardian-relation` 的 accept / delete 接口直接用）+ `guardian_relation_biz_id_seq` 序列 + `guardian_id` / `elder_id` 带 `status` 的索引（Flyway `V4__authz.sql`）；头部 Flyway 清单同步
- **`07`**：地基第 5 项 `feat/backend-authz` 勾选完成并补"落地"一行
- **新增代码（`medbox-server/`）**：`common/security/{Permission,AccessIdentity,DeviceOwnerResolver}`、`domain/GuardianRelation`、`domain/enums/{RelationRole,RelationStatus}`、`mapper/GuardianRelationMapper`、`service/{AccessService,UserService}`、`controller/UserController`、`dto/user/UserProfileResponse`
- **⚠️ 后续分支必读**：① 权限判定统一走 `AccessService.require`，**判定在 Service 层不在拦截器**；② 列表接口用 `visibleElderIds()` 做 IN 过滤（防 N+1）；③ 设备维度 `requireDevice(...)` 的 `DeviceOwnerResolver` **本分支只有接口没有实现**，`feat/device-core` 引入 `DeviceMapper` 后补上即可；④ **定时任务 / MQTT 消费线程没有 `AuthContext`**，别走 `AccessService`，直接调 Mapper
- **测试**：`./gradlew test` 共 **91 例全通过**（本分支新增 36 例：规则表全矩阵 23 + Web 集成 9 + `/users/me` 4）；Mapper 打桩，**不连数据库**
- 影响端：`medbox-server/` 是 · `medbox-miniapp/` 是（`/users/me` 已可用）· 嵌入式 否

## [2026-10-10] 清单按优先级重构、新增三条实现约定、CHANGELOG 不再编号

- **`07` / `08` 清单本体重构**：由"按业务模块平铺"改为**按实施顺序分章**（地基 → P0 主干闭环 → P1 完善 → P2 锦上添花），**章节顺序即实施顺序**；同类分支已合并，后端 46→32、小程序 38→23 个分支
- **引用改用分支名**：PR / commit 一律写"对应 07/08 的 `feat/xxx`"，**不再用"第 N 项"**（清单会重排，序号不稳定）；`AGENTS.md` 与 `00` 的示例同步改
- **`AGENTS.md` 新增 3.1 三条实现约定**：功能分支带集成测试并出报告；判定型分支（清单标 🧭）先出规则表再写代码；同类待办合并成一个分支
- **`backend-ai-infra` 提前**：地基内由第 8 位提到第 2 位（需人在本地起 Ollama + RapidOCR，放后期会阻塞）；其上 AI 功能分支整体归 P2
- **`CHANGELOG.md` 不再维护版本号**：条目用日期定位、力求简洁，历史条目原样保留
- 影响端：`medbox-server/` 否（仅流程）· `medbox-miniapp/` 否 · 嵌入式 否

## [V16] — 2026-10-10（后端落地账号密码登录与 JWT）

`feat/backend-auth`（07 清单地基第 4 项）落地，小程序 `feat/mp-auth` 终于可以联调：注册 / 登录 / 刷新三个接口、BCrypt 存密码、access 2 小时 + refresh 7 天轮换、`X-Request-Id` 保护注册幂等。**本次新增两张表**（`refresh_token`、`idempotency_record`）与 `user_id_seq` 序列，**不改 MQTT / WS 协议**。

- **`07`**：地基第 4 项 `feat/backend-auth` 勾选完成，并补"落地"一行（jjwt + 不引 Spring Security、拦截器形态、首个引入 MyBatis-Plus 的分支、V3 脚本、幂等只保护 register、`/users/me` 仍留给 authz）
- **`06`**：新增 **2.13 `refresh_token`**（轮换 / 重放检测，只存 SHA-256 哈希）与 **2.14 `idempotency_record`**（`X-Request-Id` 幂等，当前只保护注册）；头部 Flyway 清单与实体关系总览同步补上 V3 与 RefreshToken
- **`02`**：第 3 章新增《后端侧落地（`feat/backend-auth`）》—— 令牌 claims 与 TTL、refresh 轮换与重放撤销、密码口径（失败统一 40102）、注册校验（role / 密码 6~64）、幂等范围与 TTL、登录校验范围
- **⚠️ 踩坑（后续分支照做）**：业务 `user_id` 的序列叫 **`user_biz_id_seq`**，不能叫 `user_id_seq` —— 后者是 `"user"` 表 `id BIGSERIAL` 自动创建的序列，`CREATE SEQUENCE IF NOT EXISTS` 会静默跳过，首个用户会被编成 `u-1`（已修，见 06 的 2.13）
- **后端新增（`medbox-server/src/main/java/com/medbox/server/`，后续分支直接复用）**：
  - `common/security/`：`JwtProvider`（签发 / 解析，失败统一 40101）、`JwtPayload`、`TokenType`、`AuthContext`（当前登录用户，ThreadLocal）、`PasswordHasher`（BCrypt + 陪跑哈希）
  - `common/web/AuthInterceptor` + `config/WebMvcConfig`：只做校验顺序第 ① 步「是否登录」，只拦 `/api/v1/**`、放行 `/api/v1/auth/**` 与 `/actuator/**`
  - `controller/AuthController`、`service/{AuthService, RefreshTokenService, IdempotencyService}`、`dto/auth/*`（record）
  - `domain/`（`User` / `RefreshToken` / `IdempotencyRecord`）与 `mapper/`（MyBatis-Plus，`@MapperScan` 在 `config/MybatisPlusConfig`）
  - Flyway `V3__auth.sql`：`user_id_seq`、`refresh_token`、`idempotency_record`
- **依赖变化**：新增 `mybatis-plus-spring-boot4-starter`（Boot 4 专用 starter）、`jjwt`（**JSON provider 取 `jjwt-orgjson` 而非 `jjwt-jackson`** —— 后者依赖 Jackson 2，会被 Boot 4 自带的 Jackson 3 顶掉）、`spring-security-crypto`（只用它的 BCryptPasswordEncoder，不引 Security 过滤器链）
- **新增配置**：`medbox.jwt.secret`（可用 `MEDBOX_JWT_SECRET` 覆盖，≥32 字节）、`medbox.jwt.access-token-ttl-seconds`、`medbox.jwt.refresh-token-ttl-seconds`、`medbox.idempotency.ttl-hours`
- **未做（有意）**：`GET /users/me`（留给 `feat/backend-authz`）、微信登录、短信验证码、限流（`feat/ai-limit`）

### 影响提示

| 端 / 目录 | 是否需要改代码 | 说明 |
|-----------|----------------|------|
| `medbox-miniapp/`（uni-app 小程序） | ✅ 可联调 | `feat/mp-auth` 已按本集文档实现，字段与后端一致（`token` / `refreshToken` / `userId` / `role` / `expiresIn`），写操作已自动带 `X-Request-Id`；**`/users/me` 仍未实现**，`App.vue` 启动的静默校验会拿到 40401 —— 属预期，等 `feat/backend-authz` |
| `medbox-server/`（Java 后端） | ✅ 后续分支必读 | ① 取"当前登录用户"用 `AuthContext.current()` / `required()`；② 签发 / 解析令牌用 `JwtProvider`（`feat/backend-ws` 握手鉴权直接复用）；③ **新增需要登录的接口无需额外配置**（拦截器已拦 `/api/v1/**`），但**新增无需登录的接口要在 `WebMvcConfig` 里加排除路径**；④ 持久层用 MyBatis-Plus，实体放 `domain/`、Mapper 放 `mapper/`（实体主键记得 `@TableId(type = IdType.AUTO)`，MP 默认雪花 ID） |
| 嵌入式（设备端） | ➖ 不受影响 | 设备走 MQTT |

## [V15] — 2026-10-10（小程序落地登录注册与 token 管理）

`feat/mp-auth`（08 清单地基第 3 项）落地，小程序有了"进门的第一道关"：登录 / 注册页、token 与 refreshToken 的本地存储、**过期自动刷新（含重试）**、未登录的路由守卫。**本次不新增接口、不改表结构与 MQTT / WS 协议**，只补齐小程序侧的认证落地口径。

- **`08`**：地基第 3 项 `feat/mp-auth` 勾选完成，并补六条跨分支约定（编排层 `store/auth.js`、token 唯一入口、只做被动刷新、新页面要进守卫白名单、`LOGIN_PATH` 改动要同步、注册角色只有两种）；第 2 项 `feat/mp-request` 的三条约定同步更新（登录页已落地、40101 处理顺序已在 V15 修正、token.js 之上加了编排层）
- **`02`**：第 3 章补**注册 / 刷新 / `/users/me` 的请求与响应示例**（此前只有登录示例），并新增《小程序侧落地（V15）》——被动刷新流程、并发只刷一次、`noAuthRetry`、登录接口 `silent`、路由守卫与回跳白名单、启动校验；**1.1 补 `40101` 处理顺序的修正说明**
- **⚠️ 修正 `feat/mp-request` 的 40101 处理顺序（后续分支必读）**：旧实现在调处理器**之前**就 `clearTokens()`，refresh token 已被清空 → 处理器根本无法刷新；且 40101 时 Promise 已 reject，处理器内部重试的结果回传不到调用方。现改为「**先交处理器续期 → 兑现真值则用同一份参数重发一次（只重发一次）→ 失败才清凭据跳登录**」，`setUnauthorizedHandler` 的处理器因此支持返回 `Promise<boolean>`（旧契约返回 `void` 仍兼容）
- **小程序新增（`medbox-miniapp/src/`，后续分支直接复用）**：
  - `store/auth.js`：**登录态编排层**（页面只调它）——`login` / `register` / `logout` / `refreshAuth` / `restoreSession` / `isLoggedIn` / `setupAuth` / `navigateAfterLogin`
  - `store/user.js`：用户资料缓存（`getUserInfo` / `setUserInfo` / `clearUserInfo` / `getRole` / `getUserId` / `roleLabel`）与角色常量 `ROLE`（`ELDER` / `GUARDIAN`）
  - `store/guard.js`：路由守卫 `installAuthGuard()`（`uni.addInterceptor` 拦四个跳转 API + 白名单 + `redirect` 回跳）与 `safeTarget()`（回跳只接受 `pages/` 开头，防开放重定向）
  - `pages/auth/login.vue` / `pages/auth/register.vue`：账号密码登录 / 注册（注册角色二选一），已注册进 `pages.json`；`pages/index/index.vue` 加登录态展示与退出登录 / 去登录 / 去注册入口（**临时**，后续由 `mp-ui-kit` / `mp-profile` 接管）
  - `App.vue`：`onLaunch` 调 `setupAuth()`（注入 401 接管 → 装路由守卫 → 静默校验登录态）
  - `api/request.js`（修 Bug）：`send(options, retried)` 内部重发、`noAuthRetry` 选项、清凭据时机后移；`api/auth.js`：登录 / 注册 / 刷新补 `silent` 与 `noAuthRetry`
- **`medbox-miniapp/README.md`**：新增《登录与鉴权（`feat/mp-auth`）》小节（编排层用法、刷新流程、守卫、登出、角色）
- **未做（有意）**：微信登录、密码前端 SHA-256 预哈希（等 HTTPS）、主动解析 JWT `exp` 提前刷新
- **版本号改为单一递增序号**：本版为 **V15**（即原 `V1.15`，序号连续），**各文档（00~06）头部不再标注版本号**，只在 `CHANGELOG.md` 顶部维护 —— 升版本从此只改一个文件（**此编号规则已于 2026-10-10 废弃**，改用日期标识，见本文件开头）

### 影响提示

| 端 / 目录 | 是否需要改代码 | 说明 |
|-----------|----------------|------|
| `medbox-miniapp/`（uni-app 小程序） | ✅ 后续分支必读 | ① 页面判断登录态用 `isLoggedIn()`、取角色用 `getRole()` / `roleLabel()`，**不要自己读 storage**；② 登录 / 注册 / 登出一律走 `store/auth.js`；③ **新建页面若允许未登录访问，必须加进 `store/guard.js` 的 `WHITE_LIST`**，否则会被挡到登录页；④ 业务请求**不要自己处理 40101**（请求层已自动续期 + 重发），只在页面里处理 `40301` / `40302` 等权限类错误 |
| `medbox-server/`（Java 后端） | ✅ 后续分支必读（`feat/backend-auth`） | 登录 / 注册响应需带 `token` / `refreshToken` / `userId` / `role` / `expiresIn`；`/auth/refresh` 请求体为 `{ refreshToken }`、响应换新的一对（轮换）；`/users/me` 至少返回 `userId` / `role` / `name` / `phone`；**本版本前端已按此实现，后端 `/auth/*` 尚未落地，暂无法联调** |
| 嵌入式（设备端） | ➖ 不受影响 | 设备走 MQTT |

## [V1.14] — 2026-10-10（后端落地数据库初始化与迁移）

`feat/backend-db`（07 清单地基第 3 项）落地，后端接上 **PostgreSQL 17 + pgvector**，15 张表由 **Flyway** 管理。**本次不新增接口、不改 MQTT / WS 协议**，只补齐"库怎么起、表怎么建"的落地口径。

- **`07`**：地基第 3 项 `feat/backend-db` 勾选完成；该项下新增"落地"一行（脚本位置、数据源占位符、持久层选型）；《构建工具约定》新增**持久层**与**数据库**两段
- **持久层框架定为 MyBatis-Plus**：本分支**不引入依赖**，由后续功能分支按需引入并写实体 / Mapper（`mapper/` 与 `domain/` 包已预留）
- **`06`**：头部新增《落地方式（V1.14）》——DDL 由 Flyway 管理（`V1__init_schema.sql` / `V2__vector_hnsw.sql`）；修正 2.5 节末尾多余的代码块收尾标记
- **`01`**：4.0.1 的 CI service 镜像 `pgvector/pgvector:pg16` → **pg17**；4.1.1 的 `application.yml` 示例补 `spring.datasource` 账号占位与 `spring.flyway` 配置段；**新增 4.1.3《本地数据库：PostgreSQL 17 + pgvector》**（两种起库方式、验收命令、两个坑）
- **后端新增（`medbox-server/`，后续分支直接复用）**：
  - `src/main/resources/db/migration/V1__init_schema.sql`：`CREATE EXTENSION IF NOT EXISTS vector` + 15 张表 + 约束 + 5 个普通索引（`user` 是保留字，建表带双引号）
  - `src/main/resources/db/migration/V2__vector_hnsw.sql`：`drug_manual_chunk` 的 HNSW 索引（余弦距离）；换 embedding 模型须删索引 → 改维度 → 重建向量
  - `docker-compose.yml`（`pgvector/pgvector:pg17`，库 `medbox` / 账号 `myuser` / 密码 `mypassword`）、`README.md`（起库 / 验收 / 常见问题）
  - `build.gradle.kts`：`spring-boot-starter-jdbc`、`spring-boot-flyway`、`flyway-core`、`flyway-database-postgresql`、`runtimeOnly("org.postgresql:postgresql")`；test 任务注入 `SPRING_FLYWAY_ENABLED=false`
- **两个 Boot 4 / Flyway 的坑（后续分支注意）**：① Boot 4 把各技术的自动装配拆成独立模块，**必须引 `org.springframework.boot:spring-boot-flyway`**，只加 `flyway-core` 会**静默不迁移**（无报错、表不建）；② Flyway 10+ 需 `flyway-database-postgresql`，否则报 `Unsupported Database: PostgreSQL`
- **数据源约定**：入库配置不含内网 IP，库名 / 账号走 `${MEDBOX_DB_URL}` / `${MEDBOX_DB_USERNAME}` / `${MEDBOX_DB_PASSWORD}`；各人用环境变量或不入库的 `application-local.yml` 覆盖（4.5 口径不变）
- **版本号**：文档集统一升到 V1.14（02 / 03 / 04 / 05 内容未变，仅版本号同步）

### 影响提示

| 端 / 目录 | 是否需要改代码 | 说明 |
|-----------|----------------|------|
| `medbox-server/`（Java 后端） | ✅ 后续分支必读 | 启动前须先有 PostgreSQL 17 + pgvector（`docker compose up -d`，或已有实例 `CREATE DATABASE medbox OWNER myuser;`）；`GET /medbox/actuator/health` 现在含 `db` 指示器，**库不可达会 DOWN（503）**；写实体 / Mapper 时引入 MyBatis-Plus；`med_record` / `env_sample` / `alarm` 属高频写入表，地基期只建了最小索引，后续按实际查询再补 |
| `medbox-miniapp/`（uni-app 小程序） | ➖ 不受影响 | 接口、字段与协议均未变 |
| 嵌入式（设备端） | ➖ 不受影响 | 设备走 MQTT |

## [V1.13] — 2026-10-09（小程序落地请求封装）

`feat/mp-request`（08 清单地基第 2 项）落地，小程序所有 REST 调用有了统一出口。**本次不新增接口、不改表结构与 MQTT / WS 协议**，只补齐小程序侧的请求约定。

- **`08`**：地基第 2 项 `feat/mp-request` 勾选完成；新增四条**跨分支约定**：① 统一出口为 `src/api/request.js`，业务模块不得直接调 `uni.request`；② 登录页路径常量 **`LOGIN_PATH = '/pages/auth/login'`**（页面由 `feat/mp-auth` 建）；③ `setUnauthorizedHandler()` 可接管 40101，mp-auth 接入 refresh 时不必改 `request.js`；④ `src/store/token.js` 是 token 存储的**唯一入口**，mp-auth 在此文件内扩展，不另建平行模块
- **`02` 1.1 新增《小程序侧落地》**：`X-Request-Id` 由小程序自动生成 **32 位小写十六进制**、**只注入写方法**（POST / PUT / PATCH / DELETE），与后端 `newTraceId()` 同形因而必被沿用；失败**只看 `code`**，`data` 缺失按 `null` 处理；网络层失败用客户端本地码 **`-1`**（非业务码，不进 1.2 的封闭集合）
- **小程序新增（`medbox-miniapp/src/`，后续分支直接复用）**：
  - `api/request.js` 统一出口：`request` / `get` / `post` / `put` / `del`，自动拼 `BASE_URL`、注入 `Authorization: Bearer` 与 `X-Request-Id`、解析 `code/message/data`、loading（并发计数）与网络兜底；成功兑现 `data`，失败拒绝 `ApiError{code,message,traceId,httpStatus}`
  - `api/auth.js`：`login` / `register` / `refresh` / `getMe`（02 第 3 章，`feat/mp-auth` 直接复用）
  - `store/token.js`：最小 token 存储（key 常量 + 读写 / 清除）
  - `utils/error.js`（错误码表 + `ApiError`）、`utils/uuid.js`（请求号生成）
- **`medbox-miniapp/README.md`**：新增《请求封装用法》小节（导入方式、选项表、错误处理示例）
- **流程约定调整**：`07` / `08`（及仓库根 `AGENTS.md`、根 `README.md`、`00`）**删除"文档改动必须与代码同一个 commit"要求** —— 改为**同一个 PR 即可**，允许按层分批提交（只要文档与代码在同一 PR 内）
- **版本号**：文档集统一升到 V1.13（01 / 03 / 04 / 05 / 06 内容未变，仅版本号同步）

### 影响提示

| 端 / 目录 | 是否需要改代码 | 说明 |
|-----------|----------------|------|
| `medbox-miniapp/`（uni-app 小程序） | ✅ 后续分支必读 | 一律 `import { get, post } from '@/api/request.js'`；要自绘错误提示时传 `silent: true` 并在 `catch` 里按 `err.code` 细分（如 40901 设备离线、50310 AI 未开通）；loading 默认关闭，需要遮罩时显式传 `loading: true` |
| `medbox-server/`（Java 后端） | ➖ 不受影响 | 接口与错误码未变；写操作会收到小程序自带的 `X-Request-Id`（32 位十六进制），会被 `TraceContext` 沿用为 traceId |
| 嵌入式（设备端） | ➖ 不受影响 | 设备走 MQTT |

## [V1.12] — 2026-10-09（后端落地统一响应、异常与错误码）

`feat/backend-response`（07 清单地基第 2 项）落地，后端所有 REST 接口有了统一出口。**本次不新增接口、不改表结构与 MQTT / WS 协议**，只补齐"响应与错误码"的落地口径。

- **`07`**：地基第 2 项 `feat/backend-response` 勾选完成
- **`02` 1.1 新增《落地约定》**：① `traceId` 由后端生成（32 位十六进制），客户端可用 `X-Request-Id` 自带，后端校验字符合法性后沿用，并用 **`X-Trace-Id` 响应头**回写，日志带 `[trace=xxx]`（响应里的 traceId 可直接 grep 日志）；② `timestamp` 是 epoch **秒**；③ `data` 为 null 时受全局 `non_null` 影响**不输出该字段**；④ `/medbox/actuator/**` 保持 Spring 原生格式，不套这层包装
- **`02` 1.2 新增两条口径**：错误码是**封闭集合**（改枚举必须改文档）；**未知路径**与**请求方法用错**（文档无 405 码）一律 `40401`；校验失败 / JSON 非法 / 缺参 / 类型不匹配一律 `40001`；未捕获异常一律 `50000`，堆栈只进日志
- **后端新增（后续分支直接复用）**：`R<T>`、`PageResult<T>`（page/size/total/list）、`ErrorCode` 枚举、`BizException` + `BizAssert`、`TraceIdFilter`、`GlobalExceptionHandler`
- **构建**：新增**测试依赖** `spring-boot-webmvc-test` —— Boot 4 把 `@WebMvcTest` / `@AutoConfigureMockMvc` 拆成了独立模块（`org.springframework.boot.webmvc.test.autoconfigure`），`spring-boot-starter-test` 不再自带；**仅 test 作用域，不影响运行时**

### 影响提示

| 端 / 目录 | 是否需要改代码 | 说明 |
|-----------|----------------|------|
| `medbox-server/`（Java 后端） | ✅ 后续分支必读 | Controller 一律返回 `R<T>`；失败抛 `BizException`（或用 `BizAssert.forbiddenIf(...)` 等快捷方法），不要自己拼错误响应；列表接口用 `R<PageResult<T>>` |
| `medbox-miniapp/`（uni-app 小程序） | ➖ 字段未变，暂无改动 | 请求封装分支落地时按 02 的 1.1 解析 `code/message/data`；注意**失败时 `data` 字段可能整个不存在**，按 `null` 处理，不要用 `data === undefined` 当异常 |
| 嵌入式（设备端） | ➖ 不受影响 | 设备走 MQTT，不走 REST |

## [V1.11] — 2026-10-08（CI：按端拆分两个 workflow）

单仓两套技术栈（后端 Gradle / JDK 21，小程序 uni-app + Node），CI 按目录拆成两个 GitHub Actions workflow，互不触发。

- **新增 `.github/workflows/ci-server.yml`**：`paths: medbox-server/**`；`setup-java` 21（temurin）+ `cache: gradle` → `chmod +x gradlew` → `./gradlew build --no-daemon`（含测试）；失败时上传 `build/reports/tests`
- **新增 `.github/workflows/ci-miniapp.yml`**：`paths: medbox-miniapp/**`；`setup-node` 20 + `cache: npm`（`cache-dependency-path` 指向 `medbox-miniapp/package-lock.json`）→ `npm ci` → `npm run build:mp-weixin` → 上传 `dist/build/mp-weixin` 产物
- 两个 workflow 均设 `concurrency`（同分支取消旧的）与 `workflow_dispatch`（可手动触发）
- **不引入 Monorepo 工具**（Nx / Turborepo / Lerna）：项目不长期维护，`paths` 过滤已足够
- **不自动上传小程序**：发布需 `miniprogram-ci` + 小程序密钥 + HTTPS 域名，局域网演示阶段不做
- **`01`**：新增 4.0.1《CI：按端拆分（GitHub Actions）》；修正 4.0 约定第 1、3 条残留的 Maven / `target/` 表述（改为 Gradle / `build/` `.gradle/`）；新增第 5 条"CI 按端拆分"
- **根 `README.md`**：新增 CI 小节（两个 workflow 与触发目录）
- 记录了一个坑：带 `paths` 过滤的 job 在未改动端会显示 **skipped**，GitHub 视为必需检查未通过会卡合并；若要开分支保护，需加一个 `if: always()` 的汇总 job 作为唯一 required（示例已写在 01 的 4.0.1）

### 影响提示

| 端 / 目录 | 是否需要改代码 | 说明 |
|-----------|----------------|------|
| `medbox-server/` | ➖ 否 | 仅新增 CI；本地命令不变（`./gradlew build`） |
| `medbox-miniapp/` | ➖ 否 | 仅新增 CI；本地命令不变（`npm run dev:mp-weixin`） |
| `medbox-admin/` | ⏸ 本期不做 | 将来建 Web 前端时照抄 `ci-miniapp.yml` 加一个 `ci-admin.yml` 即可 |

## [V1.10] — 2026-10-08（小程序脚手架落地：uni-app + Vue3 + Vite）

`feat/mp-scaffold` 落地时确定小程序端的工程形态与配置出口。**本次不改变任何接口、表结构与协议**（`BASE_URL` / `WS_URL` 与 02 / 04 一致）。

- **`08`**：地基第 1 项 `feat/mp-scaffold` 勾选完成；新增《工程约定（V1.10 起）》——uni-app 官方 CLI（Vue 3 + Vite 5，无仓库根统一构建脚本）、`.gitignore` 由仓库根统一维护
- **`01`** 4.2 补两条实操要点：① Vite 中 **`.env.[mode]` 优先级高于 `.env.local`**，故 `VITE_TLS` 只写 `.env.development`，各人的 `VITE_SERVER_HOST` 只写 `.env.local`，两者不冲突；② 脚手架生成的是嵌套一层目录，需把工程文件提升到 `medbox-miniapp/`（仓库约定路径）
- **`medbox-miniapp/README.md`**（新增）：运行命令、后端地址三层配置（`.env.development` / `.env.local` / 本地缓存 `serverHost`）、微信开发者工具"不校验合法域名"与共用 AppID 的注意事项
- **版本号**：文档集统一升到 V1.10（02 / 03 / 04 / 05 / 06 内容未变，仅版本号同步）

### 影响提示

| 端 / 目录 | 是否需要改代码 | 说明 |
|-----------|----------------|------|
| `medbox-miniapp/`（uni-app 小程序） | ✅ 需按 README 初始化 | 首次搭建 `cp .env.example .env.local` 并填自己的 IP；业务代码一律从 `src/config/index.js` 取地址 |
| `medbox-server/`（Java 后端） | ➖ 不受影响 | 接口地址与协议未变 |
| 嵌入式（设备端） | ➖ 不受影响 | MQTT / HTTP 协议未变 |

## [V1.9] — 2026-10-08（后端定为 Gradle / Spring Boot 4 / JDK 21）

`feat/backend-scaffold` 落地时确定后端的技术栈与构建方式，并把 07 清单第 1 项的口径同步为实际实现：**Spring Boot 4（4.1.1）+ JDK 21 + Gradle**。**本次不改变任何接口、表结构与协议。**

- **`07`**：地基第 1 项 `feat/backend-scaffold` 勾选完成；内容由"Spring Boot 3 + Java 17 工程"改为"**Spring Boot 4 + Java 21**（**Gradle / Kotlin DSL**）"；验收由 `mvn spring-boot:run` 改为 `./gradlew bootRun`，健康检查路径明确为 `GET /medbox/actuator/health`（context-path 为 `/medbox`）
- **`07` 新增《构建工具约定》**：单项目 Gradle 工程（Wrapper 9.7.1）+ Spring Boot 4 + JDK 21，**编译与目标运行时都是 21**（不再做 `--release 17` 降级）；基础包名 `com.medbox.server`
- **`01`**：4.1.1 的 `application.yml` 示例补 `server.servlet.context-path: /medbox`（与小程序 `BASE_URL` / `WS_URL` 对齐）；4.2 的 `.gitignore` 清单把 Maven 的 `target/` 换成 Gradle 的 `build/`、`.gradle/`
- **`00`** 目录约定表、**根 `README.md`** 与**仓库根 `AGENTS.md`** 的技术栈一列改为"Spring Boot 4 / Java 21 / Gradle"（`README.md` 的"各端独立构建"一句同步改成 Gradle）
- **`07` 补记 Spring Boot 4 的两个差异**：① 自带 **Jackson 3**（DTO / 自定义序列化按 Jackson 3 的 API 写）；② `spring.ai.*`（Spring AI）需按与 Boot 4 匹配的版本引入，`feat/backend-ai-infra` 落地时对齐
- **仓库根 `.gitignore`**：Java 段改为 Gradle（`.gradle/`、`build/`、`out/`），并放行 `gradle/wrapper/gradle-wrapper.jar`（否则会被上面的 `*.jar` 一并忽略，别人 clone 后无法使用 Wrapper）
- **版本号**：文档集统一升到 V1.9（02 / 03 / 04 / 05 / 06 内容未变，仅版本号同步）

### 影响提示

| 端 / 目录 | 是否需要改代码 | 说明 |
|-----------|----------------|------|
| `medbox-server/`（Java 后端） | ✅ 需升级环境 | **JDK 最低要求 21**；用 `./gradlew` 代替 `mvn`；工程为**单项目**（源码在 `medbox-server/src/main/java/...`），不是 `gradle init` 默认的多项目 `app/` 子工程；Spring Boot 4 带 **Jackson 3**，自定义序列化代码按新 API 写 |
| `medbox-miniapp/`（uni-app 小程序） | ➖ 不受影响 | 接口地址与协议未变 |
| 嵌入式（设备端） | ➖ 不受影响 | MQTT / HTTP 协议未变 |

## [V1.8] — 2026-10-08（改为单仓多目录）

项目结构由"文档仓库 + 若干独立代码仓库（git subtree 双向同步）"改为**单仓多目录**：后端 `medbox-server/`、小程序 `medbox-miniapp/`、Web 前端 `medbox-admin/`（本期占位）、文档 `medbox-spec/`。

- **`00_README_文档索引.md`**：删除整节《文档如何同步到代码仓库（git subtree）》，改为《文档就在本仓库》；新增目录约定表（含 `medbox-admin/` 本期不实现）；分支约定补充"一个分支只动一个端"
- **`AGENTS.md`**：重写并**移到仓库根目录**（原在 `medbox-spec/` 下）。删除全部 `git subtree add/pull/push --prefix=spec spec-repo` 流程与"推回文档仓库"要求，改为"开工 `git switch main && git pull`、完工时**文档与代码同 commit 提交**"；新增 0.1《仓库目录约定》（先确认自己在哪个端、相对路径相对端目录、`medbox-admin/` 本期不动）；自检清单同步
  - **放置位置**：根目录真实文件、**唯一一份**，`medbox-spec/` 下不再放同名文件。**不要用软链** —— Windows 下 git 默认 `core.symlinks=false`，软链会被检出成普通文本文件（真软链需开发者模式 / 管理员权限）
- **`01_系统架构与部署.md`**：新增 4.0《仓库与目录结构（单仓多目录）》；4.1 端口表补 Web 前端 5173 行（占位）；全部路径示例加目录前缀（`medbox-server/src/main/resources/application.yml`、`medbox-miniapp/src/config/index.js`、`.env.local` 等）；4.5 的 `.gitignore` 改为仓库根统一维护；第 3 章注明 `medbox-admin/` 本期不实现且管理员接口待设计；**提前记录一个坑**：将来做 Web 前端时浏览器受同源策略限制，需后端放行 CORS 或 dev proxy
- **`07` / `08`**：顶部新增路径与分支说明（代码只在各自端目录下、一个分支只改一个端）；地基项的目录与 `.gitignore` 说明同步
- **版本号**：文档集统一升到 V1.8（02 / 03 / 04 / 05 / 06 内容未变，仅版本号同步）
- **暂不新增 09 清单**：Web 前端 `medbox-admin/` 本期不做，等后端与小程序完成后再补清单与管理员接口设计（涉及 02 新增管理员接口章节、06 新增管理员账号表）

### 影响提示

| 端 / 目录 | 是否需要改代码 | 说明 |
|-----------|----------------|------|
| `medbox-server/`（Java 后端） | ➖ 结构层面 | 接口与表结构未变；仅工程根目录从仓库根下沉到 `medbox-server/` |
| `medbox-miniapp/`（uni-app 小程序） | ➖ 结构层面 | 同上，工程根目录为 `medbox-miniapp/` |
| `medbox-admin/`（Web 前端） | ⏸ 本期不做 | 目录占位，无代码、无清单 |
| 嵌入式（设备端） | ➖ 不受影响 | MQTT / HTTP 协议与地址规划均未变 |

### 对 AI 助手的影响（重要）

见到下列命令一律**不要执行**，它们属于已废弃的 subtree 方案：`git subtree add/pull/push --prefix=spec spec-repo ...`。现在只有 `origin` 一个远端，文档改动直接落在 `medbox-spec/` 并与代码一起提交。

## [V1.7] — 2026-10-06（任务清单与 AI 工作流）

新增三份文件，让"两个代码仓库 + 学弟学妹 + 各自的 AI 助手"能按同一套流程协作：

- **`07_特性分支清单_后端.md`**：44 个待办，每个 = 一个 `feat/xxx` 分支。前 8 项为**地基**（项目骨架、统一响应、建表、登录 JWT、权限、MQTT、WebSocket、本地 OCR/Embedding），其余为可分配任务，均标注依赖分支、参考章节与验收标准。
- **`08_特性分支清单_小程序.md`**：33 个待办，前 5 项为地基（初始化与配置出口、请求封装、登录注册、WebSocket 封装、基础 UI 组件），其余为可分配任务。
- **`AGENTS.md`**：给 AI 助手的工作约定 —— 不在主分支开发、开工前 `git subtree pull --prefix=spec spec-repo main --squash`、完工时更新文档并 `git subtree push --prefix=spec spec-repo docs/<分支>`、远程更新时回主分支 fetch 再 rebase，以及 AI 的**提醒义务**与自检清单。
- `00_README` 增加《分支与任务约定》小节；文档同步改为**双向**（pull + push），远端别名统一为 `spec-repo`（与目录 `spec/` 区分）。

### 本次修订（对齐文档与清单时发现的缺口）

- 告警枚举新增 **`DEVICE_FAULT`**，与 03 的 `up/event/error`（门超时 / 识别失败 / 硬件故障）对齐；`DEVICE_OFFLINE` 注明仅主控产生；06 的 `alarm.type` 同步
- 03 明确 `up/event/error` 的处理：记入设备事件流水并生成 DEVICE_FAULT 告警
- 07 补充 4 个遗漏分支：`inventory-consume`（消费 `up/inventory`）、`device-fault`、`alarm-device-offline`、`plan-reminder`（REMINDER 推送）
- 08 修正 `mp-ai-chat` 的参考章节（改指向 05）；`mp-device-events` 补充设备侧异常
- AGENTS.md 补充"在文档仓库内直接提交，无需 subtree"

### 影响提示

本次**不改变接口与表结构**（仅新增一个告警类型），对代码的影响沿用 V1.5 的《影响提示》；新增的 07 / 08 是**任务划分依据**，各代码仓库据此认领分支。

## [V1.6] — 2026-10-06（消除歧义与补充调试手段）

文档面向"AI 助手 + 接手任务的同学"，因此把所有**条件句与未定参数收敛为明确决定**，避免不同人 / 不同 AI 各做各的。**本次不改变接口与表结构**，对代码的影响沿用下方 V1.5 的《影响提示》。

| 项 | 已确定 |
|----|--------|
| 老人账号创建 | **保留 `/auth/register`**，监护人可代建；本期不做 Web 后台创建 |
| 会话有效期 | access token **2 小时**，refresh token **7 天**且轮换 |
| 抓拍上传鉴权 | 共用密钥 HMAC 签名 + **时间窗 ±5 分钟**；不做"只校验 `X-Device-Id`"的退化方案 |
| 抓拍去重 | 同一服药事件只留 1 张，按 `planId + 60s 时间窗` 兜底去重 |
| 下行 ack 重发 | 超时 **5s** 重发，**最多 3 次**，仍无回执则标记失败并告警 |
| 上行 `msgId` 格式 | 固定 `{deviceId}-{10位递增序号}`，序号持久化不归零 |
| 传感器心跳 | 主控 **60s** 汇总上报一次，连续 **3 个周期（180s）**未上报判子设备离线 |
| 摄像头抓拍张数 | 每个服药事件**只 1 张**，禁止连拍多张 |
| RAG 参数 | Top-K **= 5**；余弦距离 **> 0.6** 视为检索无依据，只答结构化数据 |
| 多模态读图兜底 | **本期不实现**，OCR 失败标记 `FAILED` 由监护人重传 |
| Embedding 选型 | **Ollama + `bge-m3`（1024 维）**，不采用 `bge-small-zh` 轻量备选 |
| 流式问答通道 | **只用 HTTP SSE**，不实现 WebSocket 的 `AI_STREAM` |
| OCR 预处理 | 只做**灰度 + 去噪**，本期不做透视 / 版面矫正 |
| IP 固定方式 | 两人各自在自己路由器上做 DHCP 静态绑定 |
| 小程序 AppID | 两人**共用同一个 AppID**（互加开发者），不用各自的号 |
| WebSocket 补推窗口 | 服务端保留最近 **200 条 / 10 分钟**，超出走 REST 拉取全量 |

**新增调试手段**：

- **02 新增 1.4《接口调试（curl）》**：登录取 token → 带 token 调接口 → 带 `X-Request-Id` 的写操作 → `curl -F` 上传抓拍图片；
- **03 新增第 5 章《联调与调试》**：① **EMQX Dashboard 内置 WebSocket 客户端**（可订阅 + 发布，是联调主力，记得改默认 `admin/public`）；② `mosquitto_pub` **只能发布**，看回包需另开 `mosquitto_sub`，给了完整命令（注意 `-i` 的 clientId 须等于 deviceId）；③ curl 指向 02；④ WebSocket 用 `wscat` 验证。

## [V1.5] — 2026-10-05

### 01 系统架构与部署
- 新增**双人独立开发**方案：IP 只写在 `.env.local` / `application-local.yml`（不入库），`git pull` 无需改动任何配置
- 新增 `application.yml` 说明与"为什么监听 `0.0.0.0` 不能省掉地址配置"的辨析
- 新增**角色权限默认规则**（角色 × 权限矩阵 + 四步校验顺序）
- 明确**时区口径**：服药计划 `times` 为设备本地墙钟时间（`device.timezone`），其余一律 UTC
- 明确**漏服容忍优先级**：计划级 > 老人级 > 系统默认 15 分钟
- **药箱不划分仓位**；设备拆分为药箱主控 + 传感器子设备
- 新增 4.6 抓拍图片的存储、鉴权与过期规则（已读 7 天 / 未读 30 天）
- 附录新增《凭据的"加密存储"——防什么、什么时候做》
- **删除 OTA / 固件版本**相关内容（仅保留一句范围说明）
- 5.2 错服识别改为摄像头识别药品；5.3 过期改为按 `medicine_stock.expiry_date`

### 02 REST 接口设计
- 登录改为**账号 + 密码**，删除短信验证码登录与微信登录（`wechat_open_id` 预留）
- 新增 1.3 权限默认规则与 `40302`（监护关系未生效）
- 监护绑定改为**两步**：监护人发起（PENDING）→ 老人端确认（ACTIVE）
- 服药计划支持**一次提醒多种药**（`items` 数组，无仓位字段）
- **仓位接口删除**，改为库存接口 `/devices/{id}/stocks*`；`/devices/{id}/sensors` 归入设备管理
- 新增 `DELETE /medicines/{medicineId}`；删除按药品设置的环境阈值接口
- 新增 4.1 设备抓拍图片接口（上传 / 列表 / 详情 / 读取文件流 / 删除）
- 环境阈值统一为老人级；告警枚举 `ENV`、`WRONG_DRUG` 描述同步更新
- 错误码表排序整理；设备注册改为"注册即生效，不做准入审核"

### 03 设备接入协议 MQTT
- 新增 `up/ack` 回执主题与超时重发；上行统一带 `msgId` 做幂等去重
- Topic 前缀去掉前导 `/`；明确**一个药箱只有主控建 MQTT 连接**
- 新增第 4 章**摄像头识别链路**（WiFi → 单片机 → MQTT；图片 HTTP 直传不过 MQTT）
- 遥测改为**每个传感器各发一条**；新增 `up/sensor/heartbeat` 判子设备离线
- 明确**错服只走 `dispense.wrongDrug`**，`up/event/error` 仅用于设备侧异常
- 低置信度**不判错服**，抓拍标 `LOW_CONFIDENCE` 交人工复核
- 补充 `up/inventory` payload 示例；`dispense` 增加 `confidence` / `source` / `imageId`
- **删除一机一密章节**；明确本期为匿名连接，两级加固仅在 03 / 01 附录各提一句

### 04 实时推送协议 WebSocket
- 告警 `level` 统一为 `INFO / WARN / CRITICAL`（示例原为 `HIGH`）
- `REMINDER` 改为 `medicines[]`（一次提醒含多种药）
- `DEVICE_STATUS` 增加 `deviceType` / `parentDeviceId`，覆盖传感器子设备；去掉 `battery` 与 `firmwareVer`

### 05 AI 大模型与 RAG 方案
- **OCR 与 Embedding 本地化**：RapidOCR 侧车服务 + Ollama `bge-m3`（1024 维），不出网、无需 Key
- 向量维度 1536 → **1024**（换模型须重建全部分块向量）
- 新增 5.1 RAG 检索路径（锁定老人 → 定位 medicineId → 带过滤召回 → 兜底说明）
- 补全会话接口（创建 / 重命名 / 删除，首次提问隐式创建 `sessionId`）

### 06 数据库设计
- **删除 `compartment`**，新增 `medicine_stock`（药箱 + 药品维度）
- `device` 增加 `device_type` 与 `parent_device_id`（主控 / 温湿度 / 光照 / 摄像头）
- 新增 `capture` 抓拍表（含 `viewed_at`、`expire_at`）
- `med_plan_item`、`med_record` 删除 `slot_no`；`med_record` 增加 `capture_id`
- `medicine` 增加 `owner_elder_id`（公共字典 / 私有药品）
- `med_record`、`env_sample` 冗余 `elder_id`；`env_sample` 增加 `gateway_device_id`
- `user` 增加 `username`、`password_hash`，`phone` 改 UNIQUE
- `drug_manual_chunk` 外键统一为 `VARCHAR(64)`
- `device.secret` 标注为预留未使用（本期匿名，无设备凭据）

### 影响提示

| 端 / 目录（当时为独立仓库） | 是否需要改代码 | 说明 |
|----------|----------------|------|
| `medbox-server/`（Java 后端） | ✅ 需要 | 计划主子表、库存表、抓拍表与接口、本地 OCR/Embedding、匿名 MQTT 连接 |
| `medbox-miniapp/`（uni-app 小程序） | ✅ 需要 | 登录改账号密码、计划改为 `items`、监护两步确认、抓拍图片查看 |
| 嵌入式（设备端） | ✅ 需要 | 上行统一带 `msgId`、`up/ack` 回执、遥测按传感器分条、传感器心跳、摄像头链路 |
| `medbox-admin/`（Web 前端） | ⏸ 本期不做 | 管理员接口待设计（V1.8 确认本期不实现） |

## [V1.4] — 2026-10-03（基线）

文档集初始版本：系统架构与部署（01）、REST 接口设计（02）、设备接入协议 MQTT（03）、实时推送协议 WebSocket（04）、AI 大模型与 RAG 方案（05）、数据库设计（06）。
