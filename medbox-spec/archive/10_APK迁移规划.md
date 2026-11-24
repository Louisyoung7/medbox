# APK 迁移规划（小程序 → APK，保留 H5 联调）【已归档】

> 归档状态：P0–P4 已全部合入 main（P4 合并提交 `dff5059`），迁移完成，本文档冻结、不再更新。
> 后续口径：发版看 `medbox-app/README.md`「APK 发版」，接口看 `medbox-spec/02j_客户端版本.md`；仅 P4 真机验收（安装 / 覆盖升级 / 局域网明文 HTTP）待人工完成。
> 适用范围：`medbox-app/`（原 `medbox-miniapp/`）、`medbox-server/`、`medbox-spec/`、CI 与全部文档
> 原则：换壳不换业务。后端业务接口与判定逻辑不动。

---

## 1. 背景与目标

- 现状：用户端是微信小程序。
- 目标：主端改为 Android APK。
- 保留：H5 只用于开发联调与快速回归。
- 范围：前端、后端配套、CI、文档全量对齐。

---

## 2. 现状核查结论

| 项 | 现状 | 对迁移的影响 |
|---|---|---|
| 认证 | 账号密码 + refresh token，无微信登录 | 后端不改 |
| WS 鉴权 | 握手 `?token=` 传参 | APK 可直接用 |
| 抓拍图 | 短期 `fileToken` 拼 URL | 不依赖小程序特性 |
| 构建 | 已装 `@dcloudio/uni-app-plus`，manifest 已有 app-plus 段 | 切编译目标即可 |
| AI 流式 | 小程序走 `wx.request({enableChunked:true})` | App 需换传输实现 |
| 文件导出 | `uni.shareFileMessage` + `uni.getFileSystemManager` | 仅小程序可用，需替换 |
| 文档 | 15 份 md、3 个 workflow 出现「小程序 / mp-weixin」 | 需全量改口径 |

结论：迁移主路径是「换编译目标 + 改 3 处平台耦合 + 改文档」，不需要重写业务。

---

## 3. 目标形态

| 端 | 定位 | 构建方式 |
|---|---|---|
| Android APK | 主交付端，老人 / 监护人日常使用 | `npm run build:app-plus` + HBuilderX 出包 |
| H5 | 开发联调、浏览器快速回归 | `npm run dev:h5`，走 Vite 反代 |
| 后端 | 不变 | 现有 REST / WS 协议保持 |

H5 不对外部署。H5 测不了通知、文件保存等真机能力，相关能力以 APK 验收为准。

---

## 4. 决策（已确认，按建议执行）

| # | 决策 | 选项 | 结论 |
|---|---|---|---|
| 1 | 技术栈 | 继续 uni-app / 换 Capacitor、Flutter | 继续 uni-app |
| 2 | 目录与分支命名 | 保留 `medbox-miniapp/`、`feat/mp-xxx` / 改 `medbox-app/`、`feat/app-xxx` | 重命名，分支改 `feat/app-xxx` |
| 3 | 后台提醒 | A 前台实时 + 回前台补拉 / B 本地通知 / C 轮询 / D 第三方推送 | 先 A，后 B |
| 4 | H5 定位 | 仅本地联调 / 需部署给他人访问 | 仅本地联调，后端加 CORS 的事不做 |

决策已确认，阶段顺序按「结论」列编排；后续若调整，只影响 P1 与 P3 的范围。

---

## 5. 阶段与分支计划

### P0 · `docs/app-migration`（文档）

目标：先把口径改对，后续代码按文档执行。

- [x] `AGENTS.md`：0.1 目录表、三条硬要求、铁律 1 分支前缀、第 2 / 6 / 7 节命令
- [x] `README.md`：目录表、CI 表、构建命令
- [x] `medbox-miniapp/README.md`：标题、H5 定位、APK 打包前置、删微信开发者工具段
- [x] `medbox-spec/00`：标题、范围、目录表、分支命名、测试矩阵
- [x] `medbox-spec/01`：产品定义、架构图、部署表、4.2 配置、角色表述
- [x] `medbox-spec/02a` / `02b` / `02g`：「小程序侧口径」改「客户端口径」
- [x] `medbox-spec/03` / `06`：注释性表述
- [x] `medbox-spec/04`：标题、连接数约定
- [x] `medbox-spec/05`：§2.6 客户端传输说明
- [x] `medbox-spec/08`：L1–L3 路径、`ci-miniapp` → `ci-app`、构建命令
- [x] `QUICKSTART.md`、`contracts/README.md`、`tools/mock/README.md`
- [x] `docs/产品使用说明.md`：使用说明口径改 APK（安装 / 升级步骤留给 P4）
- [x] `archive/` 保持冻结，只在 00 标注为「历史」
- [x] 顺带覆盖：`medbox-spec/openapi` 描述、`diagrams/*.html`、`docs/单片机与摄像头联调对接.md`、`medbox-admin/README.md`

验收：

- `grep -ri "小程序" medbox-spec docs README.md AGENTS.md` 只命中 `archive/` 与本文档（第 1、2 节描述迁移前状态）。
- 文档中的命令、路径、分支名可直接照抄执行。

### P1 · `feat/app-base`（App 骨架）

目标：能编出 APK 资源、H5 可登录、CI 改口。

- [x] 目录重命名 `medbox-miniapp/` → `medbox-app/`
- [x] 同步 CI 路径、契约命令、文档路径
- [x] `package.json` 脚本收敛：`dev:h5` / `build:h5` / `dev:app-plus` / `build:app-plus`
- [x] 删除 `mp-*` 脚本与 `@dcloudio/uni-mp-*` 依赖
- [x] `manifest.json`：删 `mp-weixin`；补 Android 权限（含 Android 13 通知权限）、明文 HTTP、通知权限
- [x] 删除 `ai.js` 的 `mpTransport` 与相关单测
- [x] 去掉 `report.vue` 里 `getFileSystemManager` / `shareFileMessage` 的 mp 分支（下载错误体解析未改动，仍按 HTTP 状态映射）
- [x] `.github/workflows/ci-miniapp.yml` → `ci-app.yml`，构建改 `build:h5` + `build:app-plus`
- [x] 同步文档中已变更的路径与命令

验收：

- `npm test`、`npm run test:e2e`、`npm run test:contract:mock` 全绿。
- `npm run build:h5`、`npm run build:app-plus` 成功。
- H5 登录、列表、详情可用。

### P2 · `feat/app-runtime`（平台能力）

目标：真机上 AI、导出、告警可用。

- [x] AI 流式：App 用 `plus.net.XMLHttpRequest`，H5 保留 `fetch`；提供非流式降级
- [x] 文件导出：App 复制到公共下载目录；H5 用 `<a download>`；无 `shareFileMessage`
- [x] 告警：前台实时；`onShow` 回前台补拉未读与最新记录（补拉不重复弹窗）
- [x] 前后台切换时 WS 重连（`onShow` 幂等补连，`onHide` 不主动断）
- [x] 补 transport 选择、导出、回前台补拉的单测

验收：

- 真机走查 AI 流式、CSV 导出、告警提示。
- H5 回归无退化。
- 单测、E2E、契约三份报告齐全。

### P3 · `feat/backend-app-support`（后端，按需）

目标：支撑 APK 分发与升级。无需求可跳过。

- [x] `GET /app/version`：versionCode 比对 + 下载地址（已合入 main，commit `1701a06`）
- [x] APK 静态下载目录或对象存储（`medbox.app.download-dir` 映射 `/app/**`；对象存储填 `download-url`）
- [x] H5 独立部署时才加 dev-only CORS（默认不做）
- [x] 同步 `02j` / `06` / `08`，补集成测试

验收：

- 接口文档与 OpenAPI 一致。
- 集成测试报告通过。

### P4 · `feat/app-release`（发版）

目标：可安装、可升级。

- [x] 应用图标、启动图、隐私弹窗（`src/static/app/*`、`src/static/privacy.html`、`src/androidPrivacy.json`）
- [x] 应用内检查更新：启动静默检查 + 「我的 → 关于」手动检查（消费 `GET /app/version`，H5 不启用）
- [x] 签名证书管理（不入库）：根 `.gitignore` 忽略证书，README 写明云端 / 本地证书流程
- [x] `versionCode` 规则（当前 100，按发版递增）与包名固定 `com.medbox.app`
- [x] `docs/产品使用说明.md` 补安装与升级步骤
- [x] HBuilderX 云打包流程固化（`medbox-app/README.md`「APK 发版」）
- [x] 发版配置自检单测（图标 / 启动图 / 隐私 / versionCode / 包名）

验收：

- 真机安装成功。（待人工验收）
- 覆盖安装升级成功。（待人工验收）
- 局域网连后端成功（明文 HTTP 场景）。（待人工验收）

---

## 6. 测试与 CI 口径

| 层 | 命令 | 迁移后变化 |
|---|---|---|
| L1 单元 | `npm test` | 改 transport、导出相关用例 |
| L2 Mock E2E | `npm run test:e2e` | 不变（纯 HTTP） |
| L3 契约 | `npm run test:contract:mock` | 不变（纯 HTTP） |
| 构建 | `npm run build:h5`、`npm run build:app-plus` | 替换 `build:mp-weixin` |
| APK 出包 | HBuilderX 人工 | 不进 CI |

说明：

- 契约场景与 Mock 后端都走 HTTP，不受端形态影响。
- CI 只能验证 App 资源编译通过，无法产出 APK。
- 每个功能分支交付时贴三份报告：unit、e2e、contract。

---

## 7. 风险与规避

| 风险 | 影响 | 规避 |
|---|---|---|
| APK 只能人工出包 | CI 无法交付安装包 | 固化 HBuilderX 流程，产物人工验收 |
| Android 9+ 禁明文 HTTP | 装上后连不上后端 | 云打包默认放行；后续若收紧 targetSdk 需补 networkSecurityConfig |
| 后台进程被系统挂起 | WS 断连，告警收不到 | 已做回前台补拉（P2）；本地通知仍待评估 |
| App 不支持 `enableChunked` | AI 流式失效 | 已换 `plus.net.XMLHttpRequest`，并带非流式降级（P2） |
| 微信专属 API 残留 | 真机报错 | P1 已全量清理，单测兜底 |
| H5 能力缺失 | 测试结论失真 | 真机能力只在 APK 上验收 |

---

## 8. 执行约定

- 一次只推进一个阶段。阶段合并 `main` 后再开下一阶段。
- 开工前：`git switch main && git pull && git switch -`。
- 一个分支只动一个端。
- 代码分支合并前，文档改动必须已合入。
- 每阶段结束贴测试报告，不口头说「已实现」。

---

## 9. 进度记录

| 阶段 | 分支 | 状态 | 合并 commit | 备注 |
|---|---|---|---|---|
| P0 | `docs/app-migration` | 已完成并合入 main（已推送） | 68e4f8c | 纯文档；目录重命名与代码改动在 P1 |
| P1 | `feat/app-base` | 已完成并合入 main（已推送） | 1f0882a | 目录迁移与小程序耦合清理 |
| P2 | `feat/app-runtime` | 已完成并合入 main（已推送） | 6cdb374 | AI 流式 / 导出 / 回前台补拉 |
| P3 | `feat/backend-app-support` | 已完成并合入 main（已推送） | 1701a06 | 版本检查 / APK 静态分发 |
| P4 | `feat/app-release` | 已完成并合入 main（已推送） | dff5059 | 图标 / 启动图 / 隐私弹窗 / 检查更新 / 证书与发版流程；真机验收待做 |
