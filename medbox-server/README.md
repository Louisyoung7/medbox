# medbox-server

家用智能药品箱 · Java 后端（Spring Boot 4 / Java 21 / Gradle，基础包 `com.medbox.server`）。

| 场景 | 命令 |
|------|------|
| 启动 | `./gradlew bootRun`（或 IDE 运行 `com.medbox.server.MedboxServerApplication`） |
| 构建 | `./gradlew build` |
| 测试 | `./gradlew test` |
| 健康检查 | `GET http://localhost:8080/medbox/actuator/health` |

任务清单见 `../medbox-spec/07_特性分支清单_后端.md`，表结构见 `../medbox-spec/06_数据库设计.md`。

## 1. 准备数据库（PostgreSQL 17 + pgvector）

后端在 **PostgreSQL 17 + pgvector**（默认 `localhost:5432`，库 `medbox`）上运行。两种方式二选一。

### A. 本机已有 PG 实例（本机 `pgvector_db` 容器即如此，推荐）

只需建一次库（`myuser` 是超级用户时可直接执行；扩展由 Flyway 自动建）：

```bash
docker exec -e PGPASSWORD=mypassword pgvector_db \
  psql -U myuser -d mydb -c "CREATE DATABASE medbox OWNER myuser;"
```

> 本机没有安装 `psql` 客户端，SQL 一律用上面的 `docker exec` 方式执行。

### B. 没有现成实例：用 compose 起一个

```bash
cd medbox-server
docker compose up -d        # pgvector/pgvector:pg17，库 medbox / 账号 myuser / 密码 mypassword
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
| `V1__init_schema.sql` | `CREATE EXTENSION IF NOT EXISTS vector` + 15 张表 + 约束 + 5 个普通索引 |
| `V2__vector_hnsw.sql` | `drug_manual_chunk` 的 HNSW 索引（余弦距离） |
| `V3__auth.sql` | `user_biz_id_seq` 序列 + `refresh_token` + `idempotency_record`（`feat/backend-auth`，见文档 06 的 2.13 / 2.14） |
| `V4__authz.sql` | `guardian_relation.relation_id` 业务列 + `guardian_relation_biz_id_seq` 序列 + 关系查询索引（`feat/backend-authz`，见 06 的 2.2） |
| `V5__mqtt.sql` | `mqtt_message` 上行消息去重表（`feat/backend-mqtt`，见文档 06 的 2.15） |
| `V6__device_event.sql` | `device_event` 设备事件流水 + `device` 表两个索引（`feat/device-core`，见 06 的 2.16） |
| `V7__user_delete.sql` | `"user"` 表加 `status` / `deleted_at` 两列，**账号注销**（`feat/backend-user-delete`，见 02 的 3.1 / 06 的 2.1） |

安全开关：`clean-disabled: true`（禁止误清库）、`validate-on-migrate: true`（脚本被改动即启动失败）、不开 `baseline-on-migrate`。

## 4. 验收

```bash
# 15 张表
docker exec -e PGPASSWORD=mypassword pgvector_db psql -U myuser -d medbox -c "\dt"
# 索引（含 V2 的 HNSW）
docker exec -e PGPASSWORD=mypassword pgvector_db psql -U myuser -d medbox -c "\di"
# pgvector 扩展
docker exec -e PGPASSWORD=mypassword pgvector_db psql -U myuser -d medbox -c "select extname from pg_extension;"
# 迁移记录（应为 V1 / V2 / V3 三条 success = t）
docker exec -e PGPASSWORD=mypassword pgvector_db psql -U myuser -d medbox \
  -c "select installed_rank, version, success from flyway_schema_history order by installed_rank;"
```

## 5. 常见问题

| 现象 | 原因 / 处理 |
|------|-------------|
| `Unsupported Database: PostgreSQL` | 缺 `flyway-database-postgresql`（Flyway 10+ 把数据库支持拆成了独立模块） |
| 启动没有任何 Flyway 日志、表也没建 | Boot 4 把自动装配拆成了独立模块，缺 `org.springframework.boot:spring-boot-flyway` —— **只加 `flyway-core` 会静默不迁移**（无报错） |
| 启动报扩展权限不足 | `CREATE EXTENSION` 需要库所有者 / 超级用户；先用超管执行一次 `CREATE EXTENSION IF NOT EXISTS vector;`（V1 里是 `IF NOT EXISTS`，不会重复创建） |
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
# 1) 注册（带上 X-Request-Id，重复提交只会建一个号）
curl -s -X POST http://localhost:8080/medbox/api/v1/auth/register \
  -H 'Content-Type: application/json' -H 'X-Request-Id: 8f2c1a9e3b7d4c1a9f0e2d5b6a7c8f10' \
  -d '{"phone":"13800001234","password":"123456","role":"GUARDIAN","name":"李四"}' | jq

# 2) 登录拿 access token
TOKEN=$(curl -s -X POST http://localhost:8080/medbox/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"account":"13800001234","password":"123456"}' | jq -r '.data.token')

# 3) 业务接口一律带 -H "Authorization: Bearer $TOKEN"（未带 / 过期 → 40101；40102 只出现在登录/注册）

# 4) 刷新（旧的 refresh token 立即作废，重放会撤销该用户全部会话）
curl -s -X POST http://localhost:8080/medbox/api/v1/auth/refresh \
  -H 'Content-Type: application/json' -d '{"refreshToken":"..."}' | jq
```

登录校验由 `common/web/AuthInterceptor` 承担（只拦 `/api/v1/**`，放行 `/api/v1/auth/**` 与 `/actuator/**`）；
`GET /users/me` 不在本分支（属 `feat/backend-authz`）。

## 8. 本地 AI 服务（OCR / Embedding，feat/backend-ai-infra）

OCR 转文本与中文文本向量化**全部本地化、不出网、无需 Key**（见文档 05），为后续 `feat/ai-chat` / `feat/manual-ingest` 打地基。

- **Ollama（Embedding）**：本机起 Ollama 并拉取 `bge-m3`（1024 维）：
  ```bash
  docker run -d --name ollama -p 11434:11434 ollama/ollama
  docker exec ollama ollama pull bge-m3
  ```
  `spring.ai.ollama.base-url`（默认 `http://localhost:11434`）、`spring.ai.ollama.embedding.model=bge-m3` 已在 `application.yml`，无需覆盖。
- **RapidOCR 侧车（OCR）**：一个 FastAPI 包一层、`POST /ocr` 收图片/PDF 返回纯文本。源码已落在仓库内 `medbox-server/ocr-sidecar/`（`main.py` + `requirements.txt` + `Dockerfile`），**不在 `src/main/resources`**（那只是 Java classpath 资源，会被打进 JAR，跑不起来）。
  - 用 Docker 起（推荐，与 pgvector 一起 `docker compose up`）：
    ```bash
    docker compose up -d ocr      # 构建并起 medbox-ocr，监听 :8000
    curl -F file=@说明书.jpg http://localhost:8000/ocr
    ```
  - 或本机直接跑（需 Python 3.11+）：
    ```bash
    cd medbox-server/ocr-sidecar
    pip install -r requirements.txt
    uvicorn main:app --host 0.0.0.0 --port 8000
    ```
  `medbox.ocr.endpoint`（默认 `http://localhost:8000/ocr`）已在 `application.yml`；`medbox.ocr.provider=cloud` 可切换到云端 OCR（本期仅骨架，未实装）。
- **验证（本地集成测试）**：起好上面两个服务后，设环境变量 `MEDBOX_AI_LOCAL_TEST=true`，运行 `AiLocalIntegrationTest`：① 中文文本 embed→1024 维；② 渲染带文字图片 OCR→非空；③ PgVectorStore 写入 + 相似召回回环。**CI 无本地 AI 栈，该测试自动跳过。**

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
docker exec -e PGPASSWORD=mypassword pgvector_db psql -U myuser -d medbox -c "select msg_id, topic, received_at from mqtt_message order by id desc limit 10;"
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
