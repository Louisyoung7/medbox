# 09 · 特性分支清单（Web 管理后台 medbox-admin）

> 每个 `- [ ]` 待办 **对应一个特性分支**，分支名见每条的 `feat/xxx`。
> **修 Bug、重构项目结构不计入本清单**。
> 图例：🧱 = **地基**；🔧 = **功能任务**；🧭 = **判定型**（含业务规则与优先级，**动手前先出规则表给用户确认**，见 `AGENTS.md` 的 3.1）。
> **认领与引用一律用分支名**（如"对应 09 的 `feat/admin-testdata-cleanup`"），**不要用"第 N 项"** —— 清单会重排，序号不稳定。
> 完成后把待办勾成 `- [x]`，PR 描述写明"对应 09 的 `feat/xxx`"。

> **本期范围**：`medbox-admin/` 原在 00 索引里是「本期占位，暂不实现」。现决定**提前只做一件开发/测试侧的刚需**——**测试数据清理**（dev/QA 工具）：双人本地独立开发常注册一堆测试账号（监护人 / 老人），用完易忘删；用 DataGrip 手工删又怕漏表、触发外键 / 孤儿行。**需要一个带护栏的界面，安全列出并清除测试用户及其孤儿数据。**
> **边界**：它是 **dev/QA 专用工具**，**不进入普通产品导航**，UI 明确标注「测试数据清理 · 开发/测试专用」；建议在**开发 / 测试库**使用、**不接生产库**（生产库不该有 `test*` 账号，且硬删会丢健康留痕）；硬删有**多重护栏**，ACTIVE 真账号一律拒绝。真实产品后台（用户管理 / 设备管理 / 统计看板等）仍待设计，后续分支在此追加。

> **接口契约不在本清单维护**：ADMIN 角色门禁、`GET /admin/users`（列出 + 筛选）、`DELETE /admin/users/{userId}`（硬删 + 级联清理、`ACTIVE` 拒绝 `40902`）、角色建模与种子账号，统一收编在 **`02` 第 10 章《管理员接口（测试数据清理）》** 与 **`06` 的 2.1（`V11__admin_seed.sql`）**。前端按 `02` 第 10 章的字段与错误码调用即可，本文档不再重复，避免漂移。

> **路径与分支**：本清单所有代码**都在 `medbox-admin/` 下**；分支名 `feat/admin-xxx`，**一个分支只改 `medbox-admin/`**。文档改动（`medbox-spec/`）与代码放进**同一个 PR** 即可，**不要求同一个 commit**。后端接口依赖写「后端 `feat/backend-admin-cleanup`（见 `07` 第三章，已单项登记）」，**不在此重复登记后端分支**——`07` 是唯一事实源，09 仅以依赖列引用。

---

## 一、本期分支（dev/QA 工具）

- [x] 🔧 **`feat/admin-testdata-cleanup`** 测试数据清理工具
  - 依赖：本端 无；**后端 `feat/backend-admin-cleanup`（见 `07` 第三章）** —— ADMIN 角色门禁 + `GET /admin/users`（列出+筛选）+ `DELETE /admin/users/{userId}`（硬删+级联清理，`ACTIVE` 拒绝 `40902`）+ 种子管理员账号
  - 参考：02 第 10 章、06 的 2.1（V11）、原设计稿《已确认事项》
  - 内容：
    - **技术栈（已确认：默认 Vue 3 + Vite + Element Plus）**：与 `medbox-miniapp/` 同为 Vue3 系，降低上下文切换；Element Plus 含表格 / 表单 / 弹窗 / 二次确认组件，最贴合后台。备选 React + Ant Design 仍可行（用户无偏好），但本期按默认落地，不另起 React 工程
    - **登录与角色**：复用 `POST /auth/login`（账号可为手机号或用户名），admin 种子账号（`username=admin`、`password=public`、`status=ACTIVE`、`role=ADMIN`）登录后拿到普通 JWT，管理接口额外要求 `role == ADMIN`（后端门禁见 `02` 第 10 章）；路由守卫拦截未登录或非 ADMIN
    - **页面**：
      - `/login`：管理员登录（手机号/用户名 + 密码），成功存 JWT 与 `role` 到 `localStorage`
      - `/test-data`（清理主页）：筛选条（`status` 下拉默认「测试/已注销」、`keyword`、`role`、`查询`/`重置`）；用户表格（`userId`/`role`/`name`/`phone`(可空)/`username`(可空)/`status`/`createdAt` + 每行「清理」按钮，ACTIVE 行禁用并提示「活跃账号不可清理」）；批量入口「清理全部已注销测试用户」（按当前筛选批量调 `DELETE /admin/users/{userId}`）；二次确认弹框要求手输 `userId` + 后果提示
    - **前端安全护栏**：① 默认视图收敛，只展示 `status=DELETED` 或 `username LIKE 'test%'` 或 `phone LIKE '1380000%'`，看 ACTIVE 需显式切换；② 二次确认 + 手输目标 `userId` 才放行；③ 页面顶部固定标注「测试数据清理 · 开发/测试专用，勿接生产库」；④ 审计（可选）写一条审计日志（操作人 admin、目标 userId、时间）
    - **接口调用**：`axios` 封装 `baseURL` 指向 `http(s)://<host>:8080/medbox/api/v1`，统一附 `Authorization: Bearer <token>`；复用 `02` 的 `R<T>` 与错误码（如 `40902` 弹「活跃账号不可清理」）；host/端口走 `.env`（参考 `medbox-miniapp/` 的 `src/config` 兜底），默认 `127.0.0.1:8080`
  - 验收：管理员登录进 `/test-data`；列表默认只显测试/已注销账号；点清理弹二次确认、手输 userId 才放行；ACTIVE 行清理按钮禁用；批量清理按筛选生效；`40902` 有明确提示
  - **已确认事项（实现约束）**：
    1. 前端栈默认 **Vue 3 + Vite + Element Plus**（React + Ant Design 亦可但用户无偏好，按默认落地，不另起 React 工程）
    2. ADMIN 落地采用 **方案 A** —— `UserRole` 增加 `ADMIN` + `V11__admin_seed.sql` 种子账号 + 独立角色守卫（后端建模见 `02` 第 10 章 / `06` 的 2.1）；不采用方案 B 白名单
    3. 本期**只做「测试数据清理」这一个工具**，`medbox-admin/` 不搭产品后台框架（路由壳 / 通用登录 / 看板等）
    4. 种子 admin 凭证：`username=admin`、`password=public`、`status=ACTIVE`、`role=ADMIN`；**不做首次强制改密**（后端落地见 `06` 的 V11，原写 V10 系笔误，V10 已被 dispense 占用，见 CHANGELOG [2026-10-24]）

---

## 二、后续分支（待设计，占位）

> 真实产品后台（用户管理、设备管理、统计看板等）尚未设计。待 `02` / `06` 补充对应接口与权限，并确认本期范围后，在 `09` 本表追加 `- [ ]` 分支（结构同本章）。本期不认领。
