# medbox-admin · 家用智能药品箱（Web 管理后台）

Vue 3 + Vite + Element Plus 的 **「测试数据清理」dev/QA 工具**，对应任务清单 `medbox-spec/09_管理后台设计_medbox-admin.md`（首个分支 `feat/admin-testdata-cleanup`）。
真实产品后台（用户管理 / 设备管理 / 统计看板等）仍待设计，本目录**本期只做测试数据清理**，接入超出此范围的需求前请先在 `02`/`06`/`09` 补接口与清单。

## 运行

```bash
npm install
npm run dev      # Vite 开发服务器，默认 http://localhost:5173，/medbox 反代到后端 8080
npm run build    # 产出静态文件到 dist/，可用 `npx serve dist` 起服务
npm run preview  # 预览上一次 build 的产物
npm test         # Vitest 单测（api 层 + 护栏纯函数），CI 同跑
```

> **默认管理员账号**（后端种子，见 `medbox-spec/06` 的 2.1 / `feat/backend-admin-cleanup`）：
> `username=admin`、`password=public`、`role=ADMIN`，无首次强制改密。
> 登录走 `POST /auth/login`，成功后 JWT 与 `role` 存入 `localStorage`。

## 后端地址配置

编译期默认值来自 `.env`，业务代码一律从 `src/config/index.js` 取 `BASE_URL`，**禁止在页面里写死 IP**。

| 文件 | 是否入库 | 内容 |
|------|----------|------|
| `.env` | ✅ 入库 | 与机器无关的默认配置（`VITE_SERVER_HOST` / `VITE_TLS` / `VITE_BASE_URL`） |
| `.env.local` | ❌ 不入库 | 各人自己的后端地址（覆盖 `VITE_SERVER_HOST` 等） |

```bash
# .env 当前默认（开发期：相对路径 + Vite proxy，规避浏览器 CORS）
VITE_SERVER_HOST=127.0.0.1
VITE_TLS=false
VITE_BASE_URL=/medbox/api/v1
```

- `HOST` 取值链：`VITE_BASE_URL`（显式绝对地址时直接用）→ 否则 `http(s)://{VITE_SERVER_HOST}:8080/medbox/api/v1`。
- 端口固定 `8080`（与后端 `server.port` 一致），`{HOST}` 里不要再带端口。
- **开发联调**用 Vite dev proxy（`vite.config.js`）把 `/medbox` 转发到后端 8080，浏览器不会触发 CORS 预检；`VITE_BASE_URL` 写 `/medbox/api/v1` 即走同源相对路径。
- **生产 / 测试直连**时把 `VITE_BASE_URL` 改成绝对地址（如 `http://<host>:8080/medbox/api/v1`），由后端 CORS 兜底（同 `medbox-spec/01` 第 4 章）。

## 接口与鉴权

所有 REST 调用一律走 `src/api/request.js`（解包统一响应 `R<T>`、集中错误码提示），**不要直接调 axios**。

| 能力 | 接口 | 说明 |
|------|------|------|
| 登录 | `POST /auth/login` | `{ account, password }` → `{ token, refreshToken, userId, role, expiresIn }`；失败统一 `40102` |
| 列出用户 | `GET /admin/users` | Query：`status`(ACTIVE/DELETED，缺省全部)、`keyword`(LIKE phone/username)、`role`、`page`(1)、`size`(20，上限100)；返回 `R<PageResult<AdminUserRow>>` |
| 清理用户 | `DELETE /admin/users/{userId}` | 硬删该用户 + 应用层级联清孤儿数据；`status==ACTIVE` 被后端拦下返回 `40902`；`40401`=不存在；非 ADMIN `40301` |

- 行模型 `AdminUserRow = { userId, role, name, phone, username, status, createdAt }`；软删账号(DELETED)的 `phone`/`username` 为 `null`，挑选用 `userId`+`status`+`createdAt`。
- **鉴权**：管理接口需 `Authorization: Bearer <token>` 且 `role==ADMIN`，否则 `40301`。`src/router/index.js` 的 `beforeEach` 做 ADMIN 路由守卫：未登录 → `/login`，非 ADMIN → 拦截并提示后回 `/login`。
- **错误码分流**：`40102` 清凭据跳登录；`40301` 提示无权限；`40401` 提示目标不存在；`40902` 提示「活跃账号不可清理」；其余按 `medbox-spec/02` 的 1.2 兜底。

## 前端安全护栏（不替代后端）

`/test-data` 页（`src/views/TestData.vue` + `src/utils/guard.js`）有多重护栏，且**后端仍有独立护栏**（`DELETE` 对 ACTIVE 返回 `40902`），前端漏拦后端兜底：

1. **默认视图收敛**：只展示 `status=DELETED` 或 `username LIKE 'test%'` 或 `phone LIKE '1380000%'`；看 ACTIVE 需显式切换筛选项；
2. **二次确认 + 手输目标 userId**：弹框要求手输待清理 `userId`、且需与所选行一致才放行；
3. **顶部固定横幅**：「测试数据清理 · 开发/测试专用，勿接生产库」；
4. **审计（可选）**：清理成功后在本页展示一条「操作人 / 目标 userId / 时间」，不新增后端接口。

验收口径（与 `09` 一致）：管理员登录进 `/test-data`；列表默认只显测试/已注销账号；点清理弹二次确认、手输 userId 才放行；ACTIVE 行清理按钮禁用并提示「活跃账号不可清理」；批量清理按当前筛选生效；`40902` 有明确提示。

## 目录结构

```
medbox-admin/
├── src/
│   ├── views/        # 页面：Login.vue / TestData.vue
│   ├── api/          # 接口封装：request.js(解包R<T>) / auth.js(登录) / admin.js(用户列表+清理)
│   ├── config/       # 配置出口：BASE_URL
│   ├── stores/       # Pinia 状态：token.js(登录态唯一读写入口)
│   ├── router/       # 路由 + ADMIN 守卫
│   ├── utils/        # 护栏纯函数（默认视图过滤 / 是否可清理 / 二次确认匹配）
│   ├── App.vue
│   └── main.js
├── tests/            # Vitest 用例（api 层 + 护栏纯函数）
├── .env              # 入库：默认后端地址
├── .env.local        # 不入库：个人地址覆盖
├── vite.config.js    # dev proxy：/medbox → 127.0.0.1:8080
├── vitest.config.js
└── package.json
```

> `.gitignore` 由**仓库根**统一维护（含 `node_modules/`、`dist/`、`.env.local`），本目录不再单独放。

## 持续集成

`.github/workflows/ci-admin.yml`：路径过滤（仅 `medbox-admin/**` 或本文件变更触发），`setup-node` 锁 Node 24、`npm ci`、先 `npm test` 再 `npm run build`，产物上传 `admin-dist`。改后端/小程序不会触发本流。
