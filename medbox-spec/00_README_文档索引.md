# 家用智能药品箱 · 后端 / 小程序接口设计文档（文档集）

> 范围：Java 后端（Spring Boot）+ uni-app 小程序　|　协议：HTTP REST、MQTT（EMQX）、WebSocket
> 仓库：**单仓多目录** —— 后端 `medbox-server/`、小程序 `medbox-miniapp/`、Web 前端 `medbox-admin/`（本期先做「测试数据清理」dev/QA 工具，设计见归档 `archive/09`）；文档即本目录 `medbox-spec/`，**不再用 git subtree 同步**（见文末《文档就在本仓库》）。
> 通信方式：当前全部为局域网（内网）通信，无公网域名。
> 历史变更已冻结，归档于 `archive/CHANGELOG.md`；最新口径以各分册正文为准，变更历程以 `git log -- medbox-spec/` 为准。

本文档集按主题拆分为多个文件，便于分工评审与联调。各文件内容互不重复，统一约定见 01，数据库设计统一见 06。

| 文件 | 内容 | 主要读者 |
|------|------|----------|
| `QUICKSTART.md` | **速览单页**：项目是什么、怎么开工、查东西去哪（新读者只看这一页即可） | 全员 |
| `00_README_文档索引.md` | 本索引（完整目录与约定） | 全员 |
| `01_系统架构与产品定义.md` | **统一约定与全局口径**：项目背景与产品定位、药箱物理形态（带锁分层）与工作原理 / 后端极简职责边界、功能范围（4 业务 + 1 底座）、协议分工、角色权限（RBAC + 数据归属）、局域网部署与地址规划（IP/端口规划，本地配置细节见各子项目 README）、关键业务流程串联 | 全员 |
| `02_REST接口设计.md` | REST **通用约定**（统一响应 / 错误码 / 权限默认规则）、**核心数据模型**、**模块索引**；业务模块接口已拆为 8 个分册：`02a_认证与用户` / `02b_设备管理` / `02c_药品档案` / `02d_服药计划与提醒` / `02e_服药记录与依从性` / `02f_告警` / `02g_环境监测` / `02h_管理员接口`（均在本目录） | 后端 / 小程序 |
| `03_设备接入协议_MQTT.md` | EMQX 连接鉴权、Topic 规范、上下行 Payload 示例 | 后端 / 嵌入式 |
| `04_实时推送协议_WebSocket.md` | 连接鉴权、消息帧结构、推送事件类型 | 后端 / 小程序 |
| `05_AI大模型与洞察层方案.md` | 大模型接入（洞察层）：分析 / 预测 / 建议接口、LLM 配置（BYOK，监护人代配 Key）；后端不托管大模型、不做 RAG | 后端 |
| `06_数据库设计.md` | 数据库**表结构**：实体关系、PostgreSQL 关系表建表 DDL；**判定/生成类业务规则见 `07`** | 后端 |
| `07_业务规则.md` | **判定与生成类业务规则**：med_record 服药判定、MISS/ENV 告警判定、REMINDER 提醒生成、漏服/时区/容忍公共口径（库表字段与 DDL 见 `06`） | 后端 |
| `openapi/`（**本目录子目录**） | **REST 接口的 OpenAPI 机器可读版（已覆盖 02a~02h 全部模块 + AI 契约 02i）**：`common.yaml`（公共组件：统一响应 / 通用错误码响应 / 分页 / X-Request-Id，各分册跨文件 `$ref`）+ `02a-auth-users` `02b-devices` `02c-medicines` `02d-plans` `02e-records` `02f-alarms` `02g-env` `02h-admin` `02i-ai` 共 9 份契约 yaml（经 redocly lint 零告警）+ `api-docs-<模块>.html`（`npx @redocly/cli build-docs` 生成的人类可读 WebUI，可离线分享）+ `swagger-ui.html`（浏览器调试页，`npx serve .` 后访问 `swagger-ui.html?spec=<模块>.yaml`，Try it out 直连真实后端）+ `redocly.yaml`（lint 配置）。**契约（端点 / 字段 / 响应示例 / 错误码）以 YAML 为准**；`02a`~`02h` 分册与 `05` 的接口章节只保留判定规则表、边界用例与口径说明（已按此精简），两者不一致即视为缺陷 | 后端 / 小程序 / 全员 |
| `archive/07` / `archive/08` / `archive/09` | **首轮开发的特性分支清单（已归档，不再追加）**：后端 37 个、小程序 23 个、管理后台设计（`feat/admin-testdata-cleanup`）；查历史分支与依赖关系去归档 | 后端 / 小程序 / Web |
| `archive/CHANGELOG.md` | **历史归档（已冻结，不再追加）**：变更历程以 `git log -- medbox-spec/` 为准 | 全员 |
| `diagrams/`（**本目录子目录**） | 架构与拓扑图：`backend-architecture.html`（后端架构）、`frontend-architecture.html`（前端组件架构）、`network-topology.html`（网络拓扑，源文件已废弃改用 HTML 维护） | 全员 |
| `AGENTS.md`（**仓库根**，不在本目录） | **给 AI 助手的工作约定**：目录约定、特性分支流程、开工 pull / 完工同步文档（与代码同 PR）、远程更新 rebase、AI 的提醒义务 | AI 助手 / 全员 |

**快速上手**：**所有人先看 `QUICKSTART.md`（速览单页）**；小程序与后端同学再按需看 01 + 02（REST 根文档与其模块分册）；建库 / 建模看 06；**服药/告警/提醒的判定规则看 `07`**；嵌入式同学看 01 + 03；做监护端实时推送看 04；做 AI 洞察看 05。

**本仓库的目录约定**（详细见 01 的 4.0）：

| 目录 | 内容 | 本期 |
|------|------|------|
| `medbox-server/` | Java 后端（Spring Boot 4 / Java 21 + Gradle），首轮分支清单见 `archive/07` | ✅ 实现 |
| `medbox-miniapp/` | uni-app 小程序（Vue3 + Vite），首轮分支清单见 `archive/08` | ✅ 实现 |
| `medbox-admin/` | Web 前端 / 管理后台 | ⏸ **本期先做「测试数据清理」dev/QA 工具**（设计见 `archive/09`），其余管理功能待定 |
| `medbox-spec/` | 本文档集（唯一权威副本） | ✅ |

> 文档里的相对路径示例（如 `src/main/resources/application.yml`、`src/config/index.js`）**均相对对应端的子目录**，不是仓库根；详见 01 的 4.0。

---

## 分支与任务约定

- 一个功能 = 一个特性分支；分支命名约定：后端 `feat/backend-xxx`、小程序 `feat/mp-xxx`、Web `feat/admin-xxx`、文档 `docs/xxx`（**单仓下天然不冲突**）；
  - **一个分支只动一个端**（只改 `medbox-server/` 或只改 `medbox-miniapp/`），跨端改动必须在 PR 里说明并尽量拆开；
- **修 Bug、重构项目结构不单独记账**，随所在模块一起提交；
- **首轮开发的分支清单已归档**（`archive/07` / `08` / `09`，不再追加）——查历史分支、依赖关系与当时口径去归档；后续新增分支**不再建清单文件**，开工前口头约定分支名即可，实现口径沉淀到对应 spec 文档；
- **AI 助手的完整工作流（含提醒义务）见仓库根 `AGENTS.md`**，尤其：开工前 `git pull` 并看 `git log -- medbox-spec/`、完工时**把文档改动与代码放进同一个 PR**（可分开 commit）、远程更新时回主分支 fetch 再 rebase。
- **实现时遵守 `AGENTS.md` 3.1 的三条约定**：功能分支带集成测试并出报告、判定型分支先出规则表、同类待办合并成一个分支。

## 文档与代码同仓

代码与文档在**同一个仓库 `medbox`**：文档位于 `medbox-spec/`，与 `medbox-server/`、`medbox-miniapp/` 并列，只此一份权威副本。

### 改动约定

- **改了接口 / 字段 / 表结构 → 同一 PR 内改 `medbox-spec/`**：修正不一致处，不允许"代码改了文档没改"；变更历史以 git 为准；
- **开工前看 `git log --oneline -5 -- medbox-spec/`** 判断文档有没有变、要不要跟着改（变更归档 `archive/CHANGELOG.md` 已冻结）；
- 改动直接落在 `medbox-spec/` 下，与代码一起提交（如 `git add medbox-server/ medbox-spec/ && git commit -m "feat: ...; docs(spec): 同步最新口径"`）。

### AGENTS.md（与 `medbox-spec/` 同级，唯一一份）

仓库根目录的 `AGENTS.md` 规定了给 AI 助手的完整工作流（特性分支、开工 pull、完工同步文档、远程更新 rebase、提醒义务），AI 工具自动加载。

> **不要用软链**：Windows 下 git 默认 `core.symlinks=false`，软链会被检出成普通文本文件，因此 `AGENTS.md` 是根目录的真实文件、`medbox-spec/` 下不再放同名文件。
