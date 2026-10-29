# 04 · 实时推送协议（WebSocket，后端 → 小程序）

> 用途：告警实时推送、设备在线状态、实时监测看板　|　局域网明文　|　版本见 `CHANGELOG.md`

## 1. 连接与鉴权

| 项 | 约定 |
|----|------|
| 端点 | `ws://{后端主机内网IP}:8080/medbox/ws?token={JWT}&topics=elder:e-1001`（局域网明文） |
| 鉴权 | 握手校验 JWT；按订阅主题鉴权，**越权主题直接拒绝整个握手** |
| 主题语法 | 仅支持 **`elder:{elderId}`**，多个用逗号分隔（`topics=elder:e-1001,elder:e-1002`）；**`device:{deviceId}` 本期不支持**，传了返回 40001 |
| 归属判定 | 复用 REST 同一口径（02_REST接口设计.md §1.3）：订阅主题必须落在"我可见的老人"内（老人 = 自己；监护人 = 所有 ACTIVE 监护关系）。PENDING / REVOKED / 无关系一律按越权处理 |
| 握手失败 | 直接返回 HTTP 状态码 + **统一响应体**（`code/message/traceId/timestamp`）：`40001`→400、`40101`→401、`40301`→403。握手不走 MVC，客户端按 HTTP 状态与 `code` 判断即可 |
| 心跳 | 客户端每 **30s** 发 `{"type":"ping"}`，服务端回 `PONG`；服务端 **90s**（3 个周期）无任何客户端帧即关闭连接 |
| 重连 | 断线指数退避重连（初始 1s，上限 30s）；**重连时把 `lastEventId` 放在握手 query 上**（`&lastEventId=evt-9a1c`），服务端建连即补推增量 |
| 补推窗口 | 服务端**按主题**各保留最近 **200 条 / 最近 10 分钟**的事件；窗口内取不到"该给的下一跳"（被淘汰或已跨过）则回 **`RESYNC`**，客户端改用 REST 拉全量 |
| 连接期 token | 握手时校验一次；**连接存续期间不主动踢**（局域网场景，2 小时有效期），重连时自然重新鉴权 |

## 2. 消息帧结构

```json
{ "type":"ALARM|DEVICE_STATUS|REMINDER|AI_STREAM|PONG|RESYNC",
  "id":"evt-9a1c",           // evt- + 单调递增序号的十六进制，用于断线补推
  "topic":"elder:e-1001",
  "ts":1759474520000,        // epoch **毫秒**（与 REST 响应体 R.timestamp 的秒不同）
  "payload": { } }
```

> `PONG` 是控制帧：**不带 `topic` 与 `payload`**（一条连接可能订阅多个主题，无从归属）。
> `RESYNC`：补推窗口过期时告诉客户端"别等补推了，改走 REST 拉全量"，否则客户端分不清"没有新事件"与"事件已被淘汰"。

## 3. 推送事件类型

| type | 触发时机 | payload 要点 |
|------|----------|--------------|
| ALARM | 后端生成漏服/环境告警 | alarmId, type, level(`INFO`/`WARN`/`CRITICAL`), message, elderId, medicineIds |
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

**REMINDER 生成规则**：后端定时扫描（默认 60s）取 `status='ENABLED' AND sync_state <> 'SYNC_FAILED'` 的计划，按设备本地时区在 `[now, now + lead-time-minutes]`（默认 10min）窗口展开"未来"服药槽，推 `REMINDER` 帧到 `elder:{elderId}`。设备本地声光提醒为主、云端 WS 为监护端兜底（默认不要求设备在线）。两道拦截：① 该槽已有 `med_record`（已服）跳过；② 去重表 `plan_reminder`（`dedup_key = REMINDER:{planId}:{slotUTC}`）已存在则跳过。`REMINDER` 不是告警，不进 `alarm` 列表。

**DEVICE_STATUS 推送规则**：触发点是 MQTT 侧的在线状态跃变（status / 遗嘱），推到 `elder:{elderId}` 主题（归属老人从 `device.elder_id` 取），不扩展 `device:` 主题。只在状态真的变了才推（主控每 60s 有心跳，每次上行都推会把小程序刷爆）；设备未登记（无归属老人）时无主题可推，静默跳过；`medbox.ws.enabled=false` 时推送静默跳过，不影响上行链路。
