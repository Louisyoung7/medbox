# 03 · 设备接入协议（MQTT，Broker = EMQX）

> 链路：设备 ⇄ EMQX ⇄ 后端　|　局域网内网部署　|　变更历史看 `git log -- medbox-spec/`

## 1. 连接与鉴权

| 项 | 约定 |
|----|------|
| Broker | **EMQX**，局域网内网部署：`tcp://{内网BrokerIP}:1883`（明文），详见文档 01 第 4 章。**设备**连这个地址；**后端**与 EMQX 同机时连 `localhost:1883` |
| clientId | = deviceId。**必填**（即使匿名连接也必须有）：EMQX 靠它区分身份、维护会话与遗嘱；相同 clientId 再次连接会踢掉前一个 |
| KeepAlive | 60s，心跳维持在线判定 |
| 遗嘱消息 LWT | topic=`.../status`，payload `{"online":false}`，异常掉线即时感知 |
| QoS | 遥测 QoS0/1；命令/事件 QoS1；关键下行命令 QoS1 且带 ack |
| Retained | status（在线状态）retained=true，订阅即得最新态 |
| 后端接入方式 | 后端以订阅方身份连到 EMQX，订阅 `.../up/#` 消费上报、向 `.../down/#` 发布下行 |
| 连接主体 | **一个药箱只有主设备（`MAIN`）建立 MQTT 连接**，clientId = 药箱 deviceId。温湿度 / 光照传感器是其**子设备**，不各自建 MQTT 连接，由主控在消息里带 `sensorId` 标明来源；**摄像头独立经 HTTP 直连后端上报服药事件（原图 + 自报置信度 + 结论字段，见第 4 章），不走 MQTT** |
| 消息幂等 | **所有上行消息必须带 `msgId`**，格式固定为 **`{deviceId}-{10位递增序号}`**（如 `BOXA-DEBUG-01-0000000124`，序号持久化、重启不归零）。断线重连重发时服务端按 `msgId` 去重，避免重复写入服药记录 / 告警 |
| 下行确认 | 下行消息带 `msgId` 与 `needAck`；设备处理后向 `.../up/ack` 回执。**重发策略：超时 5s 未回执则重发，最多 3 次**；3 次仍无回执则标记该下发失败并生成告警（见第 2 章） |

EMQX 自带 Dashboard（默认 `http://{内网IP}:18083`），可查看连接、Topic 监控与调试发布 / 订阅。

## 2. Topic 规范

统一前缀 **`medbox/{productKey}/{deviceId}/…`**（MQTT 规范不推荐以 `/` 开头，会产生空层级，故去掉前导斜杠），区分上行 up（设备发布）与下行 down（后端发布）。

| 方向 | Topic | QoS | 说明 |
|------|-------|-----|------|
| 设备→云 | `.../up/telemetry` | 0/1 | 环境遥测（**每个传感器各发一条**，只带自己负责的字段，见第 3 章） |
| 设备→云 | `.../up/event/error` | 1 | **设备侧异常**：门超时、识别失败、硬件故障等 |
| 设备→云 | `.../up/sensor/heartbeat` | 0 | 主控周期性上报各传感器子设备在线状态（子设备无遗嘱，判离线靠它，见第 3 章） |
| 设备→云 | **`.../up/ack`** | 1 | **下行消息回执**（计划 / 命令的 `msgId` 处理结果） |
| 设备→云 | `.../status (LWT)` | 0 | 在线/离线状态（retained） |
| 云→设备 | `.../down/schedule` | 1 | 下发/同步服药计划与提醒任务 |
| 云→设备 | `.../down/command` | 1 | 控制命令（重启/校准；**蜂鸣/解锁由设备本地或小程序本地触发，后端不下发**） |

## 3. Payload 示例

> **上行消息统一带 `msgId`**，服务端按 `msgId` 幂等去重（同一 `msgId` 重复到达只处理一次）。

上行·环境遥测 `.../up/telemetry`（**每个传感器各发一条**，只带自己负责的字段）：

```json
// 温湿度传感器（TEMP_HUMI）
{ "msgId":"BOXA-DEBUG-01-000123", "ts":1759474487000,
  "sensorId":"BOXA-DEBUG-01-S-TH01",
  "temperature":24.6, "humidity":52.1 }

// 光照传感器（LIGHT）
{ "msgId":"BOXA-DEBUG-01-000124", "ts":1759474488000,
  "sensorId":"BOXA-DEBUG-01-S-LX01",
  "lux":120 }
```

`sensorId` = 该传感器子设备的 `device_id`（见文档 06 的 2.3）；主控转发时原样带上，后端据此区分来源、只更新对应列。

上行·服药事件：**不走 MQTT**，由摄像头经 HTTP multipart 上报（端点与字段见 `02b_设备管理.md` §1.1）。`takenResult` 二值（`TAKEN` 已取药 / `UNTAKEN` 未取药）+ `source`（`CAMERA` / `MANUAL`）；**仅 `UNTAKEN` 附抓拍原图作留证，`TAKEN` 不上图**。后端不建模格子（无 `slotNo`）、不做人脸识别、无置信度、无第三态。

上行·设备异常 `.../up/event/error`（**仅设备侧异常**）：
```json
{ "msgId":"BOXA-DEBUG-01-000125", "ts":1759474512000, "type":"DOOR_TIMEOUT",
  "detail":"仓门开启超过 60s 未关闭" }
```
`type` 取值：`DOOR_TIMEOUT` 门超时 / `CAMERA_FAILED` 识别失败 / `HW_FAULT` 硬件故障。后端收到后**记入设备事件流水**（`GET /devices/{id}/events`）**并生成 `DEVICE_FAULT` 告警**（见文档 02f_告警.md 第 1 章）。

上行·传感器心跳 `.../up/sensor/heartbeat`（主控周期性汇总子设备状态）：
```json
{ "msgId":"BOXA-DEBUG-01-000128", "ts":1759474540000,
  "sensors":[ { "sensorId":"BOXA-DEBUG-01-S-TH01","online":true },
              { "sensorId":"BOXA-DEBUG-01-S-LX01","online":true },
              { "sensorId":"BOXA-DEBUG-01-S-CAM01","online":false } ] }
```
因为**只有主控有 MQTT 连接**，子设备没有自己的遗嘱消息，故由主控每 **60s** 汇总上报一次；后端据此更新各子设备的 `last_heartbeat` 与 `online_state`。**离线判定：连续 3 个周期（180s）未上报即判该传感器离线**，**不生成 DEVICE_OFFLINE 告警**，仅在设备详情与 `DEVICE_STATUS` 推送中标注。

上行·下行回执 `.../up/ack`（对应下行消息中的 `msgId`）：
```json
{ "msgId":"BOXA-DEBUG-01-000126", "ts":1759474520000,
  "ackFor":"d-001", "result":"OK", "reason":null }
```
`result`：`OK` / `FAILED`；`reason` 失败时给出简要原因（如 `"medicine_not_found"`）。服务端发出 `needAck:true` 的下行消息后，**5s 未收到回执则重发，最多 3 次**；仍无回执则标记下发失败并告警。

下行·同步计划 `.../down/schedule`（**一次提醒可含多种药，用 `items` 数组下发**）：
```json
{ "msgId":"d-001", "planId":"p-3301", "op":"UPSERT",
  "times":["08:00","20:00"], "repeat":"DAILY",
  "alarm":{"missAfterMin":15},
  "items":[ { "itemId":"pi-9001","medicineId":"m-205","dose":"1","unit":"片" },
            { "itemId":"pi-9002","medicineId":"m-388","dose":"2","unit":"粒" } ],
  "needAck":true }
```

> 设备按 `items` 逐种取药并**逐条上报**；本次提醒下所有 item 都上报才算完成，缺任一种药由后端判 MISS（见文档 06 的 2.6.1）。

> `op` 取值：`UPSERT`（新增 / 修改 / 启用，覆盖写该计划，载荷含完整 `times` / `repeat` / `alarm` / `items`）与 `DELETE`（删除 / 停用，设备按 `planId` 停该计划提醒，其余字段可空）。服务端在计划写事务提交后下发，设备回执 `up/ack` 成功 → 后端把 `med_plan.sync_state` 翻成 `SYNCED_TO_DEVICE`；回执 `FAILED` 或 5s 超时（重发 3 次仍无回执） → `SYNC_FAILED`。

下行·控制命令 `.../down/command`（REST 受理后转发，QoS1 + `needAck:true`）：
```json
{ "msgId":"d-102", "cmd":"REBOOT", "params":{}, "needAck":true }
```
`cmd` 取值：`REBOOT` 重启 / `CALIBRATE` 校准（白名单见文档 02b_设备管理.md §1.0.1，非法值 REST 层 40001 拦截、不会到这里）。**蜂鸣（`BUZZ`）/ 解锁（`UNLOCK`）由设备本地或小程序本地触发，后端不下发这两类指令**（见 01 §2.3）。设备处理后经 `up/ack` 回执 `ackFor=msgId`：回执 OK → `device_command.state=SUCCESS`；回执 FAILED 或 5s 超时重发 3 次仍无回执 → `FAILED`（见文档 06 的 2.17）。

## 4. 摄像头识别与上报链路（摄像头 → 后端 HTTP）

摄像头端侧判定「服药槽格子是否被取走」，直接经 HTTP 把取药结论上报后端（复用 `02b_设备管理.md` §1.1 的 captures 端点）。单片机与摄像头之间的协同（谁触发判定、如何取流）是设备固件内部实现，协议只约束「摄像头 → 后端」这一跳 HTTP。

```
摄像头(端侧判定格子是否取走) --HTTP multipart(takenResult + 仅UNTAKEN附file)--> 后端(据结论判已服/未服, 不跑识别)
```

**职责划分**：

- **摄像头**：端侧判定并经 HTTP 上报 `takenResult`（仅 `UNTAKEN` 附原图留证，可同时本地留证）；
- **单片机**：本地时钟基准与提醒触发（到点本地声光 + 解锁对应格，见 01 §2.3），不参与服药事件上报；环境遥测仍经 MQTT 上报；
- **后端**：只接收取药结论、不运行图像识别；据「规定时间内是否收到上报」判漏服（`MISS`）；`onTime` 由后端按「就近槽 + 三级最小容忍时长（计划级 > 老人级 > 15 分钟，见 07 §5）」重算，单侧窗口只判迟到，**改阈值无需重烧固件**。完整判定规则表见 `07_业务规则.md`。
