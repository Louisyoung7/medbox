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

安全开关：`clean-disabled: true`（禁止误清库）、`validate-on-migrate: true`（脚本被改动即启动失败）、不开 `baseline-on-migrate`。

## 4. 验收

```bash
# 15 张表
docker exec -e PGPASSWORD=mypassword pgvector_db psql -U myuser -d medbox -c "\dt"
# 索引（含 V2 的 HNSW）
docker exec -e PGPASSWORD=mypassword pgvector_db psql -U myuser -d medbox -c "\di"
# pgvector 扩展
docker exec -e PGPASSWORD=mypassword pgvector_db psql -U myuser -d medbox -c "select extname from pg_extension;"
# 迁移记录（应为 V1 / V2 两条 success = t）
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

**持久层框架定为 MyBatis-Plus**（见文档 07）。本分支（地基 `feat/backend-db`）只做建表与迁移，**未引入该依赖**，由后续功能分支按需引入并写实体 / Mapper。
