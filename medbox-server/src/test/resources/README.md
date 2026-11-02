# 接口调试指南（REST / WebSocket / MQTT）

> 本目录及子目录下的文件均为**开发 / 联调期调试资产**，**不进入生产包**（位于 `src/test/resources`）。
> 协议契约以 `medbox-spec/02`（REST）、`medbox-spec/04`（WebSocket）、`medbox-spec/03`（MQTT）为权威口径，本文档只讲"怎么手动调"。

## 0. 总览

| 协议 | 首选调试工具 | 说明 |
|------|--------------|------|
| REST / HTTP | VS Code **REST Client** + `http/` 下的 `.http` 文件 | 点 Send 即发，JWT 自动注入 |
| WebSocket | `wscat` / node `ws` 脚本 / 浏览器 Console | 自定义 JSON 帧（ping/pong + eventId） |
| MQTT | **EMQX Dashboard（内置 WebSocket 客户端）** | 本期 Broker 匿名、局域网，Dashboard 首选；备选 MQTTX / mosquitto / mqtt.js |

---

## 1. REST / HTTP

调试文件在 `http/` 子目录：

- `http/auth.http` — 登录/注册/刷新/当前用户/监护关系（含 `# @name login` 供后续自动注入 token）
- `http/plan.http` — 服药计划 CRUD + 时间线 + 启停
- `http/record.http` — 服药记录 / 依从性 / 确认补录 / CSV 导出
- `http/medicine.http` — 药品档案 + 药箱库存

**步骤**：

1. VS Code 安装插件 **REST Client**（或 IntelliJ 内置 HTTP Client）；
2. 改每个文件顶部的 `@baseUrl`（局域网设备填内网 IP，默认 `http://localhost:8080/medbox/api/v1`）；
3. 先点 `auth.http` 的「登录」Send，拿到 JWT；其余【鉴权】请求通过 `{{login.response.body.$.data.token}}` **自动带 token**，无需手粘；
4. 每个请求上方 `###` 分隔，点行内 Send 即发，响应格式化展示。

**要点**：

- 统一响应为 `R<T>`：`{ code, message, data, traceId, timestamp }`；`timestamp` 为 **epoch 秒**；
- 写方法自动带幂等头 `X-Request-Id`（见 `02_REST接口设计.md` §1）；
- 字段 / 错误码 / 分页口径以 `medbox-spec/02_*` 为准。

---

## 2. WebSocket 实时推送

**端点**：`ws://{后端内网IP}:8080/medbox/ws?token={JWT}&topics=elder:e-1001`

- 鉴权在**握手**阶段（`token` query），越权主题直接拒绝整个握手（返回 `40001/40101/40301` + 统一响应体）；
- `topics` 仅支持 `elder:{elderId}`（多个逗号分隔）；`device:{deviceId}` 本期不支持，传了返回 `40001`；
- **心跳**：客户端每 **30s** 发 `{"type":"ping"}`，服务端回 `PONG`；服务端 **90s** 无任何客户端帧即关连接；
- **重连**：断线指数退避重连，重连时把 `&lastEventId=evt-xxxx` 放握手 query 上，服务端建连即补推增量（窗口外回 `RESYNC`，客户端改走 REST 拉全量）；
- 帧 `ts` 为 **epoch 毫秒**（与 REST `R.timestamp` 的**秒**不同）。

**消息帧结构**（详见 `medbox-spec/04` §2）：

```json
{ "type":"ALARM|DEVICE_STATUS|REMINDER|AI_INSIGHT|PONG|RESYNC",
  "id":"evt-9a1c", "topic":"elder:e-1001", "ts":1759474520000, "payload":{ } }
```

**工具选项**：

- **wscat**（推荐，零代码）：
  ```bash
  npm i -g wscat
  wscat -c "ws://localhost:8080/medbox/ws?token=<JWT>&topics=elder:e-1001"
  # 连上后发送心跳：
  {"type":"ping"}
  ```
- **node `ws` 脚本**（最贴合，可完整模拟 ping/pong + lastEventId 补推）：
  ```js
  const WebSocket = require('ws');
  const ws = new WebSocket('ws://localhost:8080/medbox/ws?token=<JWT>&topics=elder:e-1001');
  ws.on('open', () => {
    setInterval(() => ws.send(JSON.stringify({ type: 'ping' })), 30000);
  });
  ws.on('message', (m) => console.log('recv:', m.toString()));
  ```
- **浏览器 Console**：`const ws = new WebSocket('ws://localhost:8080/medbox/ws?token=<JWT>&topics=elder:e-1001'); ws.onmessage = e => console.log(e.data);`

---

## 3. MQTT（设备 ⇄ 云端）

> Broker = **EMQX**，局域网内网部署；本期**匿名**连接；后端与 EMQX 同机时连 `localhost:1883`。
> Topic 前缀统一 `medbox/{productKey}/{deviceId}/…`，区分上行 `up`（设备发）与下行 `down`（云端发），详见 `medbox-spec/03`。

### 3.1 首选：EMQX Dashboard（内置 WebSocket 客户端）

EMQX 自带 Dashboard（默认 `http://{内网IP}:18083`），内含 **WebSocket 客户端**，无需安装任何额外软件即可发布 / 订阅 MQTT 消息，是本期调试 MQTT 的首选方式。

**步骤**：

1. 浏览器打开 `http://{内网IP}:18083`（默认账号 `admin` / 密码按实际部署；局域网匿名连接不影响登录 Dashboard）；
2. 进入左侧 **工具箱 / Toolkit → WebSocket 客户端**；
3. **连接**：填写 MQTT over WebSocket 地址 `ws://{内网IP}:8083/mqtt`（EMQX 默认 WS 监听端口 8083），可填 `Client ID = {deviceId}`（与设备一致便于对照），点「连接」；
4. **订阅上行**（看设备上报 / 后端落库前的原始消息）：
   - Topic：`medbox/+/+/up/#`
   - QoS：`0` 或 `1`
5. **发布下行**（模拟云端给设备下发计划 / 命令）：
   - Topic：`medbox/{productKey}/{deviceId}/down/schedule`
   - QoS：`1`
   - Payload（示例，详见 `medbox-spec/03` §3 下行·同步计划）：
     ```json
     { "msgId":"d-001", "planId":"p-3301", "op":"UPSERT",
       "times":["08:00","20:00"], "repeat":"DAILY",
       "alarm":{"missAfterMin":15},
       "items":[ {"itemId":"pi-9001","medicineId":"m-205","dose":"1","unit":"片"},
                 {"itemId":"pi-9002","medicineId":"m-388","dose":"2","unit":"粒"} ],
       "needAck":true }
     ```
6. 也可订阅 `medbox/+/+/down/#` **反向观测后端实际下发了什么**（验证 REST 写操作是否触发正确的下行帧）；
7. Dashboard 的「连接 / 主题 / 订阅」页面还能实时看在线连接、Topic 监控与消息轨迹，便于排障。

> 上行消息**必须带 `msgId`**（格式 `{deviceId}-{10位递增序号}`，如 `BOXA-DEBUG-01-000123`），服务端据此幂等去重；下行帧带 `msgId` + `needAck:true`，设备经 `.../up/ack` 回执，超时 5s 重发、最多 3 次（见 `medbox-spec/03` §1）。

### 3.2 备选工具

- **MQTTX**（EMQ 官方桌面 / CLI 客户端）：连 `mqtt://{内网IP}:1883`，订阅 / 发布同上，图形化更好看；
- **mosquitto CLI**：
  ```bash
  # 订阅上行
  mosquitto_sub -h {内网IP} -p 1883 -t 'medbox/+/+/up/#' -v
  # 发布下行（模拟云端下发计划）
  mosquitto_pub -h {内网IP} -p 1883 -q 1 \
    -t 'medbox/{productKey}/{deviceId}/down/schedule' \
    -m '{"msgId":"d-001","planId":"p-3301","op":"UPSERT","times":["08:00"],"repeat":"DAILY","items":[{"itemId":"pi-9001","medicineId":"m-205","dose":"1","unit":"片"}],"needAck":true}'
  ```
- **mqtt.js 脚本**（可写断言、适合回归）：
  ```js
  const mqtt = require('mqtt');
  const c = mqtt.connect('mqtt://localhost:1883', { clientId: 'BOXA-DEBUG-01' });
  c.on('connect', () => {
    c.subscribe('medbox/+/+/up/#');
    c.publish('medbox/{productKey}/{deviceId}/down/schedule',
      JSON.stringify({ msgId:'d-001', planId:'p-3301', op:'UPSERT', times:['08:00'], repeat:'DAILY', items:[], needAck:true }),
      { qos: 1 });
  });
  c.on('message', (t, p) => console.log(t, p.toString()));
  ```

---

## 4. 调试资产清单

| 路径 | 协议 | 用途 |
|------|------|------|
| `http/auth.http` | REST | 登录拿 token + 用户 / 监护关系 |
| `http/plan.http` | REST | 服药计划 CRUD / 时间线 / 启停 |
| `http/record.http` | REST | 服药记录 / 依从性 / 确认补录 / 导出 |
| `http/medicine.http` | REST | 药品档案 + 药箱库存 |
| 本文档 | WS / MQTT | WebSocket 与 MQTT 手动调试方法（MQTT 首选 Dashboard） |
