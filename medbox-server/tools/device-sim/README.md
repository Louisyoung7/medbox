# tools/device-sim · 设备侧模拟器（dev/QA）

不接真机也能跑通「设备上线 → 遥测/心跳 → 下行回执 → 摄像头抓拍 → 后端落库/判定 → WS 推送」链路。它是**设备对端**，严格复用 `medbox-spec/03` 第 1～4 章的 topic、payload、`msgId`、ack 与 multipart 字段，不扩展协议、不进生产构建、不占用 8080。

与后端 `POST /api/v1/_debug/mqtt/publish` 的区别：后者是**服务端单发报文**（无会话、不订阅 `down/#`、不回执，受 `medbox.devtools.enabled` 门禁）；本工具是**真实设备会话 + HTTP 直报**。两者并存，口径见 `medbox-spec/03` 第 5 章。

## 1. 准备

```bash
cd medbox-server/tools/device-sim
npm install                 # 唯一依赖：mqtt
```

- MCU 子命令需要 EMQX `tcp://localhost:1883` 可连，后端需 `medbox.mqtt.enabled=true`。
- camera 子命令只需后端 HTTP 可达；seed 类验收还需以 `medbox.devtools.enabled=true` 启动后端。
- 摄像头 ID 必须与后端登记一致；默认取 seed 的 `BOXA-DEBUG-01-S-CAM01`。
- 本工具不需要用户 JWT：抓拍走设备身份 Header，查询类验收借用 seed 返回的 `guardianToken`。

## 2. 命令

### MCU（药箱主控，唯一建 MQTT 连接）

```bash
npm run mcu -- --ack ok --duration-ms 90000
```

| 开关 | 默认 | 说明 |
|------|------|------|
| `--ack ok\|fail\|silent` | `ok` | 收到 `needAck:true` 的 `down/schedule` / `down/command` 后回 `OK` / `FAILED` / 不回执 |
| `--offline` | 关 | 完全不连 broker，只验证离线态 |
| `--error-type TYPE` | 无 | 额外发一条 `up/event/error`，TYPE ∈ `DOOR_TIMEOUT`/`CAMERA_FAILED`/`HW_FAULT` |
| `--broker URL` / `--device-id ID` / `--product-key PK` | `tcp://localhost:1883` / `BOXA-DEBUG-01` / `medbox-box` | 连接身份与 topic 前缀参数 |
| `--telemetry-interval-ms N` / `--heartbeat-interval-ms N` / `--duration-ms N` | `60000` / `60000` / `0`（常驻） | 周期与退出时长；`0` 时按 Ctrl+C 退出 |
| `--state-dir DIR` / `--sensor-th ID` / `--sensor-lx ID` / `--sensor-cam ID` | `.state` / `{deviceId}-S-TH01` / `-S-LX01` / `-S-CAM01` | 序号持久化目录与子设备编号 |

启动即做：以 `clientId=deviceId` 连接 → retained `{"online":true}` → 设 LWT → 订阅 `down/#` → 温湿/光照各一条遥测 → 一条传感器心跳；之后按周期重复。所有上行 `msgId` 为 `{deviceId}-{10位递增序号}`，序号写入 `.state/{deviceId}.json`（已 gitignore），重启不归零。

### Camera（摄像头 HTTP 直报）

```bash
npm run camera -- --capture taken --cell-no 1 --plan-id p-3301 --captured-at 2026-11-12T08:00:30Z
npm run camera -- --capture untaken                     # 自动生成 1×1 JPEG 留证图
npm run camera -- --capture none                        # 到点不上报（交给后端判 MISS）
```

| 开关 | 默认 | 说明 |
|------|------|------|
| `--capture taken\|untaken\|none` | `taken` | 取药结论；`TAKEN` 不附图、`UNTAKEN` 附图 |
| `--cell-no 1\|2\|3` / `--plan-id ID` / `--reason NORMAL\|MANUAL` | `1` / `p-3301` / `NORMAL` | multipart 字段 |
| `--captured-at ISO-8601` | 省略（后端取当前 UTC） | 上报时刻；自测 MISS 边缘时显式给出 |
| `--base-url URL` / `--device-id ID` | `http://localhost:8080/medbox/api/v1` / `BOXA-DEBUG-01` | 后端地址与主控 ID（摄像头 ID 由其派生，可用 `--sensor-cam` 覆盖） |
| `--device-id-mismatch` / `--unknown-device` | 关 | 验证 `X-Device-Id` 不一致 → 40001 / 未登记设备 → 40301 |

同一 `planId + cellNo` 60s 内重复上报只保留第一条，连续自测不同结论请换 `cellNo` 或时间槽。

## 3. 场景 → 预期（与联调验收清单对应）

| 场景 | 命令 | 后端预期 |
|------|------|----------|
| 上线 / 遥测 / 心跳 | `npm run mcu -- --duration-ms 90000` | 设备 ONLINE，详情有温湿度，三个传感器有心跳 |
| 计划同步成功 | `npm run mcu -- --ack ok --duration-ms 90000`，再改计划 | 日志出现 `up/ack` 且 `ackFor` 等于下行 `msgId`，计划 `SYNCED_TO_DEVICE` |
| 计划同步失败 | 同上改 `--ack fail` / `--ack silent` | `SYNC_FAILED`；silent 触发 5s×3 重发 |
| 命令终态 | `--ack ok` 时下发 `REBOOT` | 事件流 COMMAND 行 `state=SUCCESS` |
| 设备离线 | `npm run mcu -- --offline --duration-ms 90000` | 主控 180s 判离线并生成 `DEVICE_OFFLINE` |
| 已取药 / 未取药 | `npm run camera -- --capture taken` / `--capture untaken` | 记录页出现对应结论，UNTAKEN 带抓拍图 |
| 漏服 | `npm run camera -- --capture none` 且不补报 | 超容忍窗口后生成 `MISS` |
| 错误路径 | `--device-id-mismatch` / `--unknown-device` | 40001 / 40301 |

## 4. 测试与报告

```bash
npm test                                   # node:test：纯函数 + 本地假 HTTP 服务，CI 可跑
MEDBOX_DEVICE_SIM_SMOKE=true npm run smoke # opt-in 端到端：真 PG + EMQX + devtools 后端
MEDBOX_DEVICE_SIM_SMOKE=true MEDBOX_SIM_SMOKE_QUICK=true npm run smoke  # 只跑 ack=ok，跳过重发等待
```

- `npm test` 输出 TAP 报告（用例数 / 通过 / 失败），不需要外部服务。
- `smoke` 未设开关时直接跳过并退出 0，因此不会被 CI 误触发；设置后逐场景打印 `ok` / `FAIL`，结尾输出 `结果: N 通过 / M 失败`，有失败时退出码为 1。
- 冒烟前置：PostgreSQL 17、EMQX、`SPRING_PROFILES_ACTIVE=local` 且 `medbox.devtools.enabled=true` 的后端、可写的抓拍目录；seed 用 `?reset=true` 保证每次拿到当前时刻 +2 分钟的排程。
- 每条上报只打印 topic、`msgId`、场景名；不打印 token 或大 payload，日志里不会出现密钥。

## 5. 边界

- 不实现断网缓存 / 恢复补传；`--offline` 只模拟"从未上线"。
- 不判定 MISS、不推 WS 业务帧——这两项由后端扫描与 WS 通道负责，需按 `docs/单片机与摄像头联调对接.md` 单独验证。
- 无通用"订阅并打印下行"子命令；要看原始下行报文请用 EMQX Dashboard 或 `mosquitto_sub`（协议观察手段）。
- `clean session=false` 便于离线期间下行补投；真机固件若选 `true`，验收结果可能不同（见联调文档 §2.2 待确认项）。
- `TAKEN` 不附图在响应上的判定依据是**无 `sizeBytes` / 无 `fileToken`**；`mime` 因 `capture.mime` 的 DB 默认值可能仍回显 `image/jpeg`（见 06 的 2.12），不代表真有文件。
