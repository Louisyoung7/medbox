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
- **未做（有意）**：监护关系的申请 / 确认 / 解除接口（P1 `feat/guardian-relation`）、`GET /users/{elderId}/profile`（P1 `feat/user-profile`）。

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
curl -s -X POST http://192.168.1.10:8080/medbox/api/v1/devices/BOXA1001-S-CAM01/captures \
  -H "X-Device-Id: BOXA1001-S-CAM01" -H "X-Device-Sign: <hmac>" -H "X-Timestamp: 1759474540" \
  -F "file=@capture.jpg" -F "reason=WRONG_DRUG" -F "medicineId=m-205" | jq
```

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

本节的**注册 + 四个 GET**（列表 / 详情 / 状态 / 事件流水）已落地；`POST /devices/{id}/commands` 归 `feat/device-command`、`GET /devices/{id}/sensors` 归 `feat/device-sensor`。

**谁可以注册**：`POST /devices/register` 是**监护人代老人**登记设备 —— 走 `require(elderId, WRITE_DEVICE)`，**只 FAMILY 监护人具备该权限**。老人本人（`SELF`）、医生、护理一律 40301（见 1.3 权限矩阵）。设备端**不做无 Token 自注册**，仍走 JWT。

**示例：注册**

```
POST /devices/register
{ "deviceId":"BOXA1001", "elderId":"u-1001",
  "sensors":[{"deviceId":"BOXA1001-S-TH01","deviceType":"TEMP_HUMI"},
             {"deviceId":"BOXA1001-S-CAM01","deviceType":"CAMERA"}] }

// 响应（注册即生效；onlineState 恒为 OFFLINE —— 真正的在线要等设备连上 EMQX）
{ "code":0, "data":{
    "deviceId":"BOXA1001", "deviceType":"MAIN", "elderId":"u-1001",
    "productKey":"medbox-box", "timezone":"Asia/Shanghai", "onlineState":"OFFLINE",
    "registeredAt":"2026-10-13T01:00:00Z",
    "sensors":[{"deviceId":"BOXA1001-S-TH01","deviceType":"TEMP_HUMI"},
               {"deviceId":"BOXA1001-S-CAM01","deviceType":"CAMERA"}] } }
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
POST /devices/BOXA1001/commands
{ "cmd":"BUZZ", "params":{"durationSec":5}, "operator":"doctor" }

// 响应（异步下发，返回受理）
{ "code":0, "data":{ "commandId":"c-7f21", "state":"PENDING" } }
```

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
POST /devices/BOXA1001-S-CAM01/captures     // 摄像头子设备自己的 deviceId
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
- **存储**：图片存后端主机本地目录，不落库、不暴露真实路径（表结构见文档 06 的 2.12）。

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
> **D. 启停与 syncState 回显**：`PATCH /plans/{planId}/status` 请求体**前端先行契约为 `{status: 'ENABLED'|'DISABLED'}`**（取值对照 06 的 2.6 `status` 默认 `ENABLED`；本表原文未定义 body，后端 `plan-core` 定稿后对齐并更新此处）；`syncState` 仅回显——`feat/plan-core` 落地后初始值恒为 **`PENDING`**（06 §2.6 新增列、默认 `PENDING`），真正的 `SYNCED_TO_DEVICE` / `SYNC_FAILED` 由后续 `feat/plan-sync` 写入；本例 `SYNCED_TO_DEVICE` 为下发成功后的终态，前端须兼容 `PENDING` 展示为"待同步"，未知值原样展示、无字段不渲染。
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
| MISS | 后端判定 | 计划时间+容忍时长内未收到该计划服药事件 |
| WRONG_DRUG | 设备上报 | 摄像头识别的实际药品 ≠ 计划药品 |
| EXPIRED | 后端判定 | 按手动录入的 `expiryDate` 扫描：临期预警 + 到期告警 |
| ENV | 设备上报 | 温度/湿度/光照超出**该老人统一配置的 `envThreshold`**（不按药品细分，见文档 01 的 5.4） |
| DEVICE_OFFLINE | 后端判定 | 心跳超时，设备离线超阈值（**仅主控**，传感器子设备离线不生成告警） |
| DEVICE_FAULT | 设备上报 | 设备侧异常（`up/event/error`）：门超时 / 识别失败 / 硬件故障 |

## 9. 环境监测

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /env/realtime?deviceId= | 【鉴权】最新温湿光读数 |
| GET | /env/history?deviceId=&from=&to=&interval= | 【鉴权】历史曲线（小程序图表） |

> **环境阈值按老人统一配置**（不再按药品单独设置），走 `PUT /alarm-settings?elderId=` 的 `envThreshold` 字段（见第 8 章）；判定口径见文档 01 的 5.4。

---

## 10. 管理员接口（测试数据清理，dev/QA 工具）

> 本期 `medbox-admin/` 只做「测试数据清理」这一个 dev/QA 工具（其余管理功能待定，设计见 `09`）。所有接口为 **`ADMIN` 角色门禁**，独立于现有 elder 中心的 `AccessService`（见 01 第 3 章）。
> 角色建模与种子账号见 06 的 2.1（`UserRole` 增加 `ADMIN` + `V10__admin_seed.sql` 种子账号）；前端页面方案见 `09` 第五/六章。

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
