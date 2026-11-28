# medbox · 家用智能药品箱

家用智能药品箱：带锁药格按计划到点亮灯、响铃、解锁，老人取药服用；家人通过手机 APP 安排开格时间、维护药品档案、查看服药记录与告警。

- **设备**（主控 + 传感器 + 摄像头）经 **MQTT（EMQX）** 和 **HTTP** 上报，Java 后端落库并判定，再通过 **WebSocket** 推给客户端；客户端以 **HTTP REST** 读写。
- 主交付端为 **Android APK**，H5 仅用于本地开发联调；当前全部为**局域网**通信，无公网域名。
- AI 问答走大模型：后端不托管模型、不做 RAG，由监护人代配 Key。
- `medbox-admin/` 本期只做「测试数据清理」dev/QA 工具，真实产品后台待设计。

## 目录结构

单仓多目录，代码与文档同仓，各端独立构建；仓库根没有统一构建脚本。

| 目录 | 内容 | 技术栈 | 任务清单 | 状态 |
|------|------|--------|----------|------|
| `medbox-server/` | Java 后端 | Spring Boot 4 / Java 21 / Gradle | [`archive/07`](medbox-spec/archive/07_特性分支清单_后端.md) | 实现中 |
| `medbox-app/` | uni-app 客户端（生产端 Android APK；H5 仅本地联调） | Vue 3 + Vite | [`archive/08`](medbox-spec/archive/08_特性分支清单_小程序.md) | 实现中 |
| `medbox-admin/` | Web 前端（本期仅测试数据清理工具） | Vue 3 + Vite + Element Plus | [`archive/09`](medbox-spec/archive/09_管理后台设计_medbox-admin.md) | 工具已实现，真实后台待设计 |
| `medbox-spec/` | 接口与设计文档（唯一权威副本） | — | [文档索引](medbox-spec/00_README_文档索引.md) | 持续维护 |
| `docs/` | 产品使用说明、设备与 APP 联调流程 | — | — | 持续补充 |

## 快速开始

环境要求：**JDK 21**、**Node 24**、Docker（本地 PostgreSQL 17 / EMQX 5.8，或连接已有实例）；出 APK 另需 HBuilderX（CI 只产出 App 资源，不产安装包）。

| 端 | 命令 | 说明 |
|----|------|------|
| 后端 | `cd medbox-server && ./gradlew bootRun` | 需先按 [`medbox-server/README.md`](medbox-server/README.md) 准备 PostgreSQL 17；Flyway 自动建表。健康检查：`http://localhost:8080/medbox/actuator/health` |
| 客户端 H5 | `cd medbox-app && npm install && npm run dev:h5` | 打开 `http://localhost:5173`；后端地址写在 `.env.local` 的 `VITE_SERVER_HOST` |
| 客户端 App | `npm run dev:app-plus` / `npm run build:app-plus` | HBuilderX 真机运行 / 出包资源，详见 [`medbox-app/README.md`](medbox-app/README.md) |
| Web 工具 | `cd medbox-admin && npm install && npm run dev` | `http://localhost:5173`，`/medbox` 经 Vite 反代到后端 8080 |

没有真设备或真后端时：

- `cd medbox-app && npm run mock`：自起 Mock 后端（18099 端口，不占 8080），细节见 [`tools/mock/README.md`](medbox-app/tools/mock/README.md)。
- 客户端「我的 → 开发调试」页可一键播种数据并发送模拟设备事件（调 `POST /_debug/seed`、`POST /_debug/mqtt/publish`）。
- 设备侧联调用 [`device-sim`](medbox-server/tools/device-sim/README.md)（MQTT 会话、抓拍、同步回执冒烟）。

## 测试与 CI

测试分层的职责与命令见 [`medbox-spec/08_测试体系与CI.md`](medbox-spec/08_测试体系与CI.md)和[`contracts/README.md`](medbox-spec/contracts/README.md)。

| 场景 | 命令 |
|------|------|
| 后端单元 + H2 集成测试 | `cd medbox-server && ./gradlew test`（不依赖外部 DB） |
| 后端真实 SQL / Flyway | `./gradlew infraTest`（Testcontainers PostgreSQL，需 Docker） |
| 客户端单元测试 | `cd medbox-app && npm test` |
| 客户端 Mock 端到端 | `npm run test:e2e` |
| 客户端契约场景（对 Mock） | `npm run test:contract:mock` |
| 契约场景（对真后端） | `node medbox-app/tools/contract/run.mjs --base http://127.0.0.1:8080/medbox/api/v1 --profile real` |
| Web 工具单测 | `cd medbox-admin && npm test` |

GitHub Actions 三套 workflow，靠 `paths` 过滤互不触发：

| Workflow | 触发目录 | 主要步骤 |
|----------|----------|----------|
| [`ci-server.yml`](.github/workflows/ci-server.yml) | `medbox-server/**`、`medbox-spec/contracts/**`、`medbox-app/tools/contract/**` | JDK 21：`./gradlew test`、device-sim 单测、`./gradlew infraTest`，以及真实 PG + EMQX 的 `real-e2e`（契约 `--profile real` + device-sim 冒烟） |
| [`ci-app.yml`](.github/workflows/ci-app.yml) | `medbox-app/**`、`medbox-spec/contracts/**` | Node 24：`npm ci` → `npm test` → `npm run test:e2e` → `npm run test:contract:mock` → `npm run build:h5` → `npm run build:app-plus` |
| [`ci-admin.yml`](.github/workflows/ci-admin.yml) | `medbox-admin/**` | Node 24：`npm ci` → `npm test` → `npm run build`，构建产物上传 artifact |

## 文档阅读约定

文档很多，按下面三层取用，**不要一上来就通读 `medbox-spec/`**：

| 优先级 | 文档 | 给谁 / 什么时候看 |
|--------|------|-------------------|
| ① 日常首选 | [`docs/`](docs/) | **人看得最多**：产品使用说明、调试测试流程、API 与配置对接 |
| ② 实现细节 | 各端 README，**含 `tools/` 里的 README** | 跑起来、配环境、用工具时查（见下方清单） |
| ③ 权威口径 | [`medbox-spec/`](medbox-spec/00_README_文档索引.md) | 唯一权威副本，**主要交给 AI 维护和查阅**；人只在核对契约 / 规则 / 表结构时按需查 |

各端 README（②层）：[`medbox-server/README.md`](medbox-server/README.md)、[`medbox-app/README.md`](medbox-app/README.md)、[`medbox-admin/README.md`](medbox-admin/README.md)；工具 README 别漏：[`medbox-server/tools/device-sim/README.md`](medbox-server/tools/device-sim/README.md)、[`medbox-app/tools/mock/README.md`](medbox-app/tools/mock/README.md)。

③层按需入口：[`QUICKSTART.md`](medbox-spec/QUICKSTART.md)（速览）、[`openapi/`](medbox-spec/openapi/)（接口契约唯一来源，`swagger-ui.html` 可直调后端）、`07` 业务规则、`06` 表结构、`03` MQTT / `04` WebSocket / `05` AI、`08` 测试体系、[`contracts/`](medbox-spec/contracts/README.md)、[`diagrams/`](medbox-spec/diagrams/) 架构图。

## 开发约定

- 一切改动都在特性分支上，**绝不在 `main` 开发**；开工前先 `git switch main && git pull && git switch -`。
- 分支命名：后端 `feat/backend-xxx`、客户端 `feat/app-xxx`、Web `feat/admin-xxx`、文档 `docs/xxx`；**一个分支只动一个端**。
- 功能分支必须带集成测试并输出测试报告；判定型分支（漏服、提醒、库存、权限等）先出规则表确认再写代码；能一起测的待办合并成一个分支。
- 改了接口 / 字段 / 表结构，必须在同一 PR 内更新 `medbox-spec/`（接口以 OpenAPI YAML 为准）；代码合入 `main` 前文档必须已合入。
- Flyway 迁移取当前最大版本号 + 1 后立即合并，不预留版本号；远程更新时回主分支 fetch，再回特性分支 `rebase`。
- 首轮分支清单已归档至 `medbox-spec/archive/07`、`08`、`09`，查历史分支与依赖关系去归档；变更历史看 `git log -- medbox-spec/`（原 CHANGELOG 已冻结归档）。

## AI 助手

工作约定见仓库根的 [`AGENTS.md`](AGENTS.md)（唯一一份，AI 工具自动加载）。

> 不用软链指向 `medbox-spec/`：Windows 下 git 默认 `core.symlinks=false`，软链会被检出成普通文本文件。
