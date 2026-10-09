# medbox · 家用智能药品箱

单仓多目录：后端、小程序、Web 前端各占一个目录，接口与设计文档同仓存放。**先做后端与小程序，Web 前端（`medbox-admin/`）本期占位不实现。**

## 目录结构

| 目录 | 内容 | 技术栈 | 任务清单 | 状态 |
|------|------|--------|----------|------|
| `medbox-server/` | Java 后端 | Spring Boot 4 / Java 21 / Gradle | [`medbox-spec/07`](medbox-spec/07_特性分支清单_后端.md) | 进行中 |
| `medbox-miniapp/` | uni-app 小程序 | Vue 3 + Vite | [`medbox-spec/08`](medbox-spec/08_特性分支清单_小程序.md) | 进行中 |
| `medbox-admin/` | Web 前端 / 管理后台 | 未定 | 无（待设计） | ⏸ 本期占位 |
| `medbox-spec/` | 接口与设计文档（唯一权威副本） | — | — | ✅ |

各端**独立构建**：后端在 `medbox-server/` 用 Gradle（`./gradlew bootRun` / `./gradlew build`），小程序在 `medbox-miniapp/` 用 npm，仓库根没有统一构建脚本。

## CI（GitHub Actions）

两套技术栈**各一个 workflow**，靠 `paths` 过滤互不触发（详见 [`medbox-spec/01` 的 4.0.1](medbox-spec/01_系统架构与部署.md)）：

| Workflow | 触发目录 | 命令 |
|----------|----------|------|
| `ci-server.yml` | `medbox-server/**` | JDK 21 + `./gradlew build` |
| `ci-miniapp.yml` | `medbox-miniapp/**` | Node 20 + `npm ci` + `npm run build:mp-weixin` |

## 文档

入口：[`medbox-spec/00_README_文档索引.md`](medbox-spec/00_README_文档索引.md)

- 快速上手：后端 / 小程序看 `01` + `02`，建库看 `06`，设备接入看 `03`，实时推送看 `04`，AI 问答看 `05`
- 接手新版本**先看 [`medbox-spec/CHANGELOG.md`](medbox-spec/CHANGELOG.md)**，里面写明"影响哪个端"
- 开发前先到 `07` / `08` 认领分支：`feat/backend-xxx` 只改 `medbox-server/`，`feat/mp-xxx` 只改 `medbox-miniapp/`

文档就在这个仓库里，**不通过 git subtree 同步**；改了接口 / 字段 / 表结构时，请把 `medbox-spec/` 的改动与代码放进同一个 PR（可以分开 commit）。

## AI 助手

工作约定见仓库根的 [`AGENTS.md`](AGENTS.md)（唯一一份，AI 工具自动加载）。

> 不用软链指向 `medbox-spec/`：Windows 下 git 对软链支持不可靠（默认 `core.symlinks=false`，会检出成文本文件）。
