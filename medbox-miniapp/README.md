# medbox-miniapp · 家用智能药品箱（uni-app 小程序）

Vue 3 + Vite 版 uni-app 工程，对应任务清单 `medbox-spec/08_特性分支清单_小程序.md`。

## 运行

```bash
npm install
npm run dev:mp-weixin   # 微信小程序，产物在 dist/dev/mp-weixin
npm run dev:h5          # H5，浏览器打开 http://localhost:5173
```

用**微信开发者工具**打开 `dist/dev/mp-weixin` 目录预览。

## 后端地址配置

编译期默认值来自环境变量，**运行时可用本地缓存覆盖**；业务代码一律从 `src/config/index.js` 取地址，禁止在页面里写死 IP。

| 文件 | 是否入库 | 内容 |
|------|----------|------|
| `.env.development` | ✅ 入库 | 与机器无关的配置（`VITE_TLS`） |
| `.env.local` | ❌ 不入库 | 各人自己的后端 IP（`VITE_SERVER_HOST`） |
| 本地缓存 `serverHost` | — | 真机调试时临时覆盖，**下次启动生效**，无需重新编译 |

首次搭建：

```bash
cp .env.example .env.local
# 编辑 .env.local，把 VITE_SERVER_HOST 改成自己电脑在局域网内的 IP
```

> Vite 中 `.env.[mode]` 优先级高于 `.env.local`，所以 **`VITE_TLS` 只改 `.env.development`**，不要写进 `.env.local`。
> `.env.local` 不入库，因此 `git pull` 不会覆盖自己的 IP，也不用每次改回来（见 `medbox-spec/01` 的 4.5）。

导出内容：

```js
import { BASE_URL, WS_URL, SERVER_HOST, setServerHost } from '@/config/index.js'
// BASE_URL = http://{HOST}:8080/medbox/api/v1
// WS_URL   = ws://{HOST}:8080/medbox/ws
// 没有 MQTT_URL：小程序不直连 MQTT，设备消息由后端中转
```

## 请求封装用法（`feat/mp-request`）

所有 REST 调用一律走 `@/api/request.js`，**不要直接调 `uni.request`**：它负责拼 `BASE_URL`、注入 `Authorization: Bearer` 与 `X-Request-Id`（仅写操作）、按统一响应拆包、按错误码提示。

```js
import { get, post, CODE } from '@/api/request.js'
import { getMe } from '@/api/auth.js'

// 成功直接拿到 data（不是整个响应体）
const me = await get('/users/me')
const list = await get('/devices', { page: 1, size: 20 }, { loading: true })

// 失败拿 ApiError{ code, message, traceId, httpStatus }，按 code 细分
try {
  await post('/devices/BOXA1001/commands', { cmd: 'BUZZ' })
} catch (err) {
  if (err.code === CODE.CONFLICT) {
    uni.showToast({ title: '设备离线，无法下发', icon: 'none' }) // 自己提示就传 silent: true
  }
}
```

| 选项 | 默认 | 说明 |
|------|------|------|
| `auth` | `true` | 注入 `Authorization: Bearer`；登录 / 注册 / 刷新传 `false` |
| `loading` | `false` | `true` 或自定义文案（如 `'提交中'`）显示遮罩；并发请求内部计数，不重复弹 |
| `silent` | `false` | `true` 时不自动 toast，由页面自绘错误（配合上例） |
| `timeout` | `10000` | 毫秒 |
| `params` | — | query 参数（`null` / 空串自动丢弃） |
| `requestId` | 自动生成 | `X-Request-Id`，32 位十六进制，仅写操作注入 |

- **错误码分流**：`40101` 清 token 并 `reLaunch` 到 `LOGIN_PATH`（`/pages/auth/login`，页面由 `feat/mp-auth` 建）；`40301` 提示无权限、`40302` 提示监护关系未生效；其余按 02 的 1.2 给出文案；未知码按 `50000` 兜底。
- **接管 40101**：`setUnauthorizedHandler(fn)` 注入后默认跳转失效，`feat/mp-auth` 用它先 refresh 再决定跳不跳登录，无需改 `request.js`。
- **网络失败**（没到服务端）不是业务码，用 `err.isNetworkError()`（`code === -1`）判断，提示"请检查是否在同一局域网"。
- **Token 存储**：`src/store/token.js`（`getAccessToken` / `setTokens` / `clearTokens`），`feat/mp-auth` 在此文件内扩展刷新与用户信息，**不要另建平行模块**。

## 微信开发者工具设置

- **详情 → 本地设置**：勾选「不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书」，否则局域网的 `http` / `ws` 请求会被工具拦截。
- 该勾选保存在 `project.private.config.json`，属个人配置，**不要提交**。
- **真机预览**默认强制校验域名：开发阶段用「真机调试（开发版）」模式，或改走 App 端（Android / iOS）直连局域网 IP。
- `src/manifest.json` 的 `mp-weixin.appid` **两人共用同一个**（微信公众平台「成员管理」里把对方加为开发者 / 体验成员），不要各填各的测试号。

## 目录结构

```
medbox-miniapp/
├── src/
│   ├── pages/        # 页面
│   ├── components/   # 复用组件（mp-ui-kit）
│   ├── api/          # 接口封装（mp-request）
│   ├── store/        # 全局状态（mp-auth）
│   ├── config/       # 配置出口：BASE_URL / WS_URL
│   ├── utils/        # 工具函数（时间格式化等）
│   ├── static/       # 静态资源
│   ├── pages.json    # 页面路由与导航栏
│   ├── manifest.json # 应用配置（含 AppID）
│   └── uni.scss      # uni-app 内置样式变量
├── .env.development  # 入库
├── .env.example      # .env.local 模板
└── vite.config.js
```

> `.gitignore` 由**仓库根**统一维护（含 `node_modules/`、`dist/`、`unpackage/`、`.env.local`），本目录不再单独放。
