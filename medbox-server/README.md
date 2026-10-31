# medbox-server

家用智能药品箱 · Java 后端（Spring Boot 4 / Java 21 / Gradle，基础包 `com.medbox.server`）。

| 场景 | 命令 |
|------|------|
| 启动 | `./gradlew bootRun`（或 IDE 运行 `com.medbox.server.MedboxServerApplication`） |
| 构建 | `./gradlew build` |
| 测试 | `./gradlew test` |
| 健康检查 | `GET http://localhost:8080/medbox/actuator/health` |

任务清单见 `../medbox-spec/07_特性分支清单_后端.md`，表结构见 `../medbox-spec/06_数据库设计.md`。

## 1. 准备数据库（PostgreSQL 17）

后端在 **PostgreSQL 17**（默认 `localhost:5432`，库 `medbox`）上运行。两种方式二选一。

### A. 本机已有 PG 实例（本机 `medbox-pg` 容器即如此，推荐）

只需建一次库（`myuser` 是超级用户时可直接执行；表由 Flyway 自动建）：

```bash
docker exec -e PGPASSWORD=mypassword medbox-pg \
  psql -U myuser -d mydb -c "CREATE DATABASE medbox OWNER myuser;"
```

> 本机没有安装 `psql` 客户端，SQL 一律用上面的 `docker exec` 方式执行。

### B. 没有现成实例：用 compose 起一个

```bash
cd medbox-server
docker compose up -d        # postgres:17，库 medbox / 账号 myuser / 密码 mypassword
docker compose down         # 停止（数据卷 medbox-pg-data 保留）
```

## 2. 连接配置

`src/main/resources/application.yml`（入库）里 host 固定 `localhost`，库名与账号走占位符，默认值即上面这套：

```yaml
spring:
  datasource:
    url: ${MEDBOX_DB_URL:jdbc:postgresql://localhost:5432/medbox}
    username: ${MEDBOX_DB_USERNAME:myuser}
    password: ${MEDBOX_DB_PASSWORD:mypassword}
```

各人可用环境变量覆盖，或写进 **不入库** 的 `src/main/resources/application-local.yml`（模板见 `application-local.yml.example`）。
**入库配置里不出现任何内网 IP**（见文档 01 的 4.1.1 / 4.5）。

## 3. 建表（Flyway）

应用启动时自动执行 `src/main/resources/db/migration/` 下的脚本，空库一键初始化：

| 脚本 | 内容 |
|------|------|
| `V1__init_schema.sql` | 初始化全部关系表 + 约束 + 5 个普通索引（含说明书 RAG 表与 vector 扩展，历史遗留、已由 V18 下线） |
| `V2__vector_hnsw.sql` | `drug_manual_chunk` 的 HNSW 索引（余弦距离，已由 V18 下线） |
| `V18__drop_ai_rag.sql` | 下线 AI RAG：`DROP TABLE drug_manual_chunk, drug_manual` + `DROP EXTENSION vector`（refactor/ai-insight-layer） |
| `V3__auth.sql` | `user_biz_id_seq` 序列 + `refresh_token` + `idempotency_record`（`feat/backend-auth`，见文档 06 的 2.13 / 2.14） |
| `V4__authz.sql` | `guardian_relation.relation_id` 业务列 + `guardian_relation_biz_id_seq` 序列 + 关系查询索引（`feat/backend-authz`，见 06 的 2.2） |
| `V5__mqtt.sql` | `mqtt_message` 上行消息去重表（`feat/backend-mqtt`，见文档 06 的 2.15） |
| `V6__device_event.sql` | `device_event` 设备事件流水 + `device` 表两个索引（`feat/device-core`，见 06 的 2.16） |
| `V7__user_delete.sql` | `"user"` 表加 `status` / `deleted_at` 两列，**账号注销**（`feat/backend-user-delete`，见 02 的 3.1 / 06 的 2.1） |

安全开关：`clean-disabled: true`（禁止误清库）、`validate-on-migrate: true`（脚本被改动即启动失败）、不开 `baseline-on-migrate`。

## 4. 验收

```bash
# 关系表
docker exec -e PGPASSWORD=mypassword medbox-pg psql -U myuser -d medbox -c "\dt"
# 索引
docker exec -e PGPASSWORD=mypassword medbox-pg psql -U myuser -d medbox -c "\di"
# 迁移记录（应含 V1 … V18 且 success = t）
docker exec -e PGPASSWORD=mypassword medbox-pg psql -U myuser -d medbox \
  -c "select installed_rank, version, success from flyway_schema_history order by installed_rank;"
```

## 5. 常见问题

| 现象 | 原因 / 处理 |
|------|-------------|
| `Unsupported Database: PostgreSQL` | 缺 `flyway-database-postgresql`（Flyway 10+ 把数据库支持拆成了独立模块） |
| 启动没有任何 Flyway 日志、表也没建 | Boot 4 把自动装配拆成了独立模块，缺 `org.springframework.boot:spring-boot-flyway` —— **只加 `flyway-core` 会静默不迁移**（无报错） |
| 历史库迁移报 vector 扩展权限不足 | V1 历史脚本曾 `CREATE EXTENSION vector`（现已由 V18 下线、不再需要）；若旧库迁移失败，先用超管执行一次 `CREATE EXTENSION IF NOT EXISTS vector;` 让 V1/V2 通过，V18 会自行删除该扩展 |
| CI（GitHub Actions）测试失败在 Flyway | CI 无数据库；`build.gradle.kts` 的 test 任务已注入 `SPRING_FLYWAY_ENABLED=false`，测试期不跑迁移 |
| `actuator/health` 变 DOWN | 引入 JDBC 后健康检查多了 `db` 指示器，数据库不可达即 DOWN；实例起来后恢复 200 |

## 6. 持久层

**持久层框架定为 MyBatis-Plus**（见文档 07）。地基 `feat/backend-db` 只建表未引入依赖；**`feat/backend-auth` 是第一个引入它的分支**：
`com.baomidou:mybatis-plus-spring-boot4-starter`（Boot 4 专用 starter，不是 `spring-boot3-starter`），
`@MapperScan` 在 `config/MybatisPlusConfig`，实体放 `domain/`、Mapper 放 `mapper/`。

> **实体主键要写 `@TableId(type = IdType.AUTO)`**：表主键是 `BIGSERIAL`，而 MyBatis-Plus 默认是雪花 ID（ASSIGN_ID），
> 不显式声明就会往自增列里塞一个巨大的值。

## 7. 认证（`feat/backend-auth`）

`POST /medbox/api/v1/auth/{register,login,refresh}` —— 注册 / 登录 / 刷新，响应同为
`{ token, refreshToken, userId, role, expiresIn }`（access 2 小时、refresh 7 天且轮换）。

| 配置项 | 默认 | 说明 |
|--------|------|------|
| `medbox.jwt.secret` | 本地开发默认值 | HS256 密钥，**≥32 字节**；正式/对外部署用环境变量 `MEDBOX_JWT_SECRET` 覆盖（改了它已签发的 token 全部失效） |
| `medbox.jwt.access-token-ttl-seconds` | 7200 | access token 有效期 |
| `medbox.jwt.refresh-token-ttl-seconds` | 604800 | refresh token 有效期（7 天，轮换） |
| `medbox.idempotency.ttl-hours` | 24 | `X-Request-Id` 幂等记录保留时长（目前只保护 `POST /auth/register`） |

自测（本机）：

```bash
# 1) 注册监护人（带上 X-Request-Id，重复提交只会建一个号）
curl -s -X POST http://localhost:8080/medbox/api/v1/auth/register \
  -H 'Content-Type: application/json' -H 'X-Request-Id: 8f2c1a9e3b7d4c1a9f0e2d5b6a7c8f10' \
  -d '{"phone":"13800001234","password":"123456","role":"GUARDIAN","name":"李四"}' | jq

# 1b) 注册老人本人（同一个注册接口，role 换成 ELDER；老人是设备近场使用者，只读本人数据）
curl -s -X POST http://localhost:8080/medbox/api/v1/auth/register \
  -H 'Content-Type: application/json' -H 'X-Request-Id: 9a1b2c3d4e5f60718293a4b5c6d7e8f09' \
  -d '{"phone":"13800008888","password":"123456","role":"ELDER","name":"王爷爷"}' | jq

# 2) 登录拿 access token
TOKEN=$(curl -s -X POST http://localhost:8080/medbox/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"account":"13800001234","password":"123456"}' | jq -r '.data.token')

# 3) 业务接口一律带 -H "Authorization: Bearer $TOKEN"（未带 / 过期 → 40101；40102 出现在登录 / 注册 / 注销密码错误）

# 4) 刷新（旧的 refresh token 立即作废，重放会撤销该用户全部会话）
curl -s -X POST http://localhost:8080/medbox/api/v1/auth/refresh \
  -H 'Content-Type: application/json' -d '{"refreshToken":"..."}' | jq

# 5) 注销当前登录账号（feat/backend-user-delete，见文档 02 的 3.1；不可逆，需密码二次确认）
#    注意：是 POST /api/v1/users/me/delete（不是 DELETE），挂在需鉴权的 /api/v1/users/** 下，不放行
curl -s -X POST http://localhost:8080/medbox/api/v1/users/me/delete \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"password":"123456"}' | jq
```

登录校验由 `common/web/AuthInterceptor` 承担（只拦 `/api/v1/**`，放行 `/api/v1/auth/**` 与 `/actuator/**`）；
`GET /users/me` 不在本分支（属 `feat/backend-authz`）。

### 7.1 角色模型（两层，别混淆）

注册接口**只接受两种账号角色**（`user.role`，见 `domain/enums/UserRole`）：

- `ELDER` —— 老人本人，设备近场使用者，只读本人数据；
- `GUARDIAN` —— 监护人 / 子女，对 ACTIVE 监护关系下的老人可读可写。

**`DOCTOR`（家庭医生）/ `NURSE`（社区护理）/ 家属是 `FAMILY`** 不是注册角色，而是**监护关系上的角色**（`guardian_relation.role`，见 `domain/enums/RelationRole`）：
一个 GUARDIAN 账号在**老人确认监护关系时**被标记成 FAMILY / DOCTOR / NURSE，由此决定他能对这位老人做什么（详见文档 01 第 3 章 / 02 的监护关系接口）：

| 关系角色 | 能干什么（对 ACTIVE 关系下的老人） |
|----------|--------------------------------------|
| `FAMILY` 家属 / 子女 | 可读可写：计划、药品、库存、处理告警、阈值、代配 AI Key |
| `DOCTOR` 家庭医生 | 制定 / 修改服药计划、看依从性报告与 AI 洞察；**不处理告警、不导出** |
| `NURSE` 社区护理 | 所辖老人**只读** + 处理告警 + 导出记录；**不可改计划与药品** |

> 所以"医生 / 护士能干什么"的开关落在**监护关系确认**那一步，不在注册接口；传 `role:DOCTOR` 给注册接口会被判非法。

## 9. MQTT（`feat/backend-mqtt`，见文档 03）

后端以订阅方身份连本地 EMQX（**本期匿名连接，无需用户名密码**）：订阅 `medbox/+/+/up/#` 与 `medbox/+/+/status`（遗嘱），
向 `medbox/{pk}/{deviceId}/down/{schedule|command}` 下发 QoS1 消息。业务尚未接线，本分支只负责"收得到、不重复、能下发"。

### 9.1 起 EMQX（不动 `docker-compose.yml`，单独一个容器）

```bash
docker run -d --name emqx -p 1883:1883 -p 18083:18083 emqx/emqx:latest
# Dashboard: http://localhost:18083（默认 admin/public，**务必改掉**）
```

> **不要把 1883 / 18083 做端口映射或内网穿透到公网**：匿名 broker 一旦对外，任何人都能订阅所有 topic、伪造设备数据。
> 在教室 / 公司等公共 WiFi 下演示时用手机热点自己组网（见文档 03 第 1 章）。

### 9.2 没起 EMQX 也能跑后端

```bash
MEDBOX_MQTT_ENABLED=false ./gradlew bootRun     # 或写进 application-local.yml：medbox.mqtt.enabled=false
```

MQTT 相关 Bean 全部条件装配，关闭时一个都不建；已启用但连不上时只 WARN、由 Paho 后台自动重连，**不会阻塞应用启动**。

### 9.3 验收（`mosquitto_pub`，或直接用 EMQX Dashboard 的 WebSocket 客户端）

```bash
mosquitto_pub -h localhost -p 1883 -i BOXA1001 -q 1 \
  -t 'medbox/pk001/BOXA1001/up/telemetry' \
  -m '{"msgId":"BOXA1001-0000000124","ts":1759474487000,"sensorId":"BOXA1001-S-TH01","temperature":24.6,"humidity":52.1}'
```

期望日志（`LoggingUpMessageHandler` 打印摘要）：

```
收到上行消息 deviceId=BOXA1001 topic=medbox/pk001/BOXA1001/up/telemetry bytes=123
```

再发同一条（**同 `msgId`**）应看到 `重复 msgId 已丢弃 ...` —— 这就是本分支的核心验收点。

> **注意 msgId 的去重保留 7 天**：本地重复做同一条验收时请换 `msgId`，否则第二次会被判成重发。

### 9.4 相关表

`V5__mqtt.sql` 建 `mqtt_message`（上行去重，按 `msgId` 唯一键，默认保留 7 天，见文档 06 的 2.15）。

```bash
docker exec -e PGPASSWORD=mypassword medbox-pg psql -U myuser -d medbox -c "select msg_id, topic, received_at from mqtt_message order by id desc limit 10;"
```

### 9.5 后续分支怎么接

| 要做的事 | 怎么接 |
|----------|--------|
| 消费某个上行 topic 的业务逻辑 | 新增一个类实现 `UpMessageHandler`（`supports()` 返回 `up/` 之后的子路径，如 `telemetry`）并注册成 `@Bean`，**不用改本分支代码** |
| 下发计划 / 命令 | 注入 `DownlinkPublisher`，调 `publish(deviceId, "schedule"\|"command", payload, needAck)`；`msgId` / `needAck` 由它注入，别自己拼 |
| 下发失败要告警 | 替换 `DownlinkFailureListener` 的实现即可（当前只打 WARN 日志，告警实体属 `feat/alarm-core`） |

## 10. WebSocket 推送（`feat/backend-ws`，见文档 04）

端点 `ws://{medbox.host}:8080/medbox/ws?token={JWT}&topics=elder:{elderId}[&lastEventId=evt-xxxx]`，
**只支持 `elder:{elderId}` 主题**（`device:` 待 `feat/device-core` 提供设备归属解析）。

```bash
# 1) 登录拿 token（见第 7 节）
# 2) wscat 订阅；每 30s 发一次 {"type":"ping"}，服务端回 PONG（90s 无任何帧会被断开）
wscat -c "ws://192.168.1.10:8080/medbox/ws?token=$TOKEN&topics=elder:e-1001"
# 3) 触发一帧：开 medbox.ws.debug-endpoint-enabled=true 后
curl -X POST http://192.168.1.10:8080/medbox/api/v1/_debug/ws/publish \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"elderId":"e-1001","type":"ALARM"}'
```

- 越权主题（不是我可见的老人）**整个握手被拒**：HTTP 403 + `{"code":40301,...}`；token 无效 401；主题语法错 400。
- 调试端点**默认不装配**（`medbox.ws.debug-endpoint-enabled=false`），生产保持关闭；它仍需登录并通过
  `AccessService.require(elderId, READ_ALARM)`，不是后门。
- 后续业务分支推送一律调 `WsPushService.publish(topic, type, payload)`，**不要自己持有 `WebSocketSession`**。
- 本机没起应用也能跑真链路测试：`MEDBOX_WS_LOCAL_TEST=true ./gradlew test --tests '*WsLocalIntegrationTest'`
  （它会自己拉起随机端口的完整上下文，替代手工 wscat）。

## 11. 联调辅助（devtools，feat/backend-devtools，见文档 02 的 1.5 / 03 的 5.5）

给开发者 / QA 的局域网联调辅助端点，**全部走 `medbox.devtools.enabled` 开关**：

- **默认关闭**（`application.yml` 里 `medbox.devtools.enabled: false`），`DevtoolsController` 根本不装配，`POST /api/v1/_debug/seed` 与 `/_debug/mqtt/publish` 访问即 `40401`，生产零暴露面；
- 置 `true` 重启后才出现，**且无需登录**（端点存在与否本身已被 `devtools.enabled` 门禁，开了即代表本地开发模式，再加登录纯属冗余）；
- 不引入新鉴权实体、不新增错误码（沿用 02 既有码）；seed 落地的全是**写死的固定 ID**（见下表），与调用者身份无关。

```bash
# 0) 开开关：把下面这段写进 application-local.yml（不入库，模板见 application-local.yml.example），
#    然后【必须】激活 local profile 才会被加载，否则写了也不生效：
#      ./gradlew bootRun --args='--spring.profiles.active=local'
#    或环境变量：SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
#
#    application-local.yml 应含：
#      medbox:
#        devtools:
#          enabled: true
#
#    只有 medbox.devtools.enabled=true 时，下面两个 _debug 端点才装配；无需任何登录 token。

# 1) 一键播种最小数据集（返回监护人 token，可立刻复用；幂等：已存在跳过，?reset=true 先清后种）
#    空库直接调即可，不必先注册账号/登录——seed 自己造 13800001234 监护人 + 13800008888 老人。
TOKEN=$(curl -s -X POST http://localhost:8080/medbox/api/v1/_debug/seed | jq -r .data.guardianToken)
echo "$TOKEN"

# 2) 服务端模拟设备上报（等价于"服务端版 mosquitto_pub"，流经真实入站网关→落库→WS 推送）
curl -s -X POST http://localhost:8080/medbox/api/v1/_debug/mqtt/publish \
  -H 'Content-Type: application/json' -d '{
    "deviceId":"BOXA-DEBUG-01",
    "subTopic":"event/dispense",
    "payload":"{\"msgId\":\"BOXA-DEBUG-01-000123\",\"ts\":1759474487000,\"userId\":\"e-1001\",\"planId\":\"p-3301\",\"planItemId\":\"pi-9001\",\"medicineId\":\"m-205\"}" }'
```

> **⚠️ 别预注册保留号**：`13800001234`（监护人）与 `13800008888`（老人）是 seed 写死的保留号。若你先按第 7 节用手动注册占了其中某个手机号，再跑 seed 会因手机号唯一约束冲突而失败——直接空库跑 seed 即可，或换别的手机号做 auth 自测。

> **seed 覆盖哪些角色？** 只造 **1 个 GUARDIAN（监护关系角色 `FAMILY`）+ 1 个 ELDER** 及二者一条 `ACTIVE` 监护关系，**不造 DOCTOR / NURSE 监护人**。返回的 token 是那个 `FAMILY` 监护人的。所以：
> - 测"家属/子女"视角的读写 → 直接用 seed 给的 `guardianToken` 即可；
> - 测"家庭医生 / 社区护理"权限 → seed **给不了**，需自己再注册一个 GUARDIAN 账号，再让老人以 `DOCTOR` / `NURSE` 确认监护关系（接口见文档 02 的监护关系章）；
> - 测"老人本人"只读 → seed 已返回 `elderToken`（对应 `e-1001` / 手机号 `13800008888` / 密码 `123456`），直接 `Authorization: Bearer $ELDER_TOKEN` 即可，无需另走登录。

播种落地的固定业务 ID（与文档 02 §1.4 / 03 §5 的示例完全对齐，使其可直接跑通）：

| 实体 | 业务 ID | 备注 |
|------|---------|------|
| 监护人 | `13800001234` / 密码 `123456` / userId `u-debug-guardian` | 返回 `guardianToken` 即此账号 |
| 老人 | userId `e-1001` / 手机号 `13800008888` / 密码 `123456` | 与监护人直接是 `ACTIVE` 监护关系（跳过申请→确认）；返回 `elderToken` 可直接以老人视角登录 |
| 药箱主控 | `BOXA-DEBUG-01` | productKey 取 `medbox.device.default-product-key`（默认 `medbox-box`） |
| 传感器子设备 | `BOXA-DEBUG-01-S-TH01`（温湿度）/ `BOXA-DEBUG-01-S-LX01`（光照）/ `BOXA-DEBUG-01-S-CAM01`（摄像头） | |
| 药品 | `m-205`（阿司匹林，公共）+ `m-206`（甲硝唑，老人私有） | |
| 库存 | `s-debug-001` | 有效期未过期 |
| 计划 | `p-3301`（含计划项 `pi-9001`，每日 `08:00`） | |

> **为什么只有部分 ID 带 `debug` 段**：业务号由 `user_biz_id_seq` / `medicine_stock_biz_id_seq` 等从 **1001** 起生成 —— 清库后首个注册用户必是 `u-1001`、首个库存必是 `s-1001`，种子若占用它们会与真实数据撞唯一约束，`?reset=true` 的清理更会按固定 ID **误删真实账号 / 库存**；设备号用 `BOXA-DEBUG-01` 是为了不与真机序列号重名（`clearSeed` 会按它删设备）。而 `e-1001` / `m-205` / `p-3301` / `pi-9001` 不在业务序列生成值域内（业务号恒为前缀 + 数字、且从 1001 起），永远不会与真实数据相撞，故保留短 ID。
>
> **从旧版本升级（历史库里有 `u-1001` / `BOXA1001` / `s-1001` 老种子）**：新种子改用上面的 debug ID，旧数据不会自动清。**为什么不直接用管理后台（medbox-admin 测试数据清理）删？** 后台的硬删护栏对**任何 `ACTIVE` 账号一律拒绝**（`AdminUserService.purge()` 抛 `40902`），而种子账号写入时状态恒为 `ACTIVE`，所以后台那条「清理」按钮对种子是**禁用**的——后台删不了种子。另外旧数据也不适合无条件自动清（有误删占用 `u-1001` 的真实账号的风险）。确认要清时**先 `SELECT` 核对，再按下述条件删除**（手机号条件用于兜底，避免误删占用了 `u-1001` 的真实账号）：
>
> ```sql
> DELETE FROM med_record WHERE plan_id = 'p-3301';
> DELETE FROM med_plan_item WHERE plan_id = 'p-3301';
> DELETE FROM med_plan WHERE plan_id = 'p-3301';
> DELETE FROM medicine_stock WHERE device_id = 'BOXA1001';
> DELETE FROM device WHERE device_id = 'BOXA1001' OR device_id LIKE 'BOXA1001-S-%';
> DELETE FROM medicine WHERE medicine_id IN ('m-205', 'm-206');
> DELETE FROM guardian_relation WHERE relation_id = 'r-debug-001';
> DELETE FROM "user" WHERE user_id IN ('u-1001', 'e-1001')
>   AND phone IN ('13800001234', '13800008888');
> ```
>
> 清完再点「一键播种」即可。

- 模拟消息若触发计划下发（`down/schedule`），设备不在场会 `SYNC_FAILED`，属预期，不视为缺陷。
- MQTT 没启 / 没连 broker 时该端点返回 `50000`，先按第 9 节起好 EMQX。
- 完整字段语义见文档 02 的 1.5（seed 响应结构）与 03 的 5.5（payload 与各 `subTopic` 对齐）。
