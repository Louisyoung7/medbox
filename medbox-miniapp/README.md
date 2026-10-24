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
| `.env` | ✅ 入库 | 与机器**且与构建模式**无关的默认配置（`VITE_TLS`） |
| `.env.development` | ✅ 入库 | development 模式专属（当前为空，保留入口） |
| `.env.local` | ❌ 不入库 | 各人自己的后端地址（`VITE_SERVER_HOST`） |
| 本地缓存 `serverHost` | — | 真机调试时临时覆盖，**下次启动生效**，无需重新编译 |

首次搭建：

```bash
cp .env.example .env.local
# 编辑 .env.local：开发者工具 / H5 填 127.0.0.1，真机调试填电脑的局域网 IP
```

> **为什么 `VITE_TLS` 放在 `.env` 而不是 `.env.development`**：Vite 只加载当前 mode 的 `.env.[mode]`，
> `npm run dev:*` 是 development、`uni build -p *` 是 production —— 写在 `.env.development` 的开关
> **打包后读不到**，"上线切 https / wss"会静默失效。`.env` 对所有 mode 都是基线。
> Vite 优先级为 `.env.[mode].local` > `.env.[mode]` > `.env.local` > `.env`，所以个人要临时改 TLS，
> 写不入库的 `.env.local` 即可覆盖；因机器而异的 `VITE_SERVER_HOST` 则**只**写 `.env.local`，`git pull` 不会覆盖。

导出内容：

```js
import { BASE_URL, WS_URL, SERVER_HOST, DEFAULT_HOST, setServerHost, getServerHost, clearServerHost, normalizeHost } from '@/config/index.js'
// BASE_URL = http://{HOST}:8080/medbox/api/v1
// WS_URL   = ws://{HOST}:8080/medbox/ws
// 没有 MQTT_URL：小程序不直连 MQTT，设备消息由后端中转

setServerHost('http://192.168.1.23:8080/')  // 归一化后写入 192.168.1.23，返回 true
setServerHost('')                            // 非法输入：不写入，返回 false
getServerHost()                              // 读运行时覆盖（'' = 未覆盖，仍走默认）
clearServerHost()                            // 清除覆盖，回落默认
```

> `HOST` 取值链：本地缓存 `serverHost` → `VITE_SERVER_HOST` → **兜底 `127.0.0.1`**（后端跑在本机）。
> 端口固定 `8080`（与后端 `server.port` 一致），`{HOST}` 里不要再带端口。
> `serverHost` 可由**应用内「开发调试」页**写入（见下文，`feat/mp-devtools` 已落地），也可手动写入缓存，且**重启才生效**。

## 开发调试（`feat/mp-devtools`）

「我的 → 开发调试」（`pages/devtools/devtools.vue`）：真机调试时 IP 常变，在这里改一次即可，
**存本地缓存、重启小程序后生效**，不必重新编译。页面含三块：

| 区块 | 内容 |
|------|------|
| 服务器地址（HOST） | 展示当前地址与生效 URL；输入框可粘贴整串地址（保存时自动剥协议 / 端口 / 路径）；「恢复默认」清掉覆盖回落到 `.env.local` / `.env` |
| 局域网联调说明 | 微信开发者工具「不校验合法域名」勾选、真机调试（开发版）流程、"只需配一个 HOST" |
| 没有真设备？ | **可点**：「一键播种数据」调 `POST /_debug/seed`（回显账号 / 设备 / 双 token，点 token 复制，并自动回填设备 ID）；下方直接罗列 7 种事件预设，点哪行发哪条（调 `POST /_debug/mqtt/publish`） |
页面逻辑分层：HOST 读写薄封装在 `src/utils/devtools.js`，报文构造（事件预设 + msgId + 错误文案归一）在 `src/utils/devtools-sim.js`，
请求层在 `src/api/devtools.js`，底层 HOST 读写与归一化在 `src/config/index.js`。

```js
import { seedDebugData, publishDeviceEvent } from '@/api/devtools.js'
import { SIM_EVENT_PRESETS, buildSimPayload, devtoolsErrorMessage } from '@/utils/devtools-sim.js'

await seedDebugData()                                  // 播种，返回 { guardianToken, elderToken, deviceId, ... }
await publishDeviceEvent({                             // 服务端版 mosquitto_pub
  deviceId: 'BOXA1001',
  subTopic: 'event/dispense',
  payload: buildSimPayload('dispense-on-time', 'BOXA1001'),  // ⚠️ JSON 字符串
})
```

- 事件预设 7 种：服药·按时 / 服药·错服 / 服药·迟到 / 温湿度遥测 / 传感器心跳 / 设备异常 / 库存变更，报文与 `medbox-spec/03` 第 5 章示例同形态。
- **每行预设右侧有「落库 / 仅日志」标签，发送后有结果面板**（topic + msgId + 效果说明 + 「去记录页查看」）。目前只有 `event/dispense` 有专属处理器（`dispense-consume` 已落地，写 `med_record` + 扣库存）；`telemetry` / `sensor/heartbeat` / `event/error` / `inventory` 对应的后端分支（`env-core` / `device-sensor` / `device-fault` / `inventory-consume`）**尚未落地**，消息只被 `LoggingUpMessageHandler` 兜底记一行日志、**不改任何数据** —— 点之前就会标出来，别期待界面变化。
- **后端未开 `medbox.devtools.enabled` 时接口 404**，页面会用 `devtoolsErrorMessage` 提示"去后端开开关"，不会只说"服务端内部错误"。
- ⚠️ **服药类预设的两个后端约束**（`utils/devtools-sim.js` 已按此构造，页面也有说明）：
  1. `onTime` 由后端拿 `ts` **现算**（`!actualTime.isAfter(planTime + 容忍)`），payload 里的 `onTime` 被忽略 —— 所以 `ts` 按"设备本地墙钟"构造（UTC+8，与 devtools 种子的 `device.timezone` 一致），而不是 `Date.now()`，否则下午点"按时"会落一条 `onTime=false` 的记录；
  2. `med_record` 有 `(plan_item_id, plan_time)` 部分唯一索引（06 的 2.7 规则 6），**同计划项的同计划时间只落一条**。devtools 种子只有 1 个计划项 × 每日 08:00，**槽位是有限资源** —— 因此服药类每次点击都通过持久化计数器（`utils/devtools.js` 的 `takeNextSlotOffset`，缓存 key `simSlotSeq`，刷新小程序不归零）**换一个新的日历天**，不会"点一次就废"。结果面板回显本次落在哪个槽，页面也预告下一个槽。
- Mock 后端（`tools/mock`）也已实现这两个端点（免登录，与真后端 `WebMvcConfig` 口径一致），`npm run test:e2e` 覆盖 9 项检查；但 Mock 不接 EMQX，只做参数校验与回显，**不落库、不推 WS**。

```js
import { readServerHost, saveServerHost, resetServerHost } from '@/utils/devtools.js'

readServerHost()        // '' = 仍走编译期默认值；否则返回已覆盖的 HOST
saveServerHost('http://192.168.1.23:8080/medbox/')  // 归一化后写入，返回 true；非法输入返回 false 且不写缓存
resetServerHost()       // 清除运行时覆盖
```

> 调试页需登录后才可用（不在 `store/guard.js` 白名单内），不按角色限制。

## 浏览器联调（H5）

后端 REST **没有开 CORS**，而 H5 跑在 `:5173`、接口在 `:8080`，浏览器会因预检失败拦掉所有请求（`POST` + `application/json` 与带 `Authorization` 的 `GET` 都会触发预检）。解法是让 REST 走 **Vite dev server 反代**，`vite.config.js` 里已配好：

```js
server: { proxy: { '/medbox': { target: 'http://127.0.0.1:8080', changeOrigin: true, ws: true } } }
```

使用时在本机的 `.env.local` 加一行（**不入库**，`.env.example` 有注释模板）：

```bash
VITE_BASE_URL=/medbox/api/v1   # REST 拼成同源相对路径 → 由 dev server 反代
# VITE_SERVER_HOST=127.0.0.1   # 需要真机时可填局域网 IP；不填就是兜底 127.0.0.1
```

然后 `npm run dev:h5`，用普通浏览器打开 <http://localhost:5173> 即可，**不需要**带 `--disable-web-security`。

> **可以放心写在 `.env.local` 里，不会毒害小程序**（2026-10-20 起）：`VITE_BASE_URL` 是编译期常量，
> 以前小程序产物也会带着它，把 REST 请求到自己（微信 / App）的 origin 上 —— 表现为"登录不了 / 网络异常"，
> 与 IP 填错一模一样。现在 `src/config/index.js` 用 `process.env.UNI_PLATFORM === 'h5'` 做了平台隔离：
> **只有 H5 认这个变量**，小程序 / App 一律忽略（配了却不在 H5 时控制台会给出告警）。
> 所以一份 `.env.local` 可以同时喂 H5 与微信小程序，来回切不用改配置。
>
> `WS_URL` 永远走绝对地址：WebSocket 不受同源策略限制，没必要也不能走反代。

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

## 登录与鉴权（`feat/mp-auth`）

登录态一律走 `src/store/auth.js`（**编排层**），页面不要自己拼"写 token + 拉 `/users/me`"：

```js
import { login, logout, isLoggedIn, navigateAfterLogin } from '@/store/auth.js'
import { getRole, roleLabel, ROLE } from '@/store/user.js'

// 登录：写 token → 拉 GET /users/me → 存用户资料
await login({ account: '13800001234', password: '******' })
navigateAfterLogin(this.redirect)   // 回跳守卫带来的目标，否则回首页

// 判断登录态与角色（决定显示哪些入口，见 medbox-spec/01 第 3 章）
isLoggedIn()        // 是否有 access token
getRole()           // 'ELDER' / 'GUARDIAN'
roleLabel()         // '老人' / '监护人'

logout()            // 清 token + 用户资料 + reLaunch 登录页
```

| 模块 | 职责 |
|------|------|
| `store/token.js` | **token 的唯一读写入口**（access / refresh），请求层也从这里取 |
| `store/user.js` | 用户资料缓存（`userId` / `role` / `name` / `phone`）与角色常量 |
| `store/auth.js` | 编排：登录 / 注册 / 登出 / 续期 / 401 接管 / 启动校验 |
| `store/guard.js` | 路由守卫：白名单 + 未登录跳登录页并带 `redirect` 回跳 |

- **过期自动刷新（页面无需关心）**：业务请求拿到 `40101` → 请求层交处理器 → `POST /auth/refresh` → 成功则**用新 token 自动重发一次原请求**（调用方无感知），**并发只刷一次**；refresh 也失败才清凭据 + 跳登录页。
- **刷新接口的两个必须项**：`auth: false`（没有可用 token）与 `noAuthRetry: true`（否则 refresh 自己 40101 会递归刷新），已在 `api/auth.js` 里写死。
- **路由守卫**：`App.vue` 的 `onLaunch` → `setupAuth()` 里装 `uni.addInterceptor`，拦截 `navigateTo` / `redirectTo` / `reLaunch` / `switchTab`。**新建页面若允许未登录访问，必须加进 `store/guard.js` 的 `WHITE_LIST`**（当前只有登录页与注册页）。
- **回跳安全**：`redirect` 来自 URL 参数，`safeTarget()` 只接受 `pages/` 开头的站内路径，其余一律回首页。
- **注册角色只有 `ELDER` / `GUARDIAN`**：护理 / 医生是 `guardian_relation.role`，由老人在确认监护关系时指定（见 `medbox-spec/06` 的 2.1 / 2.2），注册页不给这两个选项。
- **当前首页的登录态卡片与"退出登录"是临时入口**，后续由 `feat/mp-ui-kit` / `feat/mp-profile` 接管。

## 微信开发者工具设置

- **详情 → 本地设置**：勾选「不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书」，否则局域网的 `http` / `ws` 请求会被工具拦截。
- 该勾选保存在 `project.private.config.json`，属个人配置，**不要提交**。
- **真机预览**默认强制校验域名：开发阶段用「真机调试（开发版）」模式，或改走 App 端（Android / iOS）直连局域网 IP。
- `src/manifest.json` 的 `mp-weixin.appid` **两人共用同一个**（微信公众平台「成员管理」里把对方加为开发者 / 体验成员），不要各填各的测试号。

## Mock 后端与端到端测试（`tools/mock`）

后端接口未落地期间的前端自测底座（**不替代 `medbox-server/`**，纯 Node 内置模块、零依赖）：

```bash
npm run mock        # 起假后端，监听 8080（BASE_URL 端口固定，勿改；端口被占会直接报是谁占了）
npm run test:e2e    # 端到端冒烟：自起服务（18099 端口）、65 项检查、跑完即关
npm run test:all    # 单元测试 + 端到端一次跑完（AI 完工时的标准动作，见 AGENTS.md 3.1）
```

- 演示账号（密码统一 `123456`）：`13800001234` 监护人张伟（全读写）/ `13900000001` 老人张桂英（只读）/ `13800001235` 王芳（PENDING→40302）等，完整清单与场景见 `tools/mock/README.md`。
- 接口形状、错误码、权限口径全部对照 `medbox-spec/02`；字段名假设（stocks 裸数组、`{list,total}`）后端定稿后两处一起收紧。
- 重置数据：`curl -X POST http://127.0.0.1:8080/medbox/api/v1/__mock__/reset`。
- ⚠️ 无 WebSocket：WS 状态条显示未连接属预期；**微信侧注意 WXSS 不支持通配符 `*`**（H5 测不出，小程序编译才报错）。

## 目录结构

```
medbox-miniapp/
├── src/
│   ├── pages/        # 页面（含 devtools 开发调试页）
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
