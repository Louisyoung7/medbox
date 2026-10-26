# 02 · REST 接口设计

> 所有路径均相对 Base URL：`http://{后端主机内网IP}:8080/medbox/api/v1`　|　版本见 `CHANGELOG.md`
> 标注【鉴权】表示需携带 JWT，监护数据类接口需校验权限。AI 相关接口见文档 05。

## 1. 通用约定

| 项 | 约定 |
|----|------|
| Base URL | `http://{后端主机内网IP}:8080/medbox/api/v1`（局域网内网地址，见文档 01） |
| 请求体格式 | `application/json; charset=utf-8` |
| 认证方式 | Header: `Authorization: Bearer {JWT}`（设备端走 MQTT，本期匿名连接，不走此通道；抓拍上传的设备侧鉴权见 4.1） |
| 时间格式 | ISO-8601 **UTC**，如 `2026-10-03T07:04:00Z`；前端本地化展示 |
| 时区口径 | 除服药计划的 `times`（见第 6 章）外，**所有时间一律 UTC**。`med_plan.times` 存**设备本地墙钟时间**（如 `"08:00"`），时区取 `device.timezone`（默认 `Asia/Shanghai`）；服务端判定时换算成 UTC 后再比较 |
| 幂等 | 写操作支持 Header: `X-Request-Id`（UUID）去重 |
| 分页 | query 参数 `page`(从 1 起)、`size`(默认 20, 上限 100)；响应返回 `total` |
| 版本 | URI 版本化 `/v1`；向后兼容字段追加不升版本 |

### 1.1 统一响应结构

```json
{
  "code": 0,            // 0=成功，非0=业务错误码
  "message": "success",
  "data": { },          // 业务数据；失败时可为 null
  "traceId": "9f2c...", // 链路追踪，便于排障
  "timestamp": 1759474487
}
```

**落地约定（后端 `feat/backend-response`）**：

- **`traceId`**：请求进来时由后端生成（32 位十六进制、无连字符）。客户端可用 `X-Request-Id`（即 1. 通用约定里的幂等头）自带，后端校验字符合法性后原样沿用，并同时用 **`X-Trace-Id` 响应头**回写；日志格式带 `[trace=xxx]`，凭响应里的 traceId 可直接 grep 到同一次请求的全部日志。**幂等去重不在这一层**（见 `feat/backend-auth`）。
- **`timestamp`**：epoch **秒**（不是毫秒）。
- **`data` 为 null 时**：受全局 `spring.jackson.default-property-inclusion: non_null` 影响，**该字段不出现在 JSON 里**（文档允许"失败时可为 null"），小程序按"取不到 data 即失败"处理即可。响应体**只有这五个字段**，不会夹带额外属性。
- **`/medbox/actuator/**`（健康检查等）是 Spring 原生格式，不套这层包装**；业务接口一律 `R<T>`。

**小程序侧落地（`feat/mp-request`）**：

- **`X-Request-Id`**：小程序对**写方法**（`POST` / `PUT` / `PATCH` / `DELETE`）自动注入，值为**32 位小写十六进制**（无连字符），与后端 `newTraceId()` 同形，因此后端 `TraceContext.sanitize` 一定沿用，前后端日志可按同一个 traceId grep；`GET` 不注入（无幂等诉求）。小程序运行时没有 `crypto.randomUUID`，用 `crypto.getRandomValues` 生成、`Math.random` 回落（请求号只用于追踪与去重，不要求密码学强度）。
- **判定失败只看 `code`**：`code !== 0` 即失败，取 `message` 提示；**`data` 字段缺失按 `null` 处理**，不能把"取不到 data"当异常（见上一条 `non_null`）。
- **网络层失败**（没到服务端）不是业务码，小程序用客户端本地码 `-1`（仅前端约定，不进 1.2 的封闭集合）与"请检查是否在同一局域网"文案区分。
- **`40101` 的处理顺序（V15 修正，`feat/mp-auth`）**：请求层拿到 `40101` 时**先把错误交给 `feat/mp-auth` 注入的处理器续期**，处理器兑现真值（已换新 token）就用**同一份参数重发一次**（只重发一次），页面完全无感知；**只有续期失败才清本地凭据 + 跳登录页**。旧实现在调处理器**之前**就清掉了 refresh token，处理器根本无从刷新；且 40101 时 Promise 已 reject，处理器内部重试的结果也回传不到调用方 —— 因此重试必须放在请求层。

### 1.2 通用错误码

| code | HTTP | 含义 |
|------|------|------|
| 0 | 200 | 成功 |
| 40001 | 400 | 参数校验失败 |
| 40101 | 401 | 未登录 / Token 过期 |
| 40102 | 401 | 账号或密码错误 |
| 40301 | 403 | 无权限（越权访问他人数据 / 设备） |
| 40302 | 403 | 监护关系未生效（关系仍为 PENDING，老人端尚未确认） |
| 40401 | 404 | 资源不存在 |
| 40901 | 409 | 状态冲突（如设备离线无法下发命令、药品仍被库存引用无法删除） |
| 42901 | 429 | 触发限流 |
| 50000 | 500 | 服务端内部错误 |
| 50310 | 503 | AI 大模型服务暂不可用 / API Key 无效 |
| 40902 | 409 | 活跃账号禁止经管理接口硬删（测试数据清理：`/admin/users` 硬删 `ACTIVE` 用户） |

> **上表是封闭集合**：新增错误码必须同时改本表与后端 `ErrorCode` 枚举，二者不一致即视为缺陷。
>
> **两个补充口径（后端 `feat/backend-response`）**：
>
> 1. **未知路径**（`/medbox/api/v1/xxx` 拼错、路径不存在）也返回 **`40401` + HTTP 404** 的统一结构，而不是 Spring 默认的错误页；
> 2. **没有 405 对应的错误码**：请求方法用错（如 POST 打一个 GET 接口）统一按 **`40401` + HTTP 404** 返回。
>
> 参数校验 / 请求体 JSON 非法 / 缺参 / 参数类型不匹配一律 **`40001`**，提示取第一条错误（如 `name 不能为空`）；未捕获异常一律 **`50000`**，堆栈只进日志、不回显给客户端。

### 1.3 权限默认规则

除接口上显式标注【鉴权·仅监护人】等限定外，按下述默认规则校验（角色定义见文档 01 第 3 章）：

| 角色 | 默认权限 |
|------|----------|
| 老人本人 | **只读本人数据**：本人计划、记录、告警、本人设备状态、AI 问答；不可改计划 / 配置 Key |
| 监护人 / 子女 | 对**已生效（ACTIVE）监护关系**下的老人：**可读可写**（计划、药品、库存、告警处理、阈值、AI Key 配置） |
| 社区护理人员 | 所辖老人数据**只读** + **处理告警** + **导出记录**；不可改计划与药品 |
| 家庭医生 | **制定 / 修改服药计划** + **维护药品禁忌知识库** + 查看依从性报告；不处理告警导出 |
| 设备端 | 仅 MQTT 通道（不走 REST，见文档 03） |

**通用校验顺序**：① 是否登录（40101）→ ② 对目标 `elderId` / `deviceId` 是否有监护 / 管理关系（40301）→ ③ 关系是否已生效（40302）→ ④ 该角色是否具备此操作权限（40301）。越权一律 40301，不区分"资源不存在"与"无权限"，避免资源枚举。

#### 1.3.1 权限判定规则表（🧭 判定型，`feat/backend-authz`）

**输入**：当前登录用户（`userId` + `user.role` ∈ {ELDER, GUARDIAN}）、目标资源归属 `elderId`（`deviceId` 先解析成 `elderId`）、本次操作所需的 `Permission`、`guardian_relation` 记录。

| 步 | 检查 | 通过条件 | 不通过 |
|----|------|----------|--------|
| ① | 是否登录 | `AuthContext` 中存在当前用户 | **40101** |
| ② | 是否有关系 | 老人本人 → 目标 `elderId` 必须等于自己；监护人 → 必须存在 `guardian_relation` 记录，目标确实是老人，且 `status != REVOKED` | **40301** |
| ③ | 关系是否生效 | `status == ACTIVE`（老人本人直接通过）。`REVOKED` 与状态为空（脏数据）走第 ② 步的 40301 | `PENDING` → **40302** |
| ④ | 角色操作权限 | 命中下方权限矩阵 | **40301** |

**输出**：放行返回访问身份（`kind` = SELF / GUARDIAN、`relationRole`）；拒绝抛 `BizException`，HTTP 403 + `code` 为 40301 / 40302。

**权限矩阵**（行 = 访问身份，列 = 权限；✅ 放行，空白 = 40301）：

| Permission | 老人本人 | FAMILY | NURSE | DOCTOR |
|------------|:--------:|:------:|:-----:|:------:|
| `READ_PLAN` `READ_MEDICINE` `READ_RECORD` `READ_ALARM`<br/>`READ_DEVICE` `READ_ENV` `READ_ADHERENCE` `READ_GUARDIAN` | ✅ | ✅ | ✅ | ✅ |
| `WRITE_PLAN`（创建 / 修改 / 停用 / 删除计划） | | ✅ | | ✅ |
| `WRITE_MEDICINE`（药品档案与库存、补药入库） | | ✅ | | |
| `WRITE_RECORD`（监护人手工补录 / 确认） | | ✅ | | |
| `HANDLE_ALARM`（告警处理闭环） | | ✅ | ✅ | |
| `EXPORT_RECORD`（导出服药报告） | | ✅ | ✅ | |
| `CONFIG_LLM_KEY`（配置大模型 Key） | | ✅ | | |
| `WRITE_ALARM_SETTINGS`（配置老人级告警阈值：提前期 / 环境上下限 / 漏服容忍） | | ✅ | | |
| `READ_CAPTURE`（查看抓拍图片） | ✅ | ✅ | | |
| `WRITE_CAPTURE`（删除抓拍图片） | | ✅ | | |
| `WRITE_DEVICE`（下发控制命令） | | ✅ | | |
| `WRITE_DRUG_MANUAL`（维护禁忌知识库） | | | | ✅ |
| `AI_CHAT`（AI 药品问答） | ✅ | ✅ | | |

> `READ_CAPTURE` / `WRITE_CAPTURE` 对护理 / 医生**显式拒绝** —— 用药图像属敏感健康数据，可见范围比本节默认规则更严格（见文档 01 的 4.6）。

**边界口径**：

| 场景 | 结果 | 说明 |
|------|------|------|
| 无 / 无效 / 过期的 access token | 40101 | 拿 refresh token 当 access 用也算无效（`typ` 校验） |
| 拿 refresh token 当 access | 40101 | 同左 |
| 老人访问他人 `elderId` | 40301 | 只读本人 |
| 老人执行写操作 | 40301 | 老人只读，不可改计划 / 配置 Key |
| 监护人与目标无 `guardian_relation` | 40301 | 越权 |
| 关系 `PENDING` | **40302** | 老人端尚未确认；此判定**优先于**第 ④ 步 |
| 关系 `REVOKED` / 状态为空 | 40301 | 与"无关系"同码，不回 40302，避免泄漏"曾有关系" |
| `guardian_relation.role` 为空 | 按 **FAMILY** 兜底 | 打 warn 日志；不因空值放行或崩溃 |
| 目标 `elderId` 不存在 / 不是老人 | 40301 | 不返回 40401，防资源枚举 |
| `elderId` / `deviceId` 为空 | 40001 | 参数校验失败 |
| `GET /users/me` 但账号已不存在 | 40401 | 唯一例外：当前用户自己的资源，不泄漏任何东西 |

**后端侧落地（`feat/backend-authz`）**：

- **判定入口**：`AccessService.require(elderId, Permission)` —— **后续所有业务分支的唯一调用点**；判定放 Service 而非拦截器，因为拦截器解析不出"目标资源归属哪位老人"。第 ① 步仍由 `AuthInterceptor` 负责（现状不变）。
- **权限矩阵**：集中在 `common/security/Permission.java` 的 `allows(identity, permission)`，一处改动全端生效；矩阵共 19 个权限项，见上表。
- **枚举**：`RelationRole`（FAMILY / DOCTOR / NURSE，护理 / 医生是**监护关系上的角色**，不是注册角色）、`RelationStatus`（PENDING / ACTIVE / REVOKED）。
- **`GET /users/me`**：`UserController` + `UserService`，返回 `userId` / `role` / `name` / `phone` / `username`；小程序启动时靠它做冷启动静默续期。
- **列表类接口的行级过滤**：用 `AccessService.visibleElderIds()` 一次取"我可见的老人 ID 集合"做 IN 过滤（如药品的"公共药品 + 我监护老人的私有药品"），避免逐条判定的 N+1。
- **设备维度**：`requireDevice(deviceId, Permission)` 依赖 `DeviceOwnerResolver` 扩展点，**本分支只定义接口不实现**，由 `feat/device-core` 引入 `DeviceMapper` 后补（未接入时抛 50000 并打日志）。
- **非 Web 线程（定时任务 / MQTT 消费）没有 `AuthContext`**：不要走 `AccessService`，直接调 Mapper。
- **`GET /users/{elderId}/profile`（P1 `feat/user-profile`）已交付**：老人基础信息，监护人可见，见第 3 章。监护关系的申请 / 确认 / 解除写接口已由 `feat/guardian-relation` 交付，见 3.2。

### 1.4 接口调试（curl）

后端接口一律可用 `curl` 直接调试（局域网阶段为明文 HTTP，无需证书）。典型流程：

```bash
# 1) 登录拿 token
TOKEN=$(curl -s -X POST http://192.168.1.10:8080/medbox/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"account":"13800001234","password":"******"}' | jq -r '.data.token')

# 2) 带 token 调业务接口
curl -s "http://192.168.1.10:8080/medbox/api/v1/plans?elderId=e-1001&page=1&size=20" \
  -H "Authorization: Bearer $TOKEN" | jq

# 3) 写操作带上幂等头与请求体
curl -s -X POST http://192.168.1.10:8080/medbox/api/v1/plans \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'X-Request-Id: 8f2c1a9e-3b7d-4c1a-9f0e-2d5b6a7c8f10' \
  -d '{"elderId":"e-1001","name":"早餐后","times":["08:00"],"repeatRule":"DAILY",
       "items":[{"medicineId":"m-205","dose":"1","unit":"片"}]}' | jq
```

建议配合 `jq` 查看响应；也可用 Postman / Apifox 导入同一套接口。抓拍图片上传用 `curl -F`（multipart）：

```bash
curl -s -X POST http://192.168.1.10:8080/medbox/api/v1/devices/BOXA-DEBUG-01-S-CAM01/captures \
  -H "X-Device-Id: BOXA-DEBUG-01-S-CAM01" -H "X-Device-Sign: <hmac>" -H "X-Timestamp: 1759474540" \
  -F "file=@capture.jpg" -F "reason=WRONG_DRUG" -F "medicineId=m-205" | jq
```

### 1.5 快速起联调数据（feat/backend-devtools）

> 面向开发者 / QA 的局域网联调辅助端点，全部走 `medbox.devtools.enabled` 开关（**默认关闭**，生产零暴露面），不引入新鉴权实体、不新增错误码。先置 `medbox.devtools.enabled: true`（见 application.yml 的 `medbox.devtools` 段），重启后可用。

**1）一键播种最小数据集** `POST /api/v1/_debug/seed`

返回 1 监护人（`13800001234` / 密码 `123456`，userId `u-debug-guardian`）+ 1 老人（userId `e-1001` / 手机号 `13800008888` / 密码 `123456`）+ `ACTIVE` 监护关系 + 1 药箱主控（`BOXA-DEBUG-01`）+ 3 传感器子设备（`BOXA-DEBUG-01-S-TH01` 温湿度 / `BOXA-DEBUG-01-S-LX01` 光照 / `BOXA-DEBUG-01-S-CAM01` 摄像头）+ 公共药 `m-205`（阿司匹林）+ 老人私有药 `m-206`（甲硝唑）+ 库存 `s-debug-001` + 计划 `p-3301`（含计划项 `pi-9001`，每日 `08:00`）。响应里**直接带监护人 access token 与老人 access token**，可立刻复用：

> **为什么只有部分 ID 带 `debug` 段**：业务号由 `user_biz_id_seq` / `medicine_stock_biz_id_seq` 等从 **1001** 起生成 —— 清库后首个注册用户必是 `u-1001`、首个库存必是 `s-1001`，种子若占用它们会与真实数据撞唯一约束，`?reset=true` 的清理更会按固定 ID 误删真实账号 / 库存；设备号用 `BOXA-DEBUG-01` 则是为了不与真机序列号重名（`clearSeed` 会按它删设备）。而 `e-1001` / `m-205` / `p-3301` / `pi-9001` 不在业务序列生成值域内（业务号恒为前缀 + 数字、且从 1001 起），永远不会与真实数据相撞，故保留短 ID。

```bash
# 播种（幂等：已存在则跳过写入；?reset=true 先清后种）
# 免登录：不需要 Authorization 头（见下方口径说明）
GUARDIAN_TOKEN=$(curl -s -X POST http://192.168.1.10:8080/medbox/api/v1/_debug/seed | jq -r .data.guardianToken)
ELDER_TOKEN=$(curl -s -X POST http://192.168.1.10:8080/medbox/api/v1/_debug/seed | jq -r .data.elderToken)
echo "$GUARDIAN_TOKEN"; echo "$ELDER_TOKEN"
```

> **鉴权口径（2026-10-24 修正，2026-10-25 新增 trigger）**：`_debug/seed`、`_debug/mqtt/**` 与 `_debug/plan-reminder/trigger` **免登录** —— `WebMvcConfig` 把这三个路径从 `AuthInterceptor` 的 `/api/v1/**` 里单独排除（`DevtoolsControllerTest.noLoginRequired` 即验证不带 `Authorization` 也能 200）。理由是端点本身已由 `medbox.devtools.enabled` 门禁：该属性为 false 时 `DevtoolsController` 根本不装配，访问返回 `40401`（`application.yml` 配了 `spring.mvc.throw-exception-if-no-handler-found: true` 与 `add-mappings: false`，未命中路径经 `GlobalExceptionHandler` 统一成 40401），生产零暴露面；开启即代表"本地开发模式"，再要求登录纯属冗余摩擦。带了 token 也无害，只是不生效、也不需要。
>
> ⚠️ **同前缀的 `/_debug/ws/publish` 仍必须登录**：它要走 `AccessService.require` 做归属校验（越权照样 40301），而该校验依赖 `AuthInterceptor` 写入的 `AuthContext`。因此**不能**把整个 `/api/v1/_debug/**` 一刀切免登录—— Devtools 与 WsDebug 的免登录策略是刻意不同的（见 `medbox-spec/04` 与 `WebMvcConfig` 注释）。

**2）服务端模拟设备上报** `POST /api/v1/_debug/mqtt/publish`

等价于"服务端版 `mosquitto_pub`"：把报文发到 `medbox/{productKey}/{deviceId}/up/{subTopic}`，流经真实入站网关 → 各 `UpMessageHandler` → 落库 → WS 推送整条链路。无真设备时也能端到端验证（详见文档 03 第 5 章）。`payload` 透传原始 JSON（**必须是 JSON 字符串**，后端 DTO 声明为 `String` 并原样透传，传对象会被 Jackson 拒掉）：

```bash
# 免登录：不需要 Authorization 头（带了也无害，但不生效、也不需要）
curl -s -X POST http://192.168.1.10:8080/medbox/api/v1/_debug/mqtt/publish \
  -H "Content-Type: application/json" -d '{
    "deviceId":"BOXA-DEBUG-01",
    "subTopic":"event/dispense",
    "payload":"{\"msgId\":\"BOXA-DEBUG-01-000123\",\"ts\":1759474500000,\"planId\":\"p-3301\",\"planItemId\":\"pi-9001\",\"medicineId\":\"m-205\",\"actualDose\":\"1\",\"unit\":\"片\",\"wrongDrug\":false,\"onTime\":true,\"confidence\":0.93,\"source\":\"CAMERA\"}"
  }'
```

> `productKey` 缺省取 `medbox.device.default-product-key`（默认 `medbox-box`）。MQTT 未连 broker 时返回 `50000`，提示先启动 EMQX。本端点不触发 `plan-sync` 下发（种子计划经 Mapper 直写，`syncState` 置 `PENDING`），设备不在场不会产生 `SYNC_FAILED` 噪音。

**3）手动触发服药提醒扫描** `POST /api/v1/_debug/plan-reminder/trigger`

绕过 60s 定时器，立刻跑一次 `PlanReminderScanner`（与定时任务走**完全相同的代码路径**：去重 / 已服两道拦截照常生效）。联调时用来"立刻看到一帧 `REMINDER`"，不必等计划时间落进 `[now, now+提前量]` 窗口。`now` 为可选 ISO 时刻（`2026-10-25T06:00:00Z` 或带偏移的 `2026-10-25T14:00:00+08:00`），缺省用系统时钟——常用它把"窗口"对准某个计划的服药时刻。受 `medbox.plan-reminder.enabled` 约束：若该开关关掉，这里也只返回零汇总、不推送。响应直接返回本次扫描汇总 `ScanSummary`（各计数见下表），便于确认"推了几个 / 跳了几个"。

| 字段 | 含义 |
|------|------|
| `scannedPlans` | 参与扫描的候选计划数（`selectDueForReminder` 命中且 `ENABLED`、非 `SYNC_FAILED`） |
| `pushed` | 实际推送 `REMINDER` 帧的计划数 |
| `skippedTaken` | 因该槽已有 `med_record`（已服）而跳过的槽数 |
| `skippedDedup` | 因 `plan_reminder` 去重表已存在而跳过的槽数 |
| `skippedOffline` | 因主控离线（且开启 `skip-when-device-offline`）而跳过的计划数 |
| `skippedNoFutureSlot` | 窗口内无未来 slot 而跳过的计划数 |

```bash
# 免登录（与 seed / mqtt 同属 DevtoolsController 免登录策略，见下方口径）
# 不带 now：用系统时钟立即扫一次；带 now：把窗口对准指定时刻
curl -s -X POST "http://192.168.1.10:8080/medbox/api/v1/_debug/plan-reminder/trigger" | jq
curl -s -X POST "http://192.168.1.10:8080/medbox/api/v1/_debug/plan-reminder/trigger?now=2026-10-25T06:00:00Z" | jq
```

> **配合种子数据使用**：`POST /api/v1/_debug/seed` 播种的计划，其服药时刻被设为"设备本地当前时刻 +2 分钟"，故播种后立刻调用本端点（不传 `now`）即可命中风入窗口、直接收到 `REMINDER` 帧；`?reset=true` 重种会一并清理 `plan_reminder` 去重记录，可重复触发验证。

## 2. 核心数据模型

> 全部实体清单与建表 DDL 见文档 06《数据库设计》。本文件只列接口用到的实体名，便于对照路径含义。

涉及实体：User（老人/监护人）、GuardianRelation、Device（药箱主控 + 传感器子设备）、Medicine、MedicineStock（库存）、MedPlan（服药计划）、MedRecord（服药记录）、EnvSample（环境遥测）、Alarm（告警）、AlarmSetting（告警规则）、LlmConfig（大模型配置）、DrugManual（说明书）、DrugManualChunk（说明书向量分块，见文档 05 / 06）。


## 3. 认证与用户

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /auth/register | 账号密码注册（手机号 + 密码 + 角色），返回 JWT |
| POST | /auth/login | **账号密码登录**（手机号 / 用户名 + 密码），返回 JWT |
| POST | /auth/refresh | 刷新 Token |
| GET | /users/me | 【鉴权】当前用户资料与角色 |
| POST | /users/me/delete | 【鉴权】**注销当前账号**（软删除，密码二次确认，见 3.1） |
| POST | /users/{elderId}/guardians | 【鉴权·监护人发起】申请绑定监护关系（创建 `PENDING` 关系） |
| GET | /users/guardians/pending | 【鉴权·老人端】我待确认的监护请求列表 |
| PATCH | /users/guardians/{relationId}/accept | 【鉴权·老人端】确认监护请求（置 `ACTIVE`） |
| DELETE | /users/guardians/{relationId} | 【鉴权】拒绝 / 解除监护关系（老人端拒绝、任一方解除） |
| GET | /users/{elderId}/profile | 【鉴权】老人基础信息（监护人可见） |

> **监护绑定为两步**：① 监护人发起申请 → 关系 `PENDING`；② 老人端在"待确认"列表中确认 → 关系 `ACTIVE`，监护人才真正获得读写权限。未确认前访问该老人数据返回 **40302**。

> **认证方式约定**：小程序端主登录方式为**账号 + 密码**，**不再使用短信验证码**（免去短信网关、验证码下发与存储）。
>
> - 登录账号为手机号或用户名；密码服务端 **BCrypt 加盐哈希**存储，库内不存明文，登录失败统一返回 `40102` 不区分"账号不存在 / 密码错误"；
> - 局域网阶段为明文 HTTP，密码在传输层可见；在意的话前端可先做一次 SHA-256 再传（**不是安全替代**），正式环境直接上 HTTPS（见文档 01 附录）；
> - **本阶段不做微信登录**：`wx.login` + `code2session` 虽然是免费的基础能力（个人主体小程序即可用，无需微信认证），但仍需后端出网访问 `api.weixin.qq.com`、配置 appid / secret，并额外设计"首次微信登录如何绑定已有账号"的流程。当前统一走账号密码；后续需要时再加回 `POST /auth/login/wechat` 并在 `user` 表补 `wechat_open_id` 字段即可。
> - **会话有效期（已确定）**：access token **2 小时**（`expiresIn: 7200`），refresh token **7 天**；refresh 采用轮换（换新即作废旧的）。
> - **老人账号的创建方式（已确定）**：**保留 `/auth/register`**，监护人可在小程序内注册并为老人代建账号；Web 管理后台亦可创建，但本期不做后台。

**示例：账号密码登录**

```
POST /auth/login
{ "account":"13800001234", "password":"******" }

// 响应
{ "code":0, "data":{ "token":"eyJhbGciOi...", "refreshToken":"...",
                     "userId":"u-1001", "role":"GUARDIAN", "expiresIn":7200 } }
```

**注册**

```
POST /auth/register
{ "phone":"13800001234", "password":"******", "role":"GUARDIAN",
  "username":"lisi", "name":"李四" }

// 响应：与登录同构（注册成功即登录，直接返回可用 token）
{ "code":0, "data":{ "token":"eyJhbGciOi...", "refreshToken":"...",
                     "userId":"u-1002", "role":"GUARDIAN", "expiresIn":7200 } }
```

- `role` **只取 `ELDER` / `GUARDIAN`**（`user.role`，见 06 的 2.1）；**护理 / 医生不在注册范围** —— 他们是 `guardian_relation.role`（`FAMILY` / `DOCTOR` / `NURSE`），由老人在确认监护关系时指定，小程序注册页因此只有两个选项；
- `username` / `name` 选填；监护人可用本接口**为老人代建账号**（`role=ELDER`）；
- 手机号重复等参数问题按 1.2 的 `40001`（提示取后端 `message`）。

**刷新**

```
POST /auth/refresh
{ "refreshToken":"..." }

// 响应（轮换：新的 refreshToken 会顶替旧的，旧的立即作废）
{ "code":0, "data":{ "token":"eyJhbGciOi...", "refreshToken":"...", "expiresIn":7200 } }
```

**当前用户资料**

```
GET /users/me

// 响应
{ "code":0, "data":{ "userId":"u-1001", "role":"GUARDIAN",
                     "name":"李四", "phone":"13800001234", "username":"lisi" } }
```

> 小程序只用 `userId` / `role` / `name` / `phone`（`role` 决定角色视图，见 01 第 3 章）；其余字段随后端实现追加，前端整份缓存不挑字段。

**指定老人资料（监护人可见）**

```
GET /users/{elderId}/profile

// 响应：结构同 /users/me；null 字段按全局 non_null 规则省略（如老人无 username 则不返回该键）
{ "code":0, "data":{ "userId":"u-1001", "role":"ELDER",
                     "name":"张三", "phone":"13800001234", "username":"zhangsan" } }
```

- **可见性**：老人本人，或与老人存在 `ACTIVE` 监护关系的监护人 —— 复用 `AccessService.require(elderId, READ_GUARDIAN)`，判定顺序见 1.3（① 40101 → ②③ 关系 → ④ 权限矩阵）。
- **越权口径（同 1.3 防枚举）**：无关系 / `REVOKED` / 目标不是老人 → **40301**；关系 `PENDING` → **40302**（老人端尚未确认）。

#### 3.1 注销账号（账号删除 🧭，`feat/backend-user-delete`）

> **"退出登录"与"注销账号"是两件事**：退出登录只在**客户端清凭据**（小程序 `store/auth.js` 的 `logout()`，**不调任何后端接口**）；注销账号是**服务端把账号置为不可用**并释放登录标识。此前只设计了前者，本节补后者。

```
POST /users/me/delete
{ "password":"******" }

// 响应
{ "code":0, "data":{ "userId":"u-1002", "deletedAt":"2026-10-14T08:30:00Z" } }
```

- **路径为什么挂在 `/users/me` 下**：`/api/v1/auth/**` 在 `AuthInterceptor` 里是**放行**的，而注销必须先登录，故不能放 `/auth/logout` 一类路径；动作型子路径 + `POST`，与 `PATCH /users/guardians/{relationId}/accept` 同风格。
- **必须传密码**：注销不可逆，后端用 **BCrypt 校验当前账号密码**做二次确认（前端弹窗确认不算数，接口才是唯一入口）。

🧭 **规则表：输入 → 判定条件与优先级 → 输出**

| 顺序 | 判定条件 | 输出 | 说明 |
|------|----------|------|------|
| ① | 未登录 / token 无效或过期 | **40101** | 校验顺序第 ① 步，同 1.3 |
| ② | `password` 缺失 / 空串 / 非字符串 | **40001** | 提示取后端 `message` |
| ③ | 账号已是 `DELETED` | **40101** | 不回 40401，避免泄漏"账号曾存在" |
| ④ | 密码 BCrypt 校验不符 | **40102** | **不注销**；与"账号不存在"同码 |
| ⑤ | 全部通过 | `code=0` | 执行下方数据处理 |

**注销后的数据处理（软删除，按此顺序执行，不级联删除业务数据）**

| 对象 | 处理 |
|------|------|
| `user` | `status='DELETED'`、`deleted_at=now()`；**`phone` / `username` 置 `NULL`** 释放唯一键（同一手机号可重新注册）；行本身保留，历史数据的外键仍然成立 |
| `refresh_token` | 该用户全部生效中的行 → `REVOKED`（复用 `RefreshTokenService.revokeAll`），**注销是 `REVOKED` 的唯一主动触发点** |
| `guardian_relation` | 该用户作为 `elder_id` **或** `guardian_id` 的全部 `PENDING` / `ACTIVE` 关系 → `REVOKED`（**双向**） |
| 业务数据 | `device` / `medicine` / `medicine_stock` / `med_plan` / `med_record` / `env_sample` / `alarm` / `capture` / `llm_config` / `drug_manual` **全部保留不动** —— 用药记录需留痕，且设备是实物、不随账号消失 |
| access token | **无状态，不做黑名单**：已签发的 access 最长 **2 小时**仍可用，靠 refresh 已撤销兜底（01 附录第 3 条的"服务端吊销列表"本期仍不做） |
| WebSocket | 不主动踢连接（2 小时内自然过期）；`WsSessionManager` 若要按 `userId` 主动关闭，留作后续优化 |

**边界用例**

| 用例 | 结果 |
|------|------|
| 注销后立刻用旧 access 调业务接口 | 2 小时内仍可用（**已知且接受**的口径），之后 40101 |
| 注销后调 `/auth/refresh` | **40101**（token 已 `REVOKED`）；此时**不再触发"重放检测撤销全部"**（该用户已无生效 token，且不必再为已注销账号写库） |
| 注销后用旧密码登录 | **40102**（等同"账号不存在"） |
| 用同一手机号重新注册 | 成功，分到**新的 `user_id`**，旧数据不继承 |
| 老人注销 | 其 ACTIVE 监护关系 → `REVOKED`，监护人的"我监护的老人"里该老人消失 |
| 监护人注销 | 只撤销**自己**的关系；老人与其他监护人不受影响 |
| 注销后 2 小时内换一台设备重复提交 | 第二次 **40101**（账号已 `DELETED`） |

> **未做（有意）**：监护人代老人注销（需扩 `Permission` 权限矩阵，见 1.3.1，留后续）；微信登录场景下的确认方式（届时改手机号 / 验证码确认）；access token 黑名单。

**小程序侧落地（`feat/mp-user-delete`）**：

- `src/api/user.js` 提供 `deleteAccount(password)`；注销动作**编排在 `src/store/auth.js`**（与 `logout()` 并列），成功后 `clearSession()` + `reLaunch` 登录页 —— 页面只调编排层，不要自己拼"调接口 + 清 token"；
- 入口放在个人页（`pages/profile/profile.vue`）"退出登录"下方，**危险操作样式 + 二次确认弹窗 + 输密码**；
- 注销请求**不要带 `noAuthRetry`**（它是正常鉴权接口，40101 走标准续期流程即可）。
- **落地补充（已完成）**：成功后编排层还会调 `disconnect()` 断掉 WS —— 服务端侧不踢连接（见上），但客户端已知身份作废，不该继续以已注销身份订阅 `elder:{id}`；确认弹窗用 `MbModal` 的 `confirmType="danger"` / `confirmLoading` / `closeOnConfirm=false`（**关闭时机交给页面**：提交中要留着转圈，密码错误时要留着改密码）。

**后端侧落地（`feat/backend-user-delete`）**：

- Flyway `V7__user_delete.sql`：`"user"` 表加 `status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'`、`deleted_at TIMESTAMPTZ`（见 06 的 2.1）；
- `AuthService.login`：`status='DELETED'` 视为账号不存在 → **40102**（且仍白算一次 BCrypt 做耗时对齐）；
- `AuthService.refresh`：账号为 `DELETED` → **40101**，且不再签发新令牌（**判定放在这里而不是 `RefreshTokenService.rotate`** —— refresh 本来就要查 `user`，不必让 token 服务反向依赖用户表）；
- 注销主流程在 `UserService.deleteSelf(request)`（`@Transactional`）：验密码 → `UserMapper.markDeleted` 软删 → `RefreshTokenService.revokeAll` → `GuardianRelationMapper.revokeByUserId` 双向置 `REVOKED`；
- `GET /users/me` 对已注销账号返回 **40101**（原来是 40401：账号行还在，只是不可用了）。

> ⚠️ **口径修正（实现时确认）**：本节草稿曾写"`AuthInterceptor` 识别 `DELETED` → 40101"，它与上方边界用例"注销后旧 access **2 小时内仍可用**"互相矛盾。实现**采用边界用例的口径，不改拦截器** —— 保持 JWT 无状态，不为每个请求多一次 `user` 表查询（若将来要立刻失效，再引入 access 黑名单并按 `userId` 查一次，届时需给所有 `@WebMvcTest` 切片补 `UserMapper` 打桩）。

**小程序侧落地（V15，`feat/mp-auth`）**：

- **登录态编排在 `src/store/auth.js`**：`login` / `register` / `logout` / `refreshAuth` / `restoreSession` / `isLoggedIn`；`src/store/token.js` 仍是 **token 的唯一读写入口**，用户资料缓存在 `src/store/user.js`，**不存在第二份 token**；
- **被动刷新（唯一刷新时机）**：业务请求拿到 `40101` → 请求层把错误交给 mp-auth 的处理器 → 调 `/auth/refresh` → **成功则用新 token 自动重发一次原请求**，调用方无感知；**并发只刷一次**（共享同一个 Promise，refresh 是轮换制，并发刷新会互相作废）；refresh 也失败才清凭据 + `reLaunch` 登录页。**不做"解析 JWT `exp` 提前刷新"**（JWT 结构未进文档，且被动刷新已覆盖全部场景）；
- **`/auth/refresh` 请求必须带 `noAuthRefresh` 语义**：小程序侧用请求层内部选项 `noAuthRetry: true`，否则 refresh 自己返回 `40101` 会再触发一次刷新 → 递归；登录 / 注册同样带该选项；
- **登录 / 注册接口前端传 `silent`**：`40102` 由页面渲染成表单内的红字提示（请求层再 toast 一次会重复）；
- **路由守卫**：`uni.addInterceptor` 拦截 `navigateTo` / `redirectTo` / `reLaunch` / `switchTab`，登录页与注册页白名单放行，其余无 token 时带 `redirect` 参数跳 `POST /auth/login` 对应的登录页；**回跳只接受 `pages/` 开头的站内路径**（`redirect` 来自 URL 参数，防开放重定向）；
- **启动校验**：`App.vue` 的 `onLaunch` 调 `setupAuth()`，有 token 就拉一次 `/users/me` —— 顺带完成**冷启动静默续期**（隔天打开时 access 已过期、refresh 仍有效，用户无感知）；
- **未做**：微信登录（见上）、密码前端 SHA-256 预哈希（等 HTTPS）、前端主动解析 token 过期时间。

**后端侧落地（`feat/backend-auth`）**：

- **令牌**：HS256 签名，claims 为 `sub=userId`、`role`、`typ`（`ACCESS` / `REFRESH`，**两者必须区分**，拿 refresh 当 access 用一律拒绝）、`jti`、`iat`、`exp`；access **7200 秒**、refresh **604800 秒**，由 `medbox.jwt.*` 配置，密钥走 `MEDBOX_JWT_SECRET`（≥32 字节，**改了它已签发的 token 全部失效**）。解析失败 / 过期 / 类型不符 / 被篡改一律 **40101**。
- **refresh 轮换（落库）**：每枚 refresh token 在 `refresh_token` 表留一行（只存 SHA-256 哈希，`token_id` = JWT 的 `jti`，见 06 的 2.13）。换新时旧行置 `ROTATED` 并指向接替者；**再次使用已 `ROTATED` / `REVOKED` 的 token 视为泄漏** —— 撤销该用户全部 refresh token 并返回 40101。
- **密码**：BCrypt 单向哈希存 `user.password_hash`；登录失败（账号不存在 / 密码错误）统一 **40102**，且"账号不存在"时也会白算一次 BCrypt 做耗时对齐，避免被用来枚举已注册手机号。**已注销账号同样算"不存在"**（40102）。
- **注册校验**：`role` 只取 `ELDER` / `GUARDIAN`（其它值 40001），密码 **6~64 位**，手机号 11 位；手机号 / 用户名已存在返回 **40001**，提示取后端 `message`（如"手机号已注册"）。
- **幂等**：仅 `POST /auth/register` 生效 —— 同一 `X-Request-Id` 重复提交只创建 1 个账号并回放首次响应；同号但请求体指纹不同 → 40001。登录 / 刷新天然幂等，不落幂等记录（免得把含 token 的响应快照写进库）。快照 TTL 24 小时（`medbox.idempotency.ttl-hours`）。
- **登录校验范围**：`/api/v1/**` 需带 `Authorization: Bearer {access token}`，`/api/v1/auth/**` 与 `/actuator/**` 放行；**登录失败一律 40101**（40102 只用于账号密码错误）。本分支只做校验顺序的第 ① 步"是否登录"，监护关系与角色权限见 `feat/backend-authz`。
- **未做**：微信登录、短信验证码、限流（`feat/ai-limit`）；`GET /users/me` 已由 `feat/backend-authz` 交付，落地口径见 1.3.1（《后端侧落地（`feat/backend-authz`）》）。

#### 3.2 监护关系写接口（🧭，`feat/guardian-relation`）

> `feat/backend-authz` 只做了**判定**（读接口的 `require` 校验），没做关系的**创建 / 确认 / 解除**。本节补 4 个写接口，仍是两步绑定：① 监护人申请 → `PENDING`；② 老人确认 → `ACTIVE`，监护人才真正获得读写权限（未确认前访问该老人数据仍返回 **40302**，见 1.3）。这 4 个接口**没有对应 `Permission` 权限位**（判定型写操作），按「账号角色 + 身份」手动校验。

**4 个接口一览**（都挂 `/api/v1/users`，全部【鉴权】）

| 方法 | 路径 | 谁可用 | 作用 |
|------|------|--------|------|
| POST | `/users/{elderId}/guardians` | 监护人（GUARDIAN） | 申请绑定，建 `PENDING` |
| GET | `/users/guardians/pending` | 老人（ELDER） | 我的待确认列表（join 带监护人姓名 + 手机） |
| PATCH | `/users/guardians/{relationId}/accept` | 老人本人 | 确认，置 `ACTIVE` 并指定角色 |
| DELETE | `/users/guardians/{relationId}` | 关系任一方 | 拒绝 / 解除，软删置 `REVOKED` |

**① 申请绑定**

```
POST /users/{elderId}/guardians
Authorization: Bearer {监护人 token}     // 无请求体

// 成功（新建，role 未指定故省略）
{ "code":0, "data":{ "relationId":"r-1001", "elderId":"u-1001",
                     "guardianId":"u-2001", "status":"PENDING", "createdAt":"2026-10-22T08:00:00Z" } }
```

🧭 **规则表：输入 → 判定条件与优先级 → 输出**

| 顺序 | 判定条件 | 输出 | 说明 |
|------|----------|------|------|
| ① | 未登录 / token 无效 | **40101** | AuthInterceptor 兜底 |
| ② | 当前角色 ≠ `GUARDIAN`（老人本人等） | **40301** | 只有监护人能发起申请 |
| ③ | 目标 `elderId` 不是真实老人 | **40301** | `AccessService.requireElder`，防枚举 |
| ④ | 无既有关系 | `code=0` 建 `PENDING` | `relationId` 由服务端序列生成 |
| ⑤ | 已有 `PENDING` / `ACTIVE` | **40901** | 重复申请 |
| ⑥ | 已有 `REVOKED` | `code=0` 改回 `PENDING` | 重新申请，**清空 `role`**（`updateById` 忽略 null，走显式 SQL） |

**② 待确认列表**

```
GET /users/guardians/pending

// 响应
{ "code":0, "data":[ { "relationId":"r-1001", "elderId":"u-1001", "guardianId":"u-2001",
                       "guardianName":"张三", "guardianPhone":"13800001234", "createdAt":"..." } ] }
```

| 顺序 | 判定条件 | 输出 |
|------|----------|------|
| ① | 未登录 | **40101** |
| ② | 当前角色 ≠ `ELDER` | **40301** |
| ③ | 通过 | `code=0` 返回本人 `PENDING` 关系列表，join `user` 带监护人姓名 + 手机，按 `createdAt` 升序 |

**③ 确认（置 ACTIVE）**

```
PATCH /users/guardians/{relationId}/accept
{ "role":"FAMILY" }        // 必填，FAMILY / DOCTOR / NURSE 三选一

// 成功
{ "code":0, "data":{ "relationId":"r-1001", "elderId":"u-1001", "guardianId":"u-2001",
                     "role":"FAMILY", "status":"ACTIVE", "createdAt":"..." } }
```

| 顺序 | 判定条件 | 输出 | 说明 |
|------|----------|------|------|
| ① | 未登录 | **40101** | |
| ② | 当前角色 ≠ `ELDER` | **40301** | |
| ③ | `role` 缺失 / 空白 / 超长（`@Valid`）或非法值（`parse` 失败） | **40001** | 只能 FAMILY / DOCTOR / NURSE |
| ④ | `relationId` 查不到 | **40301** | 防枚举，不回 40401 |
| ⑤ | `relation.elderId` ≠ 当前老人 | **40301** | 只能确认发给自己的 |
| ⑥ | 关系已非 `PENDING` | **40901** | 只允许 PENDING→ACTIVE |
| ⑦ | 通过 | `code=0` 置 `ACTIVE` 并落角色 | |

**④ 拒绝 / 解除**

```
DELETE /users/guardians/{relationId}

// 成功（无 data）
{ "code":0 }
```

| 顺序 | 判定条件 | 输出 | 说明 |
|------|----------|------|------|
| ① | 未登录 | **40101** | |
| ② | `relationId` 查不到 | **40301** | 防枚举 |
| ③ | 当前用户既非关系里的老人、也非监护人 | **40301** | 只能任一方解除 |
| ④ | 通过 | `code=0` 软删置 `REVOKED` | 已是 `REVOKED` 则幂等跳过 |

**边界用例**

| 用例 | 结果 |
|------|------|
| 申请后、确认前，监护人访问该老人数据 | **40302**（关系仍 PENDING，见 1.3） |
| 解除后再申请 | 成功，`REVOKED` → `PENDING`（`role` 清空，走重申请分支） |
| 陌生人对 `relationId` 做 accept / delete | **40301**（与"查不到"同码，防枚举） |
| 老人 / 监护人注销 | 双向关系置 `REVOKED`（见 3.1 注销后的数据处理） |

**落地（`feat/guardian-relation`）**：

- `GuardianRelationService` 4 个方法 `apply` / `pendingList` / `accept` / `revoke`（`@Transactional`），`findById` 查不到一律 **40301**；
- `GuardianRelationMapper` 新增 `reactivatePending` 用显式 `@Update`（`SET role=NULL`），因 `updateById` 忽略 null 字段；`nextRelationId()` / `revokeByUserId()` 已在 `feat/backend-authz` / `feat/backend-user-delete` 建好；
- `AccessService` 新增公开 `requireElder(elderId)`（复用私有 `isElder`），目标非老人 40301；
- 3 个 DTO 在 `dto/guardian/`：`GuardianAcceptRequest`（record + `@NotBlank @Size(max=16) role`）、`GuardianPendingResponse`、`GuardianRelationResponse`；
- 集成测试 `GuardianRelationWebIntegrationTest` **16 例全通过**（apply 6 + pending 2 + accept 6 + delete 2；照 MedicineCore 基座：`@SpringBootTest + @AutoConfigureMockMvc` + H2 `@Primary` + `@MockitoBean`）。

**小程序侧落地（`feat/mp-guardian`）**：

- `src/api/guardian.js` 四个函数与上表四条一一对应：申请**无请求体**；accept 请求体 `{ role }` 必填（`FAMILY` / `DOCTOR` / `NURSE`）；解除成功无 `data`。错误码按 1.2 封闭集合兑现，页面 `silent: true` 自绘文案；
- **"输入老人账号"= 老人用户ID（`userId`）**：本节没有"按手机号 / 用户名查找老人"的接口（`GET /users/{elderId}/profile` 归 `feat/user-profile`，也不是查找入口），绑定页输入框即 `elderId`，页面引导监护人向老人索取（老人可在"我的"页面查看自己的用户ID）；
- **"我的申请"是设备本地视图**（`src/store/guardian.js`，storage key `guardianApplications`）：后端只有老人端的 pending 列表，**没有"我发起的申请列表"接口**。监护人发起后本地落一份（申请响应即时写入），`store/auth.js` 的 `clearSession()`（登出 / 续期失败）一并清掉。老人确认 / 拒绝后本地不会自动同步，页面因此把两个动作分开：**撤销**（仅本地 PENDING 时显示，调解除接口，服务端对 `REVOKED` 幂等）与**移除记录**（仅清本地缓存 —— 关系可能已被确认成 `ACTIVE`，误调解除接口会真解除监护）；
- **40302 提示**：`utils/error.js` 已有默认文案"监护关系未生效，请等待老人确认"；页面按 `CODE.GUARDIAN_PENDING` 自绘，不重复 toast（同 plan 页既有写法）；
- 老人在确认弹窗里单选 `FAMILY` / `DOCTOR` / `NURSE`（每选一项显示该角色的一句权限说明，口径取 1.3 权限矩阵），未选择时确认键禁用；拒绝经二次确认后走解除接口。

**小程序侧落地（`feat/mp-profile`）**：

- **个人页数据源**：`pages/profile/profile.vue` 渲染 `store/user.js` 的缓存（即 `GET /users/me` 的结果）；`onShow` 时**缓存缺 `name` / `phone` 才静默拉一次** `/users/me` 补齐（`utils/profile.js` 的 `needsProfileRefresh` 判定 —— 登录时 `getMe` 失败会留半份缓存；TabBar 页 `onShow` 频繁，缓存满时零请求）。`api/auth.js` 的 `getMe(options)` 因此加了可选 options（传 `silent: true`，背景刷新失败只记日志不弹 toast）
- **资料字段展示**：`{userId, role, name, phone, username}` 全字段进「个人资料」卡；null 字段按全局 non_null 省略，统一经 `profileFieldText` 兜底为"未设置"；hero 姓名三级兜底 姓名 → 用户名 → "未设置姓名"
- **角色入口分化**（08 验收"不同角色显示不同入口"）：`utils/permission.js` 的 `ENTRY_ROLES` 登记两项 —— `alarmSettings=[GUARDIAN]`（「监护台」卡；**顺带修**：白名单此前为空，该卡片实际对老人可见，与页面注释不符）、`elderBindGuide=[ELDER]`（老人端「告诉监护人」绑定引导卡，落实 3.2 口径"老人可在'我的'页面查看自己的用户ID"）
- **`/users/{elderId}/profile` 唯一消费点**：`pages/guardian/guardian.vue`「我的申请」卡按 elderId 拉老人姓名，展示"张桂英（u-1001）"；**40302（申请中未生效）/ 40301（被拒 / 无关系）/ 网络失败一律静默回退 elderId 原文**（`elderNameText`）—— 姓名是增强信息，不 toast、不遮罩、不阻塞列表
- **未做（有意）**：plan / record 页老人选择器的姓名化（可复用同一接口，控范围留待后续）；资料编辑（02 第 3 章无 `PATCH /users/me`，后端定了再说）

## 4. 设备管理（命令实际经 MQTT 下行）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /devices/register | 【鉴权】药箱注册并绑定到老人（**本期注册即生效**，不做准入审核；传感器子设备随主控一起登记，无需单独注册） |
| GET | /devices | 【鉴权】我监护的老人设备列表（含在线状态） |
| GET | /devices/{deviceId} | 【鉴权】设备详情与最新遥测 |
| GET | /devices/{deviceId}/status | 【鉴权】实时状态（在线/仓门） |
| POST | /devices/{deviceId}/commands | 【鉴权】下发控制命令（蜂鸣/校准/解锁/重启），转 MQTT QoS1 |
| GET | /devices/{deviceId}/events | 【鉴权】设备事件流水（上下线、心跳异常） |
| GET | /devices/{deviceId}/sensors | 【鉴权】该药箱下的传感器子设备列表（温湿度 / 光照 / 摄像头，含各自在线状态） |

> `deviceId` 指**药箱主控**（`device_type=MAIN`）；传感器 / 摄像头是其子设备，通过 `parent_device_id` 关联、在 `GET /devices/{deviceId}/sensors` 中列出（见文档 06 的 2.3）。子设备无独立 MQTT 连接，其在线状态由主控周期上报（见文档 03 第 3 章 `up/sensor/heartbeat`）。

#### 4.0 后端落地（`feat/device-core`）

本节的**注册 + 五个 GET**（列表 / 详情 / 状态 / 事件流水 / 子设备列表）由 `feat/device-core` / `feat/device-sensor` 落地；`POST /devices/{id}/commands` 由 `feat/device-command` 落地（见 4.0.1）。

**谁可以注册**：`POST /devices/register` 是**监护人代老人**登记设备 —— 走 `require(elderId, WRITE_DEVICE)`，**只 FAMILY 监护人具备该权限**。老人本人（`SELF`）、医生、护理一律 40301（见 1.3 权限矩阵）。设备端**不做无 Token 自注册**，仍走 JWT。

**示例：注册**

```
POST /devices/register
{ "deviceId":"BOXA-DEBUG-01", "elderId":"u-1001",
  "sensors":[{"deviceId":"BOXA-DEBUG-01-S-TH01","deviceType":"TEMP_HUMI"},
             {"deviceId":"BOXA-DEBUG-01-S-CAM01","deviceType":"CAMERA"}] }

// 响应（注册即生效；onlineState 恒为 OFFLINE —— 真正的在线要等设备连上 EMQX）
{ "code":0, "data":{
    "deviceId":"BOXA-DEBUG-01", "deviceType":"MAIN", "elderId":"u-1001",
    "productKey":"medbox-box", "timezone":"Asia/Shanghai", "onlineState":"OFFLINE",
    "registeredAt":"2026-10-13T01:00:00Z",
    "sensors":[{"deviceId":"BOXA-DEBUG-01-S-TH01","deviceType":"TEMP_HUMI"},
               {"deviceId":"BOXA-DEBUG-01-S-CAM01","deviceType":"CAMERA"}] } }
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `deviceId` | 是 | 药箱主控编号，同时是 MQTT 的 clientId；4~64 位字母、数字、下划线或短横线 |
| `elderId` | 是 | 归属老人 |
| `productKey` | 否 | Topic 第 2 层；留空取 `medbox.device.default-product-key` |
| `timezone` | 否 | 设备本地时区（服药计划按此换算）；留空取 `Asia/Shanghai`，非法时区 40001 |
| `sensors[].deviceType` | 否 | **只能是 `TEMP_HUMI` / `LIGHT` / `CAMERA`**，填 `MAIN` 报 40001 |

**错误码口径**（沿用 1.2 / 1.3，不新增码值）：

| 场景 | 结果 |
|------|------|
| 未登录 / Token 失效 | 40101 |
| 老人本人、医生、护理注册 | 40301（`WRITE_DEVICE` 仅 FAMILY） |
| 监护关系 PENDING | 40302 |
| **设备编号已登记（含子设备）** | **40901** —— `device_id` 唯一，不静默覆盖、不改绑。**换绑老人走后续独立接口，本分支不做** |
| 编号格式非法 / 编号重复 / 子设备类型为 MAIN / 时区非法 / `page` 或 `size` < 1 | 40001 |
| **查询他人设备，或设备不存在** | **一律 40301** —— 与 1.2 第 ② 条同口径，不返回 40401（防设备编号枚举） |
| 传**子设备编号**进 `/devices/{id}`、`/status`、`/events` | 40301（这些接口的 `deviceId` 只接受主控） |

**事件流水 `GET /devices/{deviceId}/events`**：分页返回，`page` 从 1 起，不传即用 `medbox.device.event-page-size`（20）；`size` 超过 `event-max-page-size`（100）静默取上限；`page`/`size` 小于 1 报 40001。按 `created_at` **倒序**（最新在前）。数据源是新增的 `device_event` 表（见文档 06 的 2.16），**本分支只写入 `REGISTERED` / `ONLINE` / `OFFLINE` 三类**，`FAULT` / `COMMAND` 由 `feat/device-fault`、`feat/device-command` 续写。

**在线状态只写"跃变"**：主控每 60s 有心跳，若每次上行都写一条 `ONLINE` 事件，单台设备一天就是 1440 条噪声。故只有状态真的变了才落流水（同时推 `DEVICE_STATUS` 帧，见文档 04 第 4 章跨分支约定）。

**两处刻意留空（待后续分支补，接口结构不动）**：

- 表格里 `GET /devices/{deviceId}` 的**最新遥测**：读数在 `env_sample` 表，归 `feat/env-core`；本分支不提前引那张表（引了也查不出数据），届时在响应里加字段即可；
- 表格里 `GET /devices/{deviceId}/status` 的**仓门状态**：设备侧尚未上报、`device` 表也无该列，等上报后再补。

**示例：下发控制命令**

```
POST /devices/BOXA-DEBUG-01/commands
{ "cmd":"BUZZ", "params":{"durationSec":5} }

// 响应（异步下发，返回受理）
{ "code":0, "data":{ "commandId":"c-7f21", "state":"PENDING" } }
```

#### 4.0.1 命令下发落地（`feat/device-command`，已交付）

**请求体**：`cmd`（必填，`BUZZ` / `UNLOCK` / `REBOOT` / `CALIBRATE`）+ `params`（可选 Map，如蜂鸣的 `durationSec`）。
**请求体不收 `operator`** —— 操作人由服务端取鉴权用户落 `device_command.operator_id` 留痕，客户端多传的 `operator` 字段被忽略（不信客户端自报身份）。

**判定表（顺序即优先级）**：

| 场景 | 结果 |
|------|------|
| 未登录 | 40101 |
| 设备不存在 / 越权 / 传**子设备编号** / 老人本人 | **40301**（`WRITE_DEVICE` 仅 FAMILY 监护人；不区分不存在与无权限，防编号枚举） |
| 监护关系 PENDING | 40302 |
| `cmd` 不在白名单 | 40001 |
| **设备离线（`online_state != ONLINE`）** | **40901** —— 小程序按此码提示"设备离线，无法下发" |
| MQTT 未启用 | 50000（**不落库**：命令是用户主动的同步操作，悬挂 PENDING 没意义） |
| 受理成功 | 200，`{commandId, state:"PENDING"}`，异步等回执 |

**终态流转**：受理落 `device_command`（PENDING）→ 转 MQTT `down/command`（QoS1、信封带 `msgId` + `needAck:true`，回填 `msg_id` 列）→ 设备回执 `up/ack` 或 5s 超时重发 3 次仍无回执 → 行翻 `SUCCESS` / `FAILED` 终态（更新条件带 `state='PENDING'`，迟到/重复回执幂等）。**发布当场抛异常**（如 broker 断连）→ 行置 FAILED 留痕 + 50000。

**COMMAND 事件契约**：每次下发写一条 PENDING 事件、翻终态再写一条，`detail` 为 JSON `{"commandId","cmd","state"[,"reason"]}`（见 06 的 2.16 / 2.17）。**不生成告警、无命令查询接口、无 WS 帧** —— 前端刷 `GET /devices/{id}/events` 观察终态（小程序侧已按此对接，见下方落地注）。

> **小程序侧落地（`feat/mp-device-extra`；后端 `device-command` 已落地（4.0.1），`device-fault` 在 07 仍为 `[ ]` 未落地、按契约 + mock 先行）**：
>
> 1. **命令接口**：`api/device.js` 的 `sendDeviceCommand(deviceId, cmd, params, operator)` 打 `POST /devices/{id}/commands`；`cmd` ∈ `BUZZ` / `UNLOCK` / `CALIBRATE` / `REBOOT`，蜂鸣带 `params.durationSec`，`operator` 传当前用户 ID 作留痕；写操作自动注入 `X-Request-Id`。
> 2. **命令终态观察口径（重要）**：后端**没有**命令状态查询接口，也**没有**命令相关的 WebSocket 推送帧（WS 事件只有 ALARM / DEVICE_STATUS / REMINDER / AI_STREAM / PONG / RESYNC），故前端下发后展示 `PENDING`，并刷新 `GET /devices/{id}/events` 读取由回执产生的 **COMMAND 事件**（其 `detail = { commandId, cmd, state }`，`state` ∈ `PENDING`/`SUCCESS`/`FAILED`）来反映 `PENDING→成功/失败`。**不自行发明轮询或命令 WS 帧**，后端 device-command 落地后若新增查询/推送，再在 `utils/device.js` 一处收紧。
> 3. **设备离线 40901**：前端收到 `40901`（前端 `CODE.CONFLICT`）时提示"设备离线，无法下发"；其余错误用后端 `message` 自绘，不另弹窗。
> 4. **权限**：命令属 `WRITE_DEVICE`（02 的 1.3 矩阵仅 `FAMILY`），设备详情页「远程控制」卡用 `RoleGate entry="deviceCommand"` 包裹（`utils/permission.js` 登记 `deviceCommand:[GUARDIAN]`），老人端不可见；事件流水属 `READ_DEVICE`，老人与监护人都可读，「设备事件」卡与独立分页页 `pages/device/events.vue` 双方可见。
> 5. **事件接口**：`api/device.js` 的 `getDeviceEvents(deviceId, {page,size})` 打 `GET /devices/{id}/events`；分页、按 `created_at` 倒序、`page`/`size` < 1 报 40001；`utils/device.js` 的 `parseEventsPage` 容错 `{list,total}` / `{records,total}` / `{items,total}` / 裸数组，`toEventView` 归一化（FAULT 的 `detail` 为故障码 → 标签；COMMAND 的 `detail` 解析 `commandId`/`cmd`/`state`）。**心跳异常 = OFFLINE 事件**（心跳超时落），**设备侧异常 = FAULT 事件**（`DOOR_TIMEOUT` / `CAMERA_FAILED` / `HW_FAULT`）。
> 6. **mock 先行**：`tools/mock` 补 `GET /events`（倒序分页、40301 他人/不存在/子设备编号）与 `POST /commands`（在线→PENDING 并同步落 COMMAND 事件模拟设备 ack；离线→40901；非法 cmd→40001；仅监护人），并播种 REGISTERED/ONLINE/OFFLINE + 样例 FAULT/COMMAND 事件；真实 FAULT 摄入走后端 MQTT `up/event/error`，mock 不接 EMQX，只给样例使列表可读。

### 4.1 设备抓拍图片（摄像头留证，已读 7 天 / 未读 30 天过期）

摄像头识别结果的**结构化数据走 MQTT**（见文档 03 第 4 章）；**图片单独走 HTTP multipart 直传后端**。

> **当前摄像头默认全量抓拍：正常服药也拍、每次服药都拍**，后端按全量接收。摄像头侧的算法与抓拍策略不由后端 / 小程序团队负责，后端只负责"收下并管好"。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /devices/{deviceId}/captures | 【**设备鉴权**】摄像头上传抓拍图片（multipart），返回 `captureId` 与 `expireAt`。`deviceId` 填**摄像头子设备自己的 deviceId**，后端按 `parent_device_id` 反查所属药箱与老人 |
| GET | /captures?elderId=&reason=&from=&to= | 【鉴权】抓拍列表（仅元数据，不含文件流） |
| GET | /captures/{captureId} | 【鉴权】抓拍详情 |
| GET | /captures/{captureId}/file?token= | 【鉴权】读取图片流（供小程序 `<image src>` 使用，校验监护关系） |
| DELETE | /captures/{captureId} | 【鉴权·监护人】手动提前删除 |

**示例：摄像头上传抓拍**

```
POST /devices/BOXA-DEBUG-01-S-CAM01/captures     // 摄像头子设备自己的 deviceId
Content-Type: multipart/form-data
file: capture.jpg
reason=WRONG_DRUG & medicineId=m-205 & planId=p-3301
& confidence=0.62 & capturedAt=2026-10-04T00:04:00Z

// 响应
{ "code":0, "data":{ "captureId":"cap-8801","expireAt":"2026-10-11T00:04:00Z" } }
```

**约定**：

- **`reason` 取值**：`NORMAL` 正常服药抓拍（**当前占绝大多数**，摄像头全量拍）、`WRONG_DRUG` 错服、`LOW_CONFIDENCE` 置信度低于阈值、`MANUAL` 监护人主动请求。列表接口可按 `reason=WRONG_DRUG` 过滤只看异常。
- **去重规则（已确定）**：同一服药事件**只保留 1 张**。后端按 `planId + capturedAt` **60 秒时间窗**去重，窗口内的重复抓拍只保留第一张（摄像头侧也应尽量只拍 1 张）。
- **上传侧鉴权走设备身份，不走用户 JWT（已确定）**：设备侧用**一把共用密钥**（写在固件与后端配置里，不入库）做 HMAC 签名，Header 携带 `X-Device-Id` / `X-Device-Sign` / `X-Timestamp`，其中 `sign = HMAC_SHA256(sharedSecret, deviceId + timestamp)`；后端校验签名并校验**时间窗 ±5 分钟**（防重放）。上传接口只允许写入该设备所属药箱绑定的老人。**不做"只校验 `X-Device-Id`"的退化方案**。MQTT 通道本期是匿名的，与此处的 HTTP 上传鉴权相互独立。
- **下载侧鉴权**：必须是**老人本人**或其 **ACTIVE 监护人**，其余角色（护理 / 医生）一律 40301 —— 比 1.3 的默认规则更严格。
- **为什么用 query 传 token**：小程序 `<image src>` 无法携带自定义 Header，故用后端签发的**短期 `fileToken`**（5 分钟有效）拼接在 URL 上；**不要直接把长期 JWT 放 URL**（会落进日志与浏览器历史）。
- **过期清理（已读 / 未读分开算）**：`expire_at = MIN(captured_at + 30 天, viewed_at + 7 天)` —— **未读最长保留 30 天**，**首次查看后再保留 7 天**（查看只会让过期提前，不会延长）。首次调用 `/captures/{id}/file` 读取图片流时后端自动写入 `viewed_at` 并重算 `expire_at`；天数可配 `medbox.capture.retention-days-unread`（30）/ `retention-days-read`（7）。后端每日定时任务删除到期文件与记录，监护人可手动提前删除。
- **存储**：图片存后端主机本地目录，不落库、不暴露真实路径（表结构见文档 06 的 2.12）；落盘路径 `{dir}/{YYYY-MM-DD}/{captureId}.{ext}`，`file_path` 入库但**绝不出现在任何响应里**。`{ext}` 由上传 `Content-Type` 推导（仅 `jpg`/`png`/`webp`，其余兜底 `jpg`），**不使用原始文件名**（防路径穿越）。

#### 4.1.1 上传侧鉴权（设备身份，HMAC，不走 JWT）

`POST /devices/{deviceId}/captures` 从 `AuthInterceptor` 排除，由本类自带设备鉴权。设备侧用一把**共用密钥**（写在固件与后端配置 `medbox.capture.upload-secret` 里，不入库、走 `MEDBOX_CAPTURE_UPLOAD_SECRET` 环境变量覆盖）做 HMAC 签名，携带三个 Header：

| Header | 含义 |
|--------|------|
| `X-Device-Id` | 摄像头子设备自己的 `deviceId`，**必须与路径 `deviceId` 完全一致**（否则 40001） |
| `X-Device-Sign` | `HMAC_SHA256(secret, deviceId + timestamp)`，**十六进制小写** |
| `X-Timestamp` | Unix **秒**，与服务器时钟差值绝对值 **≤ 5 分钟**（防重放） |

后端校验：① 签名正确（伪造 / 密钥不符 → **40101**）；② 时间戳在 ±5 分钟窗口内（超出 → **40101**）；③ `X-Device-Id == 路径 deviceId`（否则 40001）。**不做"只校验 `X-Device-Id`"的退化方案**。校验通过后后端按 `parent_device_id` 反查所属药箱与老人，只允写入该设备所属老人。

```bash
curl -s -X POST http://192.168.1.10:8080/medbox/api/v1/devices/BOXA-DEBUG-01-S-CAM01/captures \
  -H "X-Device-Id: BOXA-DEBUG-01-S-CAM01" \
  -H "X-Device-Sign: $(printf '%s%s' BOXA-DEBUG-01-S-CAM01 1759474540 | openssl dgst -sha256 -hmac '<upload-secret>' -hex | awk '{print $2}')" \
  -H "X-Timestamp: 1759474540" \
  -F "file=@capture.jpg" -F "reason=WRONG_DRUG" -F "medicineId=m-205" \
  -F "planId=p-3301" -F "confidence=0.62" -F "capturedAt=2026-10-04T00:04:00Z" | jq
```

#### 4.1.2 接口契约（5 个端点）

**① 上传（设备 HMAC）** `POST /devices/{deviceId}/captures`　multipart/form-data

| 字段 | 必填 | 说明 |
|------|------|------|
| `file` | 是 | 图片二进制 |
| `reason` | 否 | `NORMAL` / `WRONG_DRUG` / `LOW_CONFIDENCE` / `MANUAL`，缺省 `NORMAL`，非法值 40001 |
| `medicineId` | 否 | 关联药品 |
| `planId` | 否 | 关联服药计划；**非空时才触发去重** |
| `confidence` | 否 | 置信度（BigDecimal，如 `0.62`） |
| `capturedAt` | 否 | ISO-8601 UTC，缺省取服务器当前 UTC |

响应（去重命中时返回既有记录的 `captureId`/`expireAt`，本次文件流丢弃不落盘）：
```json
{ "code":0, "data":{ "captureId":"cap-1001", "expireAt":"2026-11-03T00:04:00Z" } }
```

**② 列表（仅元数据）** `GET /captures?elderId=&reason=&from=&to=&page=&size=`

| Query | 必填 | 说明 |
|-------|------|------|
| `elderId` | 是 | 目标老人；走 `READ_CAPTURE`（老人本人 + FAMILY） |
| `reason` | 否 | 按 `reason` 过滤（如只看 `WRONG_DRUG`） |
| `from` / `to` | 否 | `capturedAt` 时间区间（ISO-8601 UTC） |
| `page` / `size` | 否 | 默认 1 / 20，**上限 200**（图片元数据体积小，比通用约定 100 放宽） |

响应：`R<PageResult<CaptureResponse>>`，`list` 中每条字段见 ④；分页字段 `total`/`page`/`size`。

**③ 详情** `GET /captures/{captureId}` → `R<CaptureResponse>`

**④ 抓拍元数据字段（`CaptureResponse`，②/③ 共用）**
```json
{ "captureId":"cap-1001", "deviceId":"BOXA-DEBUG-01-S-CAM01", "elderId":"u-1001",
  "medicineId":"m-205", "planId":"p-3301", "reason":"WRONG_DRUG",
  "mime":"image/jpeg", "sizeBytes":48213, "confidence":0.62,
  "capturedAt":"2026-10-04T00:04:00Z", "viewedAt":null, "expireAt":"2026-11-03T00:04:00Z",
  "fileToken":"<5分钟短期令牌>" }
```
`viewedAt` 未读为 `null`；`fileToken` 供小程序 `<image src>` 拼接 ⑤ 使用（每次取详情/列表都会重新签发，**5 分钟有效**）。

**⑤ 读取图片流** `GET /captures/{captureId}/file?token=` → **直接返回二进制**（`Content-Type` 为图片、`Cache-Control: no-store`），**不走统一响应体包裹**。首次读取自动写 `viewed_at` 并据"查看后过期公式"重算 `expire_at`。

**⑥ 删除（仅 FAMILY）** `DELETE /captures/{captureId}` → `R<Void>`；走 `WRITE_CAPTURE`（仅 FAMILY，老人本人 / 护理 / 医生 40301）。删库行 + 尽力删磁盘文件（文件已被清理任务清掉也不算失败）。

#### 4.1.3 错误码与边界（不新增码值，沿用 1.2 / 1.3）

| 端点 | 场景 | code | HTTP |
|------|------|------|------|
| ① 上传 | HMAC 签名错 / 时间戳超 ±5min / `X-Timestamp` 非法 | 40101 | 401 |
| ① 上传 | `X-Device-Id ≠ 路径 deviceId` / `file` 空 / `reason` 非法 / 缺参 | 40001 | 400 |
| ① 上传 | 设备未注册或父设备缺失（解析不出老人） | 40301 | 403 |
| ① 上传 | 文件落盘 IO 失败 | 50000 | 500 |
| ②/③ 列表/详情 | 未登录 / token 失效 | 40101 | 401 |
| ②/③ 列表/详情 | 无关系 / NURSE/DOCTOR / 老人看他人 | 40301 | 403 |
| ②/③ 列表/详情 | 监护关系 PENDING | 40302 | 403 |
| ③ 详情 | `captureId` 不存在 | 40401 | 404 |
| ⑤ 文件流 | `token` 缺失 / 格式非法 / `token.captureId ≠ 路径` | 40001 | 400 |
| ⑤ 文件流 | `token` 签名无效 / 已过期 | 40101 | 401 |
| ⑤ 文件流 | `captureId` 不存在 / 磁盘文件已被清理 | 40401 | 404 |
| ⑤ 文件流 | 监护关系不符（二次复核，令牌泄漏也兜底） | 40301 / 40302 | 403 |
| ⑥ 删除 | `captureId` 不存在 / 已被清理 | 40401 | 404 |
| ⑥ 删除 | 老人本人 / NURSE / DOCTOR 调用 | 40301 | 403 |
| ⑥ 删除 | FAMILY 但监护关系 PENDING | 40302 | 403 |

> **去重规则（实现确定）**：`planId` 非空时，按 `|capturedAt − 已有 capturedAt| ≤ 60s` 判定重复，命中只保留第一张（返回既有 `captureId`/`expireAt`）；`planId` 为空不去重。
>
> **fileToken 结构（供调试 / 联调理解，小程序无需解析）**：`base64url(payload) + "." + base64url(HMAC(fileTokenSecret, payload))`，`payload = captureId + "|" + 过期秒`；默认 TTL 5 分钟（`medbox.capture.file-token-ttl-minutes`），密钥走 `MEDBOX_CAPTURE_FILE_TOKEN_SECRET` 覆盖。令牌只证明"曾有权读取"，文件端点仍二次走 `AccessService.require(READ_CAPTURE)` 复核监护关系。

## 5. 药品与库存管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /medicines | 【鉴权】新建/更新药品档案（含禁忌、储存条件）；不传 `ownerElderId` 即公共字典 |
| GET | /medicines | 【鉴权】药品字典 / 我的药品列表（分页、搜索）；可见范围 = **公共药品（`ownerElderId` 为空）+ 我监护老人的私有药品** |
| GET | /medicines/{medicineId} | 【鉴权】药品详情 |
| DELETE | /medicines/{medicineId} | 【鉴权】删除药品档案（**无任何药箱库存引用时才允许**，否则 40901） |
| GET | /devices/{deviceId}/stocks | 【鉴权】药箱库存清单（按药品：数量、生产日期、有效期） |
| PUT | /devices/{deviceId}/stocks/{medicineId} | 【鉴权】录入 / 更新某药品库存：数量、**生产日期与有效期（手动录入）** |
| POST | /devices/{deviceId}/stocks/{medicineId}/in | 【鉴权】补药入库（增加库存，可同时更新生产日期 / 有效期） |
| DELETE | /devices/{deviceId}/stocks/{medicineId} | 【鉴权】移除该药品库存 |
| GET | /devices/{deviceId}/expiring | 【鉴权】临期/过期清单（提前 N 天预警） |

> **药箱不划分仓位 / 格子**：库存按"药箱 + 药品"管理（表 `medicine_stock`，见文档 06 的 2.5）；传感器则按**子设备**细分（`device.device_type` + `parent_device_id`），见文档 06 的 2.3。

> **小程序侧落地口径（`feat/mp-medicine`；后端 `medicine-core` 未落地，先行约定）**：
>
> 1. **新建与更新同走 `POST /medicines`**：更新时请求体带 `medicineId`，创建不带（由后端生成）；`ownerElderId` **不传即公共字典**，传某老人 `userId` 即其私有药品。可见范围（公共 + 我监护老人的私有）由后端按 1.3.1 的 `visibleElderIds()` **行级过滤**，小程序不本地过滤。
> 2. **分页响应形状**：`GET /medicines` 按 1. 通用约定收 `page` / `size`、返回 `total`；**列表数组字段名暂定 `list`**（小程序 `parseMedicinePage` 容错 `records` / `items`）。后端 `feat/medicine-core` 定稿后二选一收紧，并同步更新本注释与 `medbox-spec/CHANGELOG.md`。
> 3. **删除 40901**：药品被任一药箱库存引用（`medicine_stock.medicine_id` 存在记录）时拒绝删除；小程序在删除确认弹窗内提示"该药品已被药箱库存引用，无法删除"。
> 4. **私有药品的归属选择**：P1 `feat/mp-guardian` 前小程序没有"我监护的老人列表"接口，暂从 `GET /devices` 列表项的 `elderId` 归纳候选老人；`mp-guardian` 上线后改调老人列表接口。
> 5. **写权限**：`POST` / `DELETE` 仅 `WRITE_MEDICINE`（02 的 1.3 矩阵，仅 FAMILY）；小程序据此只给监护人显示新建 / 编辑 / 删除入口，老人端只读，服务端仍强校验。

> **库存四条 · 小程序侧口径（`feat/mp-stock`；后端 `medicine-core` 未落地，先行约定）**：
>
> 1. **响应形状**：`GET /devices/{deviceId}/stocks` 暂按**裸数组**返回（小程序 `parseStocks` 容错 `{list}` / `{records}` / `{items}`）；行级字段即 06 的 2.5（`stockId` / `medicineId` / `quantity` / `productionDate` / `expiryDate` / `updatedAt`），**不内嵌药品摘要** —— 名称 / 规格 / 单位由小程序拉药品字典按 `medicineId` join。后端若改为内嵌摘要，只改小程序 `utils/stock.js` 的 `joinStockMedicine` 一处。
> 2. **请求体字段（camelCase，与 06 列名对应）**：PUT 录入/更新与 POST `/in` 补药入库均为 `{ quantity, productionDate?, expiryDate? }`；**PUT 的 `quantity` 为绝对值（覆盖写）**，**POST `/in` 的 `quantity` 为增量**；两个日期均为 `YYYY-MM-DD`（DATE 列，选填，不传即不更新该列）。
> 3. **设备维度错误码**：沿用第 4 章 device-core 口径 —— 查询 / 操作他人设备与设备不存在**一律 40301**（不返回 40401）；数量非法等参数错误 40001。
> 4. **日期无时区换算**：`productionDate` / `expiryDate` 是 DATE（无时刻），小程序用 `picker mode="date"` 直接收发 `YYYY-MM-DD`，**不走 1. 通用约定的 UTC 口径**（该口径的例外只有计划 `times`）。
> 5. **`GET /devices/{deviceId}/expiring`（临期/过期清单）归 P1 `feat/mp-expiring`**，`feat/mp-stock` 不实现；库存清单页的临期 / 过期标签是**前端本地展示**（复用 `datetime.expiryState`，默认 30 天；老人级 `alarm-settings` 提前期归 P1 `feat/mp-alarm`）。
> 6. **写权限**：四条均属 `WRITE_MEDICINE`（02 的 1.3 矩阵，仅 FAMILY）；小程序据此只给监护人显示录入 / 补药 / 移除入口，老人端只读，服务端仍强校验。

## 6. 服药计划与提醒

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /plans | 【鉴权】创建服药计划（**一次提醒可含多种药品**，请求体带 `items` 数组） |
| PUT | /plans/{planId} | 【鉴权】修改计划（整体覆盖：时间/周期/报警规则与 `items` 明细一并提交） |
| PATCH | /plans/{planId}/status | 【鉴权】启用/停用计划 |
| DELETE | /plans/{planId} | 【鉴权】删除计划（级联删除明细） |
| GET | /plans?elderId= | 【鉴权】按老人查询计划列表（列表项含药品概览） |
| GET | /plans/{planId} | 【鉴权】计划详情（含 `items` 明细） |
| GET | /plans/{planId}/next-doses | 【鉴权】未来待服药时间线（按次展开，每次列出所需药品） |

计划保存后，后端将提醒规则转成设备可执行的定时任务，并通过 MQTT 下行同步给设备；提醒触发以设备本地为准，云端做兜底与统计。

> **一次提醒 = 一个计划，可含多种药**：`times` / `repeatRule` / 报警规则在计划头统一配置，药品与剂量放在 `items` 明细里（**无仓位字段**，见文档 06 的 2.6 / 2.6.1）。漏服判定以"本次所有 item 都有服药记录"为准，缺任一种药即判 MISS。
>
> **两处口径**：① `times` 为**设备本地墙钟时间**（时区取 `device.timezone`，默认 `Asia/Shanghai`），不是 UTC；② 漏服容忍时长优先级为 **`missAlarmAfterMin`（计划级）> `alarm_setting.missTolerateMin`（老人级）> 系统默认 15 分钟**，计划级留空即沿用上一级。

**示例：创建服药计划（两种药同时服用）**

```json
POST /plans
{
  "elderId":"e-1001","name":"早餐后",
  "times":["08:00","20:00"],"repeatRule":"DAILY","missAlarmAfterMin":15,
  "alarmRule":{"miss":true,"wrongDrug":true,"expired":true,"env":true},
  "items":[
    { "medicineId":"m-205","dose":"1","unit":"片" },
    { "medicineId":"m-388","dose":"2","unit":"粒","note":"餐后" }
  ]
}
// 响应
{ "code":0, "data":{ "planId":"p-3301","syncState":"SYNCED_TO_DEVICE",
    "items":[ { "itemId":"pi-9001","medicineId":"m-205" },
              { "itemId":"pi-9002","medicineId":"m-388" } ] } }
```

> **小程序侧落地口径与规则表（`feat/mp-plan`；后端 `plan-core` / `plan-sync` 未落地，先行约定；规则表经用户确认后沉淀于此）**：
>
> **A. 时间（times）—— 设备本地墙钟，非 UTC**：输入用 `picker mode="time"` 取 `HH:mm`（格式 `^([01]\d|2[0-3]):[0-5]\d$`），计划内去重、至少 1 个；**提交与回显均为原样字符串，不做任何时区换算**（与 1. 通用约定的 UTC 例外一致）；提交时按 `HH:mm` 升序（与上文示例一致）；跨天 / 修改当日生效等边界由服务端处理，前端不拦截。
>
> **B. items 组装（一次提醒多种药）**：`medicineId` 必填；`dose` / `unit` 选填（空则省略，后端按 06"计划明细未指定时取药品默认剂量"兜底，选中药品时前端预填档案值）；`note` 选填；同一计划内 `medicineId` 不重复（06 约束①，picker 中已选项置灰）；`items` 数组序即展示顺序；PUT 为**整体覆盖**（times / repeatRule / items 全量提交）。
>
> **C. 过期 / 无库存药品禁止加入（06 约束②）**：前端按"该老人在其可见设备上的库存行"预判——任一设备有未过期库存即可加入；全部库存已过期置灰提示"已过期"；无任何库存行置灰提示"无库存"；**库存数据拉取失败时不拦截**（后端创建时返回 40901 兜底）；临期可加入、仅文字提示。
>
> **D. 启停与 syncState 回显**：`PATCH /plans/{planId}/status` 请求体**前端先行契约为 `{status: 'ENABLED'|'DISABLED'}`**（取值对照 06 的 2.6 `status` 默认 `ENABLED`；本表原文未定义 body，后端 `plan-core` 定稿后对齐并更新此处）；`syncState` 仅回显——`feat/plan-core` 落地后初始值恒为 **`PENDING`**（06 §2.6 新增列、默认 `PENDING`），真正的 `SYNCED_TO_DEVICE` / `SYNC_FAILED` 由 **`feat/plan-sync`（已实现）** 写入；本例 `SYNCED_TO_DEVICE` 为下发成功后的终态，前端须兼容 `PENDING` 展示为"待同步"，未知值原样展示、无字段不渲染。
>
> 前端触发映射（后端 `plan-sync`，见 03 第 3 章）：创建 / 修改 / 启用（`ENABLED`）→ 下行 `down/schedule` 的 `op=UPSERT`（含完整 `items` 数组）；删除 / 停用（`DISABLED`）→ `op=DELETE`（设备停该计划提醒）；设备回执 `up/ack` 后翻 `SYNCED_TO_DEVICE`，回执 `FAILED` 或 5s 超时重发 3 次仍无回执则翻 `SYNC_FAILED`。无老人 MAIN 设备 / MQTT 禁用时安全降级，`syncState` 保持 `PENDING`。
>
> **E. 权限与老人上下文**：写操作（创建 / 修改 / 启停 / 删除）仅 `WRITE_PLAN`（1.3 矩阵，FAMILY + DOCTOR）；小程序据此只给监护人显写入口，老人端只读，服务端强校验。`elderId`：老人端取自身；监护人端从 `GET /devices` 列表项的 `elderId` 归纳（P1 `feat/mp-guardian` 上线后改调老人列表接口）。
>
> **F. 本期不做（前端）**：`GET /plans/{planId}/next-doses` 的**前端时间线页面**归 P1 `feat/mp-plan-timeline`；但**后端接口已随 `feat/plan-core` 落地**——`days` 默认 7、上限 60，本期仅 `DAILY`，停用计划返回空、按 `device.timezone` 换算 `scheduledTimeUtc` / `scheduledTimeLocal`。计划级 `missAlarmAfterMin` / `alarmRule` 编辑（走后端默认与老人级 `alarm_setting`，归 P1 `feat/mp-alarm`）。
>
> **待后端定稿后收紧**：① `GET /plans` 分页响应字段名（前端 `parsePlansPage` 容错 `{list,records,items}+total` 与裸数组）；② PATCH 请求体（见 D）；③ `repeatRule` 枚举（06 注释"DAILY / WEEKLY ..."未列全，前端先提供 DAILY / WEEKLY 两档，不传即后端默认）。

## 7. 服药记录与依从性

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /records?elderId=&from=&to= | 【鉴权】服药记录分页（含是否按时/是否错服） |
| GET | /records/{recordId} | 【鉴权】记录详情（关联计划与抓拍留证） |
| GET | /adherence?elderId=&period=day\|week\|month | 【鉴权】依从性统计（按时率/漏服/错服） |
| POST | /records/{recordId}/confirm | 【鉴权】监护人补录/确认（设备异常时手工校准） |
| GET | /records/export?elderId=&from=&to= | 【鉴权】导出服药报告（护理/医生用） |

> **小程序侧落地口径（feat/mp-record）**：列表分页响应暂定 `{list, total}`（前端 `parseRecordsPage` 已容错 `records`/`items` 与裸数组，后端定稿后收紧）；详情由后端 join 计划名 / 药品名返回（06 的 2.7 字段充足）；`POST /records/{recordId}/confirm` 仅 `WRITE_RECORD`（FAMILY）可调用，老人写 40301、监护 PENDING 40302；抓拍留证依赖 `capture-core`（P1，feat/mp-capture），本期小程序详情页缩略图留空占位、不调 `GET /captures`。
>
> **`POST /records/{recordId}/confirm` 单接口双语义（record-core 落地补充，2026-10-24）**：`feat/record-core` 用**一个**端点承担"校准已有记录"与"补录新建"两件事——先按 `{recordId}` 查库：① **命中已有记录 → 校准**：仅覆盖请求体里**非空**的 `actualTime`/`actualDose`/`onTime`/`wrongDrug`，`source` 恒置 `MANUAL`（不给设备改回 CAMERA 的机会）；② **记录不存在 → 新建（补录=创建）**：此时路径 `{recordId}` 被**忽略**，由 `med_record_biz_id_seq` 服务端生成新 `mr-` 号并在响应返回（不破坏序列约定），请求体须带 `elderId`/`planId`/`planItemId`/`medicineId`（缺任一带 40001，计划/药品查不到带 40401），`source=MANUAL`，`planTime` 经 `PlanSlotResolver` + 设备时区就近槽重算（无 `planId`/算不出则 NULL）。列表/详情不暴露文件流，只回传 `captureId`（取文件归 `capture-core`）；列表项带 `hasCapture` 供缩略图占位。

## 8. 告警（列表/处理，实时推送走 WebSocket）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /alarms?elderId=&type=&readState= | 【鉴权】告警分页（漏服/错服/过期/环境超标） |
| GET | /alarms/unread-count | 【鉴权】未读告警数（小程序角标） |
| PATCH | /alarms/{alarmId}/read | 【鉴权】标记已读 |
| PATCH | /alarms/{alarmId}/handle | 【鉴权】处理/确认告警（护理人员闭环） |
| PUT | /alarm-settings?elderId= | 【鉴权】配置告警规则与阈值（提前期/环境上下限/漏服容忍） |
| GET | /alarm-settings?elderId= | 【鉴权】查询当前告警配置 |

**告警类型枚举**

| type | 触发来源 | 说明 |
|------|----------|------|
| MISS | 后端判定 | 计划时间+容忍时长内未收到该计划服药事件（**`feat/miss-alarm` 定时判定生成并经 WS 推送**，见 06 §2.9 规则表；**本分支只落库 + 推 WS，不提供下列 REST**） |
| WRONG_DRUG | 设备上报 | 摄像头识别的实际药品 ≠ 计划药品 |
| EXPIRED | 后端判定 | 按手动录入的 `expiryDate` 扫描：临期预警 + 到期告警 |
| ENV | 设备上报 | 温度/湿度/光照超出**该老人统一配置的 `envThreshold`**（不按药品细分，见文档 01 的 5.4） |
| DEVICE_OFFLINE | 后端判定 | 心跳超时，设备离线超阈值（**仅主控**，传感器子设备离线不生成告警） |
| DEVICE_FAULT | 设备上报 | 设备侧异常（`up/event/error`）：门超时 / 识别失败 / 硬件故障 |

> **MISS 告警归属**：`feat/miss-alarm`（07 P0 第 10 步）负责**生成** MISS 告警（落 `alarm` 表 + 经 WebSocket 推 `ALARM` 帧，见 04 第 3 章）；**查询/已读/处理与阈值配置（`PUT /alarm-settings` 等的 `missTolerateMin` 即三级容忍的老人级）归 `feat/alarm-core`**（P1，依赖本分支）。本分支不新增任何 REST。

> **告警阈值配置权限（feat/alarm-core 落地，2026-10-26）**：`GET /alarm-settings`、`GET /alarms`、`GET /alarms/unread-count`、`PATCH /alarms/{alarmId}/read` 用 `READ_ALARM`（四角色可读）；`PATCH /alarms/{alarmId}/handle` 用 `HANDLE_ALARM`（仅 FAMILY + NURSE）；`PUT /alarm-settings` 用新增的 `WRITE_ALARM_SETTINGS`（**仅 FAMILY** 监护人代老人配置，镜像 `CONFIG_LLM_KEY` 口径，DOCTOR / NURSE / ELDER 写返回 40301）。`alarm_setting` 每老人一行（`elder_id` UNIQUE），无则插入、有则更新；请求字段可选、仅提供项覆盖原值。

## 9. 环境监测

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /env/realtime?deviceId=&sensorId= | 【鉴权】最新温湿光读数（按传感器合并） |
| GET | /env/history?deviceId=&sensorId=&from=&to=&interval= | 【鉴权】历史曲线（小程序图表） |

> **环境阈值按老人统一配置**（不再按药品单独设置），走 `PUT /alarm-settings?elderId=` 的 `envThreshold` 字段（见第 8 章）；判定口径见文档 01 的 5.4。超标告警归 `feat/env-alarm`，**本分支（`feat/env-core`）只落库与查询、不告警、不推 WS**。

### 9.1 实时读数 `GET /env/realtime`
- Query：`deviceId`（必填，药箱主控）；`sensorId`（可选，按传感器查）。
- 权限：`AccessService.requireDevice(deviceId, READ_ENV)`，越权 / 设备查不到一律 `40301`（与设备类接口同口径，防设备编号枚举）。
- 响应 `R<EnvRealtimeResponse>`：`{ temperature, humidity, lux, sampleTime }`（`BigDecimal`，某传感器无数据则该字段 `null`；`sampleTime` 取温湿度行与光照行中较新者）。温湿度传感器与光照传感器分两条 `env_sample` 入库，本接口合并两者最新非空读数。

### 9.2 历史曲线 `GET /env/history`
- Query：`deviceId`（必填）；`sensorId`（可选）；`from` / `to`（必填，ISO-8601，含端点）；`interval`（可选，分钟，1~1440，分桶 `avg()` 聚合；缺省返回原始有序点）。
- 按 `sensorId` 查询时只筛该传感器 `device_id`，否则按药箱 `gateway_device_id` 筛其下全部传感器。
- 边界校验（不满足 → `40001`）：`from` / `to` 缺省；`from > to`；`interval` 越界（1~1440）；时间跨度 > 30 天。
- 权限：同上 `40301`。
- 响应 `R<EnvHistoryResponse>`：`{ points: [ { sampleTime, temperature, humidity, lux } ] }`。温湿度与光照来自不同传感器、上报时刻不同 —— 原始模式下单行只带一类数值、其余为 `null`；分桶模式下按桶 `avg()` 聚合（`avg` 天然忽略 `null`，同桶内温湿度取自 TH 行、光照取自 LIGHT 行可正确合并）。

### 9.3 设备详情附带最新遥测
- `GET /devices/{deviceId}` 的 `DeviceDetailResponse` 新增 `latestTelemetry` 字段（`EnvRealtimeResponse`，无上报时为 `null`），由 `DeviceService.detail` 经 `EnvSampleService.realtime(deviceId, null)` 一并取回（见 `feat/env-core`）。

---

## 10. 管理员接口（测试数据清理，dev/QA 工具）

> 本期 `medbox-admin/` 只做「测试数据清理」这一个 dev/QA 工具（其余管理功能待定，设计见 `09`）。所有接口为 **`ADMIN` 角色门禁**，独立于现有 elder 中心的 `AccessService`（见 01 第 3 章）。
> 角色建模与种子账号见 06 的 2.1（`UserRole` 增加 `ADMIN` + `V16__admin_seed.sql` 种子账号，原 `V11__admin_seed.java` 在 Spring Boot 4 下不可靠）；前端页面方案见 `09` 第五/六章。

基础前缀同现有：`/medbox/api/v1`；所有接口需 `Authorization: Bearer <token>` **且 `role == ADMIN`**，否则 `40301`。

### 10.1 列出用户（供挑测试号）

`GET /api/v1/admin/users`

| Query     | 类型   | 说明 |
|-----------|--------|------|
| `status`  | string | 可选：`ACTIVE` / `DELETED`；缺省=全部 |
| `keyword` | string | 可选：LIKE 匹配 `phone` 或 `username`（仅对非空列生效；软删账号这两列已置 NULL） |
| `role`    | string | 可选：`GUARDIAN` / `ELDER` / `ADMIN` |
| `page`    | int    | 默认 1 |
| `size`    | int    | 默认 20，上限 100 |

- 响应：`R<PageResult<AdminUserRow>>`，`AdminUserRow = { userId, role, name, phone, username, status, createdAt }`。
  - 软删（`status=DELETED`）账号的 `phone` / `username` 已置 NULL（见 06 的 2.1 `V7__user_delete.sql`），这两列对它们为空，挑选用 `userId` + `status` + `createdAt`。
- 错误：非 ADMIN → `40301`。

### 10.2 清理（硬删 + 级联）

`DELETE /api/v1/admin/users/{userId}`

- 护栏：目标 `status == ACTIVE` → **拒绝**，返回 `40902`（见 1.2）。
- 通过护栏后：**硬删该用户 + 级联清其孤儿数据**（应用层按依赖顺序删，不依赖物理外键）。
- 响应：`R<Void>`（`code=0`）。
- 错误：`40902`（活跃账号）/ `40301`（非 ADMIN）/ `40401`（userId 不存在）。

> **为什么硬删而非软删**：测试痕迹要彻底清除（软删只置 `DELETED` + 清空手机/用户名，但仍留行与孤儿数据，达不到清理目的）；正因如此才要 `ADMIN` + ACTIVE 护栏双重保护。

### 10.3 级联清理范围（按依赖顺序）

目标 `userId` 可能同时是「老人」（拥有 device / medicine / stock / plan / record）与「监护人」（拥有 relation）。**先删子表，再删 `user` 头**，实现前务必对照 06 复核所有 `user_id` / `elder_id` / `guardian_id` 列：

1. `med_plan_item`：`WHERE plan_id IN (SELECT plan_id FROM med_plan WHERE elder_id = ?)`
2. `med_plan`：`WHERE elder_id = ?`
3. `guardian_relation`：`WHERE elder_id = ? OR guardian_id = ?`（两侧都清）
4. `device`：`WHERE elder_id = ?`
5. `medicine_stock`：`WHERE elder_id = ?`
6. `medicine`：`WHERE owner_elder_id = ?`
7. `med_record`：`WHERE elder_id = ? OR guardian_id = ?`　⚠️ 健康留痕数据，仅测试账号可删
8. 其余含 `user_id` / `elder_id` / `guardian_id` 的表（`capture` / `env_sample` / `alarm` / `alarm_setting` / `refresh_token` / `idempotency_record` 等）：实现前对照 06 逐一确认并纳入级联；与用户无关的表（`drug_manual` / `drug_manual_chunk` / `llm_config` 等）不删。
9. `user`：`WHERE user_id = ?`

> 顺序为逻辑干净（先子后父）；当前库无物理外键，顺序不影响删除成败，但保持「子→父」便于排查。
