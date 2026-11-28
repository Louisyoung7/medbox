# 速览（QUICKSTART）— 只需要看这一页

> 本页回答三件事：项目是什么、怎么开工、想查东西去哪。其余文档当字典用，不用通读。

## 一、项目是什么（10 行版）

家用智能药品箱，纯局域网通信：

- **设备**（药箱主控，环境遥测经 MQTT、摄像头抓拍经 HTTP）上报
- **Java 后端**（`medbox-server/`，Spring Boot 4 + Java 21）落库并判定
- **uni-app 客户端**（`medbox-app/`，主交付端 Android APK；H5 仅本地联调）通过 **HTTP REST + WebSocket** 交互
- **AI 问答**走大模型（后端不托管，监护人代配 Key）
- `medbox-admin/` 本期只做测试数据清理工具
- 本目录 `medbox-spec/` 是唯一权威文档集

## 二、怎么开工（三步）

1. **同步**：`git switch main && git pull && git switch -`
2. **开工**：从 main 切特性分支（后端 `feat/backend-xxx` / 客户端 `feat/app-xxx` / Web `feat/admin-xxx` / 文档 `docs/xxx`），一切改动都在特性分支上，绝不碰 main
3. **收尾**：修正受影响的 spec 文档 → 与代码同一 PR 提交

> 首轮开发的分支清单已归档（`archive/07` / `08` / `09`），查历史分支与依赖关系去归档；后续新增分支不再建清单文件，实现口径直接沉淀到对应 spec 文档。

细节规则（集成测试、rebase、Flyway 编号等）在仓库根 `AGENTS.md`，AI 自动加载，**人不用看**。

## 三、查东西去哪

| 我想查… | 去这 |
|---------|------|
| 某接口的字段 / 错误码 | `openapi/<模块>.yaml`（**唯一契约源**） |
| 某业务怎么判定（漏服、告警、提醒） | `07_业务规则.md`，或该模块分册 `02a~02h`、`02j` 的规则表 |
| 表结构 / DDL | `06_数据库设计.md` |
| 设备 MQTT 上报格式 | `03_设备接入协议_MQTT.md` |
| WebSocket 推送格式 | `04_实时推送协议_WebSocket.md` |
| AI 大模型怎么接 | `05_AI大模型与洞察层方案.md` |
| 全局口径（时区 / 权限 / 部署） | `01_系统架构与产品定义.md` |
| 项目进展到哪了 | `git log --oneline` 与历史分支清单（归档 `archive/07` / `08` / `09`） |
| 架构图 | `diagrams/` 下 HTML |
| 历史变更 | `git log -- medbox-spec/`（变更归档在 `archive/CHANGELOG.md`，已冻结） |

## 四、人看哪层，AI 看哪层

文档按三层取用，**人日常从第一层往下看，不用通读本目录**：

| 层 | 文档 | 谁看 / 什么时候看 |
|----|------|-------------------|
| ① 日常首选 | 仓库根 `docs/`：`产品使用说明.md`、`调试测试流程/`、`API与配置对接/` | **人看得最多**：怎么用、怎么联调、设备怎么接 |
| ② 实现细节 | 各端 README：`medbox-server/`、`medbox-app/`、`medbox-admin/` 各自的 `README.md`，**以及 tools 里的** `medbox-server/tools/device-sim/README.md`、`medbox-app/tools/mock/README.md` | 跑起来、配环境、用工具时查 |
| ③ 权威口径 | 本目录 `medbox-spec/`（`QUICKSTART.md`、`openapi/*.yaml`、`07`、`06`、`03`~`05`、`08`、`contracts/`、`diagrams/`） | **主要交给 AI 维护和查阅**；人只在讨论口径、核对契约 / 规则 / 表结构、评审 yaml diff 时按需查 |

仍要人拍板的内容：`07` 业务规则与各分册规则表的「为什么这么算」、归档 `archive/07/08/09` 里首轮分支的做法。

一句话：**「怎么算、为什么」人来拍板（07 + 分册规则表），「是什么、长什么样」AI 去查（yaml + 03/04/06）**；使用与联调说明写在 `docs/` 和各端 README，不放进本目录。

## 五、给 AI 助手的话

动手前：读仓库根 `AGENTS.md`（自动加载）→ 本页 → 对应模块文档。契约以 yaml 为准，发现歧义先问用户，不要擅自偏离文档。
