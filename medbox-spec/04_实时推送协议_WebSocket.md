# 04 · 实时推送协议（WebSocket，后端 → 小程序）

> 用途：告警实时推送、设备在线状态、实时监测看板　|　局域网明文　|　版本见 `CHANGELOG.md`

## 1. 连接与鉴权

| 项 | 约定 |
|----|------|
| 端点 | `ws://{后端主机内网IP}:8080/medbox/ws?token={JWT}&topics=elder:e-1001`（局域网明文） |
| 鉴权 | 握手校验 JWT；按订阅主题鉴权，**越权主题直接拒绝整个握手** |
| 主题语法 | 仅支持 **`elder:{elderId}`**，多个用逗号分隔（`topics=elder:e-1001,elder:e-1002`）；**`device:{deviceId}` 本期不支持**（设备归属解析由 `feat/device-core` 提供，见 07），传了返回 40001 |
| 归属判定 | 复用 REST 同一口径（02 的 1.3）：订阅主题必须落在"我可见的老人"内（老人 = 自己；监护人 = 所有 ACTIVE 监护关系）。PENDING / REVOKED / 无关系一律按越权处理 |
| 握手失败 | 直接返回 HTTP 状态码 + **统一响应体**（`code/message/traceId/timestamp`）：`40001`→400、`40101`→401、`40301`→403。握手不走 MVC，客户端按 HTTP 状态与 `code` 判断即可 |
| 心跳 | 客户端每 **30s** 发 `{"type":"ping"}`，服务端回 `PONG`；服务端 **90s**（3 个周期）无任何客户端帧即关闭连接 |
| 重连 | 断线指数退避重连（初始 1s，上限 30s）；**重连时把 `lastEventId` 放在握手 query 上**（`&lastEventId=evt-9a1c`），服务端建连即补推增量 |
| 补推窗口 | 服务端**按主题**各保留最近 **200 条 / 最近 10 分钟**的事件；窗口内取不到"该给的下一跳"（被淘汰或已跨过）则回 **`RESYNC`**，客户端改用 REST 拉全量 |
| 连接期 token | 握手时校验一次；**连接存续期间不主动踢**（局域网场景，2 小时有效期），重连时自然重新鉴权 |
| 调试 | 用 `wscat -c "ws://{IP}:8080/medbox/ws?token=...&topics=elder:e-1001"` 验证；推送触发源见第 4 章的调试端点；MQTT 侧用 EMQX Dashboard（见文档 03 第 5 章） |

## 2. 消息帧结构

```json
{ "type":"ALARM|DEVICE_STATUS|REMINDER|AI_STREAM|PONG|RESYNC",
  "id":"evt-9a1c",           // evt- + 单调递增序号的十六进制，用于断线补推
  "topic":"elder:e-1001",
  "ts":1759474520000,        // epoch **毫秒**（与 REST 响应体 R.timestamp 的秒不同）
  "payload": { } }
```

> `PONG` 是控制帧：**不带 `topic` 与 `payload`**（一条连接可能订阅多个主题，无从归属）。
> `RESYNC` 是本分支新增：补推窗口过期时告诉客户端"别等补推了，改走 REST 拉全量"，
> 否则客户端分不清"没有新事件"与"事件已被淘汰"。

## 3. 推送事件类型

| type | 触发时机 | payload 要点 |
|------|----------|--------------|
| ALARM | 后端生成漏服/错服/过期/环境告警 | alarmId, type, level(`INFO`/`WARN`/`CRITICAL`), message, elderId, medicineId |
| DEVICE_STATUS | 设备在线/离线（订阅 retained status） | deviceId, deviceType(`MAIN`/`TEMP_HUMI`/`LIGHT`/`CAMERA`), online, parentDeviceId（子设备时） |
| REMINDER | 临近服药计划提醒（可选，监护端提醒） | planId, planTime, **`medicines[]`**（一次提醒可含多种药） |
| AI_STREAM | AI 流式回答分片（**本期不使用**：流式统一走 HTTP SSE，见文档 05） | sessionId, delta |
| RESYNC | 补推窗口已过期，客户端应改走 REST 拉全量 | `reason=WINDOW_EXPIRED`、`latestEventId` |

> **DEVICE_STATUS 覆盖子设备**：药箱主控（`MAIN`）离线/上线各推一条；**传感器子设备**没有自己的 MQTT 连接，其在线状态由主控通过 `up/sensor/heartbeat` 汇总上报（见文档 03 第 3 章），后端据此推送子设备的 `DEVICE_STATUS`（带 `deviceType` 与 `parentDeviceId`）。子设备离线**不生成 DEVICE_OFFLINE 告警**，仅在设备详情页标注。

**示例：推送漏服告警**
```json
{ "type":"ALARM","id":"evt-9a1c","topic":"elder:e-1001",
  "ts":1759474520000,
  "payload":{ "alarmId":"a-5001","type":"MISS","level":"WARN",
    "message":"08:00 计划服药未按时服用：缺 阿莫西林、维生素D",
    "medicineIds":["m-205","m-388"] } }
```

**示例：推送服药提醒（多种药）**
```json
{ "type":"REMINDER","id":"evt-9a1d","topic":"elder:e-1001",
  "ts":1759474500000,
  "payload":{ "planId":"p-3301","planTime":"2026-10-04T00:00:00Z",
    "medicines":[ { "itemId":"pi-9001","medicineId":"m-205","medicineName":"阿莫西林","dose":"1","unit":"片" },
                  { "itemId":"pi-9002","medicineId":"m-388","medicineName":"维生素D","dose":"2","unit":"粒" } ] } }
```

---

> **REMINDER 由 `feat/plan-reminder` 生成**：后端定时扫描（`@Scheduled`，每 `medbox.plan-reminder.scan-interval-ms` 默认 60s）取 `status='ENABLED' AND sync_state <> 'SYNC_FAILED'` 的计划，
> 按设备本地时区在 `[now, now + lead-time-minutes]`（默认 10min）窗口展开"未来"服药槽，经 `WsPushService` 唯一出口推 `REMINDER` 帧到 `elder:{elderId}`。
> **两台语义**：设备本地声光提醒为主、云端 WS 为监护端兜底（默认不要求设备在线）。**两道拦截**：① 该槽已有 `med_record`（已服）跳过；② 去重表 `plan_reminder`（`dedup_key = REMINDER:{planId}:{slotUTC}`）已存在则跳过（见 06 §2.6.x）。`REMINDER` 不是告警，不进 `alarm` 列表。

## 4. 后端落地（`feat/backend-ws`）

**推送出口只有一个**：`WsPushService.publish(topic, WsFrameType, payload)` → 返回 `(eventId, 送达连接数)`。
后续业务分支（`feat/miss-alarm`、`feat/plan-reminder`、`feat/env-alarm`、`feat/alarm-core`）一律只调它，
**不要自己持有 `WebSocketSession`** —— 与 MQTT 侧"下行出口只有 `DownlinkPublisher`"是同一条约定。

| 组件 | 职责 |
|------|------|
| `WsConfig` | `@EnableWebSocket` + `@EnableScheduling`；`medbox.ws.enabled=false` 整包不装配（与 `MqttConfig` 同套路） |
| `WsHandshakeInterceptor` | 解析 `token` → 解析 `topics` 语法 → `AccessService.visibleElderIds()` 归属判定；拒绝时写 HTTP 状态 + 统一响应体；`finally` 清 `AuthContext` |
| `MedboxWebSocketHandler` | 建连注册、按 `lastEventId` 补推（过期发 `RESYNC`）、`ping`→`PONG`、关闭 / 异常摘除连接 |
| `WsSessionManager` | `topic → sessionId` 倒排索引、按连接串行发送、发送失败即摘除、90s 空闲回收 |
| `WsEventBuffer` | **按主题**各留最近 200 条 / 10 分钟；记每主题已发最大序号，据此判定"窗口已过期" |
| `WsPushService` | 发号（`evt-` + 十六进制递增序号）→ 组帧 → 写缓冲 → 扇出 |
| `WsIdleSessionReaper` | 30s 扫描一次，关闭 90s 无消息的连接 |
| `WsDebugController` | `POST /api/v1/_debug/ws/publish`，**默认不装配**（见下） |

**几个取舍**：

- **用原生 WebSocket，不用 STOMP / 消息代理**：本协议的帧是自定义 JSON，含 `ping/pong` 与"按 `lastEventId`
  补增量"，STOMP 的 destination 订阅语义表达不了，还会白引入一层代理。
- **归属判定用 `visibleElderIds()` 而不是 `require(elderId, Permission)`**：握手阶段只有"订阅哪些老人"，
  没有"要做什么操作"，拿不到具体 `Permission`；用可见老人集合一次查询覆盖所有主题，也避免逐主题查库。
- **事件缓冲放内存**：骨架期单实例足够，代价是**重启 / 多实例即丢**。届时可换 Redis / 表，
  `WsPushService` 签名与补推语义不变，调用方零改动。
- **未注册 `ServletServerContainerFactoryBean`**：它在 `afterPropertiesSet` 就去取
  `jakarta.websocket.server.ServerContainer`，而 Boot 4 下该属性要等内嵌 Tomcat 起来才有，注册会让
  应用上下文启动失败。单帧大小沿用容器默认（Tomcat 文本帧 8KB），业务帧远小于此。

**调试推送端点（本地联调用）**：告警的业务源头（`feat/miss-alarm`）是 P0 第 10 步，骨架阶段没有触发源，
故留一个开关控制的入口 —— `medbox.ws.debug-endpoint-enabled=true` 时才装配：

```bash
# 1) 开端点（写在 application-local.yml，或启动时加参数）后起应用
# 2) wscat 订阅
wscat -c "ws://192.168.1.10:8080/medbox/ws?token=$TOKEN&topics=elder:e-1001"
# 3) 触发一帧
curl -X POST http://192.168.1.10:8080/medbox/api/v1/_debug/ws/publish \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"elderId":"e-1001","type":"ALARM"}'
```

端点落在 `/api/v1/**`（强制登录）并再经 `AccessService.require(elderId, READ_ALARM)` 校验归属，
越权照样 40301 —— 能推的范围不超出调用者本来就能看到的数据。**生产务必保持关闭。**

**`DEVICE_STATUS` 由 `feat/device-core` 接入（`feat/backend-ws` 时留白的一项）**：

- 触发点是 **MQTT 侧的在线状态跃变**（status / 遗嘱），不是 HTTP 接口 —— 见文档 03 第 1 章；
- 推到 **`elder:{elderId}`** 主题即可（归属老人从 `device.elder_id` 取），**不扩展 `device:` 主题** —— 当前没有消费方，`WsTopic` 保持只支持 `elder:`；
- **只在状态真的变了才推**：主控每 60s 有心跳，每次上行都推会把小程序刷爆；
- 设备**未登记**（局域网上任何 clientId 都能连 EMQX）时没有归属老人，无主题可推，静默跳过；
- `medbox.ws.enabled=false` 时 `ObjectProvider` 取不到 `WsPushService`，推送静默跳过，**不影响上行链路**。

---

## 5. 小程序落地（`feat/mp-ws`）

> 客户端只有 **一个长连接出口**：`medbox-miniapp/src/store/ws.js`（连接 / 心跳 / 退避重连 /
> `lastEventId` / 事件分发），页面只调 `onEvent` 订阅，**不要自己 `uni.connectSocket`**。

**握手失败的客户端口径（本分支补齐）**：小程序 `uni.connectSocket` 的 `fail` 只给 `errMsg`，
**拿不到第 1 章那个 HTTP 状态码与统一响应体**，因此客户端**无法按 `code` 分流**，一律按
"连接失败 + 指数退避重连"处理。续期按下表：

| 情形 | 客户端行为 |
|------|------------|
| **从未连上过**连续失败（后端没起 / 网段不对 / 参数不合法） | 只退避重连（1s→30s），**不调 `/auth/refresh`** —— 此时 refresh 只会因网络不通失败，反被当成"登录失效"清掉本地凭据 |
| **曾经连上过**后连续失败 3 次 | 先 `refreshAuth()` **一次**（并发安全）再立即重连；成功则重置退避，失败则**停止重连**并把登录态交给请求层的 40101 流程处理（连接存续期间后端不重鉴权，2 小时后重连最容易撞上这个场景） |
| 本地已无 token（退出登录） | 停止重连，不再建连 |

**其余客户端约定**：

- **`PONG` 不推进 `lastEventId`**：它是控制帧，服务端不进补推窗口；只有 `ALARM` / `DEVICE_STATUS` /
  `REMINDER` 推进，重连时作为 `&lastEventId=` 带上；
- **收到 `RESYNC`**：记下 `payload.latestEventId` 作为新起点，**业务页面改走 REST 拉全量**
  （本期无对应列表页，只派发事件；`feat/mp-alarm` / `feat/mp-device` 接手消费）；
- **订阅主题由调用方传**：老人端默认自己；监护人端传当前关注的 `elderId` ——
  "我监护的老人列表"接口属 P1 `feat/guardian-relation`，未落地前监护人**不传就不起连**，
  避免拿自己的 ID 去订阅被判越权后空转重连；
- **心跳**：客户端 30s 发 `{"type":"ping"}`（连接打开后才发，断开即清定时器）；切后台被系统
  掐断后回前台幂等补连（`App.vue` 的 `onShow`）。
