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
