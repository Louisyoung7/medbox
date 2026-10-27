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

- **`traceId`**：请求进来时由后端生成（32 位十六进制、无连字符）；客户端可用 `X-Request-Id`（即幂等头）自带，后端校验字符合法性后原样沿用，并同时用 **`X-Trace-Id` 响应头**回写；日志带 `[trace=xxx]`，凭 traceId 可直接 grep 同一次请求的全部日志。幂等去重不在这一层。
- **`timestamp`**：epoch **秒**（不是毫秒）。
- **`data` 为 null 时**：受全局 `non_null` 配置影响，**该字段不出现在 JSON 里**；客户端判定失败只看 `code != 0`，`data` 缺失按 `null` 处理，不能把"取不到 data"当异常。
- **`/medbox/actuator/**` 是 Spring 原生格式，不套这层包装**；业务接口一律 `R<T>`。
- 客户端对**写方法**（`POST`/`PUT`/`PATCH`/`DELETE`）自动注入 `X-Request-Id`（32 位小写十六进制，无连字符），与后端同形；`GET` 不注入。

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
> **补充口径**：
> 1. **未知路径**（`/medbox/api/v1/xxx` 拼错、路径不存在）也返回 **`40401` + HTTP 404** 的统一结构，而不是 Spring 默认的错误页；
> 2. **没有 405 对应的错误码**：请求方法用错（如 POST 打一个 GET 接口）统一按 **`40401` + HTTP 404** 返回。
> 3. 参数校验 / 请求体 JSON 非法 / 缺参 / 参数类型不匹配一律 **`40001`**，提示取第一条错误；未捕获异常一律 **`50000`**，堆栈只进日志、不回显给客户端。

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

#### 1.3.1 权限判定规则表

**输入**：当前登录用户（`userId` + `user.role` ∈ {ELDER, GUARDIAN}）、目标资源归属 `elderId`（`deviceId` 先解析成 `elderId`）、本次操作所需的 `Permission`、监护关系记录。

| 步 | 检查 | 通过条件 | 不通过 |
|----|------|----------|--------|
| ① | 是否登录 | `AuthContext` 中存在当前用户 | **40101** |
| ② | 是否有关系 | 老人本人 → 目标 `elderId` 必须等于自己；监护人 → 必须存在监护关系记录，目标确实是老人，且 `status != REVOKED` | **40301** |
| ③ | 关系是否生效 | `status == ACTIVE`（老人本人直接通过）。`REVOKED` 与状态为空（脏数据）走第 ② 步的 40301 | `PENDING` → **40302** |
| ④ | 角色操作权限 | 命中下方权限矩阵 | **40301** |

**输出**：放行返回访问身份（`kind` = SELF / GUARDIAN、`relationRole`）；拒绝抛 `BizException`，HTTP 403 + `code` 为 40301 / 40302。

> 权限矩阵共 **20** 个权限项（含 `AI_CHAT`）。

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

> `READ_CAPTURE` / `WRITE_CAPTURE` 对护理 / 医生**显式拒绝** —— 用药图像属敏感健康数据，可见范围比本节默认规则更严格（见文档 01 的 5.2）。

**边界口径**：

| 场景 | 结果 | 说明 |
|------|------|------|
| 无 / 无效 / 过期的 access token | 40101 | 拿 refresh token 当 access 用也算无效（`typ` 校验） |
| 拿 refresh token 当 access | 40101 | 同左 |
| 老人访问他人 `elderId` | 40301 | 只读本人 |
| 老人执行写操作 | 40301 | 老人只读，不可改计划 / 配置 Key |
| 监护人与目标无监护关系 | 40301 | 越权 |
| 关系 `PENDING` | **40302** | 老人端尚未确认；此判定**优先于**第 ④ 步 |
| 关系 `REVOKED` / 状态为空 | 40301 | 与"无关系"同码，不回 40302，避免泄漏"曾有关系" |
| 目标 `elderId` 不存在 / 不是老人 | 40301 | 不返回 40401，防资源枚举 |
| `elderId` / `deviceId` 为空 | 40001 | 参数校验失败 |
| `GET /users/me` 但账号已不存在 | 40401 | 唯一例外：当前用户自己的资源，不泄漏任何东西 |

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

> **认证方式约定**：主登录方式为**账号 + 密码**，**不再使用短信验证码**。
>
> - 登录账号为手机号或用户名；密码服务端 **BCrypt 加盐哈希**存储，库内不存明文，登录失败统一返回 `40102` 不区分"账号不存在 / 密码错误"；
> - 局域网阶段为明文 HTTP，密码在传输层可见；在意的话前端可先做一次 SHA-256 再传（**不是安全替代**），正式环境直接上 HTTPS；
> - **本阶段不做微信登录**：后续需要时再加回 `POST /auth/login/wechat` 并在 `user` 表补 `wechat_open_id` 字段即可；
> - **会话有效期**：access token **2 小时**（`expiresIn: 7200`），refresh token **7 天**；refresh 采用轮换（换新即作废旧的）；
> - **老人账号的创建方式**：**保留 `/auth/register`**，监护人可在小程序内注册并为老人代建账号。

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

- `role` **只取 `ELDER` / `GUARDIAN`**（`user.role`，见 06 的 2.1）；**护理 / 医生不在注册范围** —— 他们是监护关系上的 `FAMILY` / `DOCTOR` / `NURSE`，由老人在确认监护关系时指定；
- `username` / `name` 选填；监护人可用本接口**为老人代建账号**（`role=ELDER`）；
- 手机号重复等参数问题按 1.2 的 `40001`。

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

> 客户端只用 `userId` / `role` / `name` / `phone`（`role` 决定角色视图）；其余字段随后端实现追加，前端整份缓存不挑字段。

**指定老人资料（监护人可见）**

```
GET /users/{elderId}/profile

// 响应：结构同 /users/me；null 字段按全局 non_null 规则省略
{ "code":0, "data":{ "userId":"u-1001", "role":"ELDER",
                     "name":"张三", "phone":"13800001234", "username":"zhangsan" } }
```

- **可见性**：老人本人，或与老人存在 `ACTIVE` 监护关系的监护人 —— 复用 `require(elderId, READ_GUARDIAN)`，判定顺序见 1.3（① 40101 → ②③ 关系 → ④ 权限矩阵）。
- **越权口径（同 1.3 防枚举）**：无关系 / `REVOKED` / 目标不是老人 → **40301**；关系 `PENDING` → **40302**。

#### 3.1 注销账号

> **"退出登录"与"注销账号"是两件事**：退出登录只在客户端清凭据；注销账号是服务端把账号置为不可用并释放登录标识。

```
POST /users/me/delete
{ "password":"******" }

// 响应
{ "code":0, "data":{ "userId":"u-1002", "deletedAt":"2026-10-14T08:30:00Z" } }
```

- **路径挂在 `/users/me` 下**：注销必须先登录，故不能放 `/auth/logout` 一类路径；动作型子路径 + `POST`，与 `PATCH /users/guardians/{relationId}/accept` 同风格。
- **必须传密码**：注销不可逆，后端用 **BCrypt 校验当前账号密码**做二次确认。

**注销规则表（顺序即优先级）**：

| 顺序 | 判定条件 | 输出 | 说明 |
|------|----------|------|------|
| ① | 未登录 / token 无效或过期 | **40101** | 校验顺序第 ① 步，同 1.3 |
| ② | `password` 缺失 / 空串 / 非字符串 | **40001** | 提示取后端 `message` |
| ③ | 账号已是 `DELETED` | **40101** | 不回 40401，避免泄漏"账号曾存在" |
| ④ | 密码 BCrypt 校验不符 | **40102** | **不注销**；与"账号不存在"同码 |
| ⑤ | 全部通过 | `code=0` | 执行下方数据处理 |

**注销后的数据处理（软删除，按此顺序执行，不级联删除业务数据）**：

| 对象 | 处理 |
|------|------|
| `user` | `status='DELETED'`、`deleted_at=now()`；**`phone` / `username` 置 `NULL`** 释放唯一键（同一手机号可重新注册）；行本身保留，历史数据的外键仍然成立 |
| `refresh_token` | 该用户全部生效中的行 → `REVOKED` |
| `guardian_relation` | 该用户作为 `elder_id` **或** `guardian_id` 的全部 `PENDING` / `ACTIVE` 关系 → `REVOKED`（**双向**） |
| 业务数据 | `device` / `medicine` / `medicine_stock` / `med_plan` / `med_record` / `env_sample` / `alarm` / `capture` / `llm_config` / `drug_manual` **全部保留不动** —— 用药记录需留痕，且设备是实物、不随账号消失 |
| access token | **无状态，不做黑名单**：已签发的 access 最长 **2 小时**仍可用，靠 refresh 已撤销兜底 |

**边界用例**：

| 用例 | 结果 |
|------|------|
| 注销后立刻用旧 access 调业务接口 | 2 小时内仍可用（**已知且接受**的口径），之后 40101 |
| 注销后调 `/auth/refresh` | **40101**（token 已 `REVOKED`） |
| 注销后用旧密码登录 | **40102**（等同"账号不存在"） |
| 用同一手机号重新注册 | 成功，分到**新的 `user_id`**，旧数据不继承 |
| 老人注销 | 其 ACTIVE 监护关系 → `REVOKED`，监护人的"我监护的老人"里该老人消失 |
| 监护人注销 | 只撤销**自己**的关系；老人与其他监护人不受影响 |
| 注销后 2 小时内换一台设备重复提交 | 第二次 **40101**（账号已 `DELETED`） |

> **未做（有意）**：监护人代老人注销；微信登录场景下的确认方式；access token 黑名单。

#### 3.2 监护关系写接口

> 这 4 个写接口**没有对应 `Permission` 权限位**（判定型写操作），按「账号角色 + 身份」手动校验；两步绑定：① 监护人申请 → `PENDING`；② 老人确认 → `ACTIVE`，监护人才真正获得读写权限（未确认前访问该老人数据仍返回 **40302**，见 1.3）。

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

**规则表**：

| 顺序 | 判定条件 | 输出 |
|------|----------|------|
| ① | 未登录 / token 无效 | **40101** |
| ② | 当前角色 ≠ `GUARDIAN`（老人本人等） | **40301** |
| ③ | 目标 `elderId` 不是真实老人 | **40301** |
| ④ | 无既有关系 | `code=0` 建 `PENDING` |
| ⑤ | 已有 `PENDING` / `ACTIVE` | **40901** |
| ⑥ | 已有 `REVOKED` | `code=0` 改回 `PENDING`（清空 `role`） |

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
| ③ | 通过 | `code=0` 返回本人 `PENDING` 关系列表，join 带监护人姓名 + 手机，按 `createdAt` 升序 |

**③ 确认（置 ACTIVE）**

```
PATCH /users/guardians/{relationId}/accept
{ "role":"FAMILY" }        // 必填，FAMILY / DOCTOR / NURSE 三选一

// 成功
{ "code":0, "data":{ "relationId":"r-1001", "elderId":"u-1001", "guardianId":"u-2001",
                     "role":"FAMILY", "status":"ACTIVE", "createdAt":"..." } }
```

| 顺序 | 判定条件 | 输出 |
|------|----------|------|
| ① | 未登录 | **40101** |
| ② | 当前角色 ≠ `ELDER` | **40301** |
| ③ | `role` 缺失 / 空白 / 超长或非法值 | **40001** |
| ④ | `relationId` 查不到 | **40301** |
| ⑤ | `relation.elderId` ≠ 当前老人 | **40301** |
| ⑥ | 关系已非 `PENDING` | **40901** |
| ⑦ | 通过 | `code=0` 置 `ACTIVE` 并落角色 |

**④ 拒绝 / 解除**

```
DELETE /users/guardians/{relationId}

// 成功（无 data）
{ "code":0 }
```

| 顺序 | 判定条件 | 输出 |
|------|----------|------|
| ① | 未登录 | **40101** |
| ② | `relationId` 查不到 | **40301** |
| ③ | 当前用户既非关系里的老人、也非监护人 | **40301** |
| ④ | 通过 | `code=0` 软删置 `REVOKED`（已是 `REVOKED` 则幂等跳过） |

**边界用例**：

| 用例 | 结果 |
|------|------|
| 申请后、确认前，监护人访问该老人数据 | **40302** |
| 解除后再申请 | 成功，`REVOKED` → `PENDING`（`role` 清空） |
| 陌生人对 `relationId` 做 accept / delete | **40301**（与"查不到"同码，防枚举） |
| 老人 / 监护人注销 | 双向关系置 `REVOKED` |

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

#### 4.0 设备注册与查询

**谁可以注册**：`POST /devices/register` 是**监护人代老人**登记设备 —— 走 `require(elderId, WRITE_DEVICE)`，**只 FAMILY 监护人具备该权限**。老人本人（`SELF`）、医生、护理一律 40301。设备端**不做无 Token 自注册**，仍走 JWT。

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
| **设备编号已登记（含子设备）** | **40901** —— `device_id` 唯一，不静默覆盖、不改绑 |
| 编号格式非法 / 编号重复 / 子设备类型为 MAIN / 时区非法 / `page` 或 `size` < 1 | 40001 |
| **查询他人设备，或设备不存在** | **一律 40301**（不返回 40401，防设备编号枚举） |
| 传**子设备编号**进 `/devices/{id}`、`/status`、`/events` | 40301（这些接口的 `deviceId` 只接受主控） |

**事件流水 `GET /devices/{deviceId}/events`**：分页返回，`page` 从 1 起，不传即用默认 20；`size` 超过上限静默取上限；`page`/`size` 小于 1 报 40001。按 `created_at` **倒序**（最新在前）。数据源是 `device_event` 表，**只写入 `REGISTERED` / `ONLINE` / `OFFLINE` 三类**，`FAULT` / `COMMAND` 由后续分支续写。

**在线状态只写"跃变"**：只有状态真的变了才落流水（同时推 `DEVICE_STATUS` 帧，见文档 04）。

**两处刻意留空（接口结构不动，待后续补）**：

- `GET /devices/{deviceId}` 的**最新遥测**：读数在 `env_sample` 表，届时在响应里加字段即可；
- `GET /devices/{deviceId}/status` 的**仓门状态**：设备侧尚未上报、设备表也无该列，等上报后再补。

#### 4.0.1 命令下发

**请求体**：`cmd`（必填，`BUZZ` / `UNLOCK` / `REBOOT` / `CALIBRATE`）+ `params`（可选 Map，如蜂鸣的 `durationSec`）。
**请求体不收 `operator`** —— 操作人由服务端取鉴权用户落 `device_command.operator_id` 留痕（不信客户端自报身份）。

**判定表（顺序即优先级）**：

| 场景 | 结果 |
|------|------|
| 未登录 | 40101 |
| 设备不存在 / 越权 / 传**子设备编号** / 老人本人 | **40301**（`WRITE_DEVICE` 仅 FAMILY；不区分不存在与无权限，防编号枚举） |
| 监护关系 PENDING | 40302 |
| `cmd` 不在白名单 | 40001 |
| **设备离线（`online_state != ONLINE`）** | **40901** —— 提示"设备离线，无法下发" |
| MQTT 未启用 | 50000（**不落库**：命令是用户主动的同步操作，悬挂 PENDING 没意义） |
| 受理成功 | 200，`{commandId, state:"PENDING"}`，异步等回执 |

**终态流转**：受理落 `device_command`（PENDING）→ 转 MQTT `down/command`（QoS1、信封带 `msgId` + `needAck:true`）→ 设备回执 `up/ack` 或 5s 超时重发 3 次仍无回执 → 行翻 `SUCCESS` / `FAILED` 终态（更新条件带 `state='PENDING'`，迟到/重复回执幂等）。**发布当场抛异常**→ 行置 FAILED + 50000。

**COMMAND 事件契约**：每次下发写一条 PENDING 事件、翻终态再写一条，`detail` 为 JSON `{"commandId","cmd","state"[,"reason"]}`（见 06 的 2.16 / 2.17）。**不生成告警、无命令查询接口、无 WS 帧** —— 前端刷 `GET /devices/{id}/events` 观察终态。

**示例：下发控制命令**

```
POST /devices/BOXA-DEBUG-01/commands
{ "cmd":"BUZZ", "params":{"durationSec":5} }

// 响应（异步下发，返回受理）
{ "code":0, "data":{ "commandId":"c-7f21", "state":"PENDING" } }
```

#### 4.1 设备抓拍图片（摄像头留证，已读 7 天 / 未读 30 天过期）

摄像头识别结果的**结构化数据走 MQTT**（见文档 03 第 4 章）；**图片单独走 HTTP multipart 直传后端**。

> **当前摄像头默认全量抓拍：正常服药也拍、每次服药都拍**，后端按全量接收。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /devices/{deviceId}/captures | 【**设备鉴权**】摄像头上传抓拍图片（multipart），返回 `captureId` 与 `expireAt`。`deviceId` 填**摄像头子设备自己的 deviceId**，后端按 `parent_device_id` 反查所属药箱与老人 |
| GET | /captures?elderId=&reason=&from=&to= | 【鉴权】抓拍列表（仅元数据，不含文件流） |
| GET | /captures/{captureId} | 【鉴权】抓拍详情 |
| GET | /captures/{captureId}/file?token= | 【鉴权】读取图片流（供小程序 `<image src>` 使用，校验监护关系） |
| DELETE | /captures/{captureId} | 【鉴权·监护人】手动提前删除 |

**约定**：

- **`reason` 取值**：`NORMAL` 正常服药抓拍、`WRONG_DRUG` 错服、`LOW_CONFIDENCE` 置信度低于阈值、`MANUAL` 监护人主动请求。列表接口可按 `reason=WRONG_DRUG` 过滤只看异常。
- **去重规则**：同一服药事件**只保留 1 张**。后端按 `planId + capturedAt` **60 秒时间窗**去重，窗口内的重复抓拍只保留第一张。
- **上传侧鉴权走设备身份，不走用户 JWT**：设备侧用**一把共用密钥**做 HMAC 签名，Header 携带 `X-Device-Id` / `X-Device-Sign` / `X-Timestamp`，其中 `sign = HMAC_SHA256(sharedSecret, deviceId + timestamp)`；后端校验签名并校验**时间窗 ±5 分钟**（防重放）。上传接口只允许写入该设备所属药箱绑定的老人。**不做"只校验 `X-Device-Id`"的退化方案**。MQTT 通道本期是匿名的，与此处的 HTTP 上传鉴权相互独立。
- **下载侧鉴权**：必须是**老人本人**或其 **ACTIVE 监护人**，其余角色（护理 / 医生）一律 40301 —— 比 1.3 的默认规则更严格。
- **为什么用 query 传 token**：小程序 `<image src>` 无法携带自定义 Header，故用后端签发的**短期 `fileToken`**（5 分钟有效）拼接在 URL 上；**不要直接把长期 JWT 放 URL**（会落进日志与浏览器历史）。
- **过期清理（已读 / 未读分开算）**：`expire_at = MIN(captured_at + 30 天, viewed_at + 7 天)` —— **未读最长保留 30 天**，**首次查看后再保留 7 天**（查看只会让过期提前，不会延长）。首次调用 `/captures/{id}/file` 读取图片流时后端自动写入 `viewed_at` 并重算 `expire_at`；天数可配。后端每日定时任务删除到期文件与记录，监护人可手动提前删除。
- **存储**：图片存后端主机本地目录，不落库、不暴露真实路径；落盘路径 `{dir}/{YYYY-MM-DD}/{captureId}.{ext}`，`file_path` 入库但**绝不出现在任何响应里**。`{ext}` 由上传 `Content-Type` 推导（仅 `jpg`/`png`/`webp`，其余兜底 `jpg`），**不使用原始文件名**（防路径穿越）。

##### 4.1.1 上传侧鉴权（设备身份，HMAC，不走 JWT）

`POST /devices/{deviceId}/captures` 从 `AuthInterceptor` 排除，由本类自带设备鉴权。设备侧用一把**共用密钥**（写在固件与后端配置里，不入库）做 HMAC 签名，携带三个 Header：

| Header | 含义 |
|--------|------|
| `X-Device-Id` | 摄像头子设备自己的 `deviceId`，**必须与路径 `deviceId` 完全一致**（否则 40001） |
| `X-Device-Sign` | `HMAC_SHA256(secret, deviceId + timestamp)`，**十六进制小写** |
| `X-Timestamp` | Unix **秒**，与服务器时钟差值绝对值 **≤ 5 分钟**（防重放） |

后端校验：① 签名正确（伪造 / 密钥不符 → **40101**）；② 时间戳在 ±5 分钟窗口内（超出 → **40101**）；③ `X-Device-Id == 路径 deviceId`（否则 40001）。**不做"只校验 `X-Device-Id`"的退化方案**。校验通过后后端按 `parent_device_id` 反查所属药箱与老人，只允写入该设备所属老人。

##### 4.1.2 接口契约（5 个端点）

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

##### 4.1.3 错误码与边界（不新增码值，沿用 1.2 / 1.3）

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

> **去重规则（实现确定）**：`planId` 非空时，按 `|capturedAt − 已有 capturedAt| ≤ 60s` 判定重复，命中只保留第一张；`planId` 为空不去重。

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

**接口口径**：

- **新建与更新同走 `POST /medicines`**：更新时请求体带 `medicineId`，创建不带（由后端生成）；`ownerElderId` **不传即公共字典**，传某老人 `userId` 即其私有药品。可见范围（公共 + 我监护老人的私有）由后端按 `visibleElderIds()` **行级过滤**。
- **分页响应形状**：`GET /medicines` 按通用约定收 `page` / `size`、返回 `total`；列表数组字段名以最终实现为准。
- **删除 40901**：药品被任一药箱库存引用（`medicine_stock.medicine_id` 存在记录）时拒绝删除。
- **写权限**：`POST` / `DELETE` 仅 `WRITE_MEDICINE`（1.3 矩阵，仅 FAMILY）；服务端强校验。

**库存四条 · 接口口径**：

1. **响应形状**：`GET /devices/{deviceId}/stocks` 的列表行级字段即 06 的 2.5（`stockId` / `medicineId` / `quantity` / `productionDate` / `expiryDate` / `updatedAt`），**不内嵌药品摘要** —— 名称 / 规格 / 单位由客户端拉药品字典按 `medicineId` join。
2. **请求体字段（camelCase，与 06 列名对应）**：PUT 录入/更新与 POST `/in` 补药入库均为 `{ quantity, productionDate?, expiryDate? }`；**PUT 的 `quantity` 为绝对值（覆盖写）**，**POST `/in` 的 `quantity` 为增量**；两个日期均为 `YYYY-MM-DD`（DATE 列，选填，不传即不更新该列）。
3. **设备维度错误码**：沿用第 4 章口径 —— 查询 / 操作他人设备与设备不存在**一律 40301**（不返回 40401）；数量非法等参数错误 40001。
4. **日期无时区换算**：`productionDate` / `expiryDate` 是 DATE（无时刻），直接收发 `YYYY-MM-DD`，**不走 1. 通用约定的 UTC 口径**（该口径的例外只有计划 `times`）。
5. **`GET /devices/{deviceId}/expiring`（临期/过期清单）**：库存清单页的临期 / 过期标签可由客户端本地展示；服务端提供该清单接口供精确预警。
6. **写权限**：四条均属 `WRITE_MEDICINE`（1.3 矩阵，仅 FAMILY）；服务端强校验。

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

**接口口径与规则**：

- **时间（times）—— 设备本地墙钟，非 UTC**：输入格式 `HH:mm`（计划内去重、至少 1 个）；**提交与回显均为原样字符串，不做任何时区换算**；提交时按 `HH:mm` 升序。
- **items 组装（一次提醒多种药）**：`medicineId` 必填；`dose` / `unit` 选填（空则省略）；`note` 选填；同一计划内 `medicineId` 不重复；`items` 数组序即展示顺序；PUT 为**整体覆盖**（times / repeatRule / items 全量提交）。
- **过期 / 无库存药品禁止加入**：后端创建时返回 `40901` 兜底（库存数据拉取失败时仍允许提交，由后端拦截）。
- **启停与 syncState**：`PATCH /plans/{planId}/status` 请求体为 `{status: 'ENABLED'|'DISABLED'}`；`syncState` 仅回显——创建后初始值恒为 **`PENDING`**，真正的 `SYNCED_TO_DEVICE` / `SYNC_FAILED` 由设备同步写入；前端须兼容 `PENDING` 展示为"待同步"，未知值原样展示、无字段不渲染。
- **设备同步映射**：创建 / 修改 / 启用（`ENABLED`）→ 下行 `down/schedule` 的 `op=UPSERT`（含完整 `items` 数组）；删除 / 停用（`DISABLED`）→ `op=DELETE`；设备回执 `up/ack` 后翻 `SYNCED_TO_DEVICE`，回执 `FAILED` 或超时重发 3 次仍无回执则翻 `SYNC_FAILED`。无老人 MAIN 设备 / MQTT 禁用时安全降级，`syncState` 保持 `PENDING`。
- **权限与老人上下文**：写操作（创建 / 修改 / 启停 / 删除）仅 `WRITE_PLAN`（1.3 矩阵，FAMILY + DOCTOR）；服务端强校验。`elderId`：老人端取自身；监护人端从可见设备归纳。
- **本期不做（后端）**：`GET /plans/{planId}/next-doses` 的**时间线页面**前端归后续分支；但**后端接口已落地**——`days` 默认 7、上限 60，本期仅 `DAILY`，停用计划返回空、按 `device.timezone` 换算 `scheduledTimeUtc` / `scheduledTimeLocal`。计划级 `missAlarmAfterMin` / `alarmRule` 编辑（走后端默认与老人级 `alarm_setting`）。

> **小程序侧落地（`feat/mp-plan-timeline`；后端 `plan-core` 已落地，按契约 + mock 先行）**：
>
> 1. **接口层**：`api/plan.js` 的 `getNextDoses(planId, {days})`（GET，默认 `days=7`、上限 60，越界由后端 40001；**无** `X-Request-Id`，与 `listPlans` 同 GET 口径）。前端**不自行换算时区、不做按次展开**——这些全在后端按 `device.timezone` 完成，前端只消费 `scheduledTimeLocal`。
> 2. **纯函数**：`utils/plan.js` 的 `parseNextDoses`（容错 `{doses:[...]}` 与裸数组，后端响应外壳未定稿，一处收敛）、`toDoseView`（取 `scheduledTimeLocal`、拆出本地日期 `YYYY-MM-DD` 与 `HH:mm` 供分组/排序、items 映射为药品+剂量文案、缺字段兜底）、`groupDosesByDay`（按本地日期升序分组、组内时间序）。"前端规则表"= 直接展示 `scheduledTimeLocal`、按本地日期分组、空态全在前端判定。
> 3. **页面**：`pages/plan/timeline.vue`（新）按本地日期分组渲染时间线（日期头 + 每次 `HH:mm` + 药品剂量列表），顶部 days 分段选择（7/15/30）切换即重拉；**空态**：计划停用 → "计划已停用，暂无未来服药"；所选天数内无安排 → "未来 N 天暂无服药安排"；加载失败按 code 细分提示。
> 4. **入口与权限**：计划列表卡片加"未来服药时间线 ›"链接跳 timeline（与卡片点按进编辑区分）；时间线只读，`READ_PLAN` = 全角色可读，无需 `RoleGate`、不新增 `ENTRY_ROLES`、老人端同样可见；不改 `permission.js`。
> 5. **mock 先行**：`tools/mock` 补 `GET /plans/{planId}/next-doses`（停用返 `{doses:[]}`、`days` 越界 40001、未知 40401、权限 40301/40302、按本地墙钟 `times × days` 展开并拼 `items` 药品名/剂量）；仅演示用途，真后端本期仅 `DAILY`。

## 7. 服药记录与依从性

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /records?elderId=&from=&to= | 【鉴权】服药记录分页（含是否按时/是否错服） |
| GET | /records/{recordId} | 【鉴权】记录详情（关联计划与抓拍留证） |
| GET | /adherence?elderId=&period=day\|week\|month | 【鉴权】依从性统计（按时率/漏服/错服） |
| POST | /records/{recordId}/confirm | 【鉴权】监护人补录/确认（设备异常时手工校准） |
| GET | /records/export?elderId=&from=&to= | 【鉴权】导出服药报告（护理/医生用） |

**接口口径**：

- 列表分页响应按通用约定（`page`/`size`/`total`）；详情由后端 join 计划名 / 药品名返回；列表/详情不暴露文件流，只回传 `captureId`（取文件归抓拍接口）；列表项带 `hasCapture` 供缩略图占位。
- `POST /records/{recordId}/confirm` 单接口双语义：先按 `{recordId}` 查库：① **命中已有记录 → 校准**：仅覆盖请求体里**非空**的 `actualTime`/`actualDose`/`onTime`/`wrongDrug`，`source` 恒置 `MANUAL`；② **记录不存在 → 新建（补录）**：此时路径 `{recordId}` 被**忽略**，由服务端生成新 `mr-` 号并在响应返回，请求体须带 `elderId`/`planId`/`planItemId`/`medicineId`（缺任一带 40001，计划/药品查不到带 40401），`source=MANUAL`，`planTime` 经就近槽重算（无 `planId`/算不出则 NULL）。
- `POST /records/{recordId}/confirm` 仅 `WRITE_RECORD`（FAMILY）可调用，老人写 40301、监护 PENDING 40302。

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
| MISS | 后端判定 | 计划时间+容忍时长内未收到该计划服药事件（**后端定时判定生成并经 WS 推送**；**本分支只落库 + 推 WS，不提供下列 REST**） |
| WRONG_DRUG | 设备上报 | 摄像头识别的实际药品 ≠ 计划药品 |
| EXPIRED | 后端判定 | 按手动录入的 `expiryDate` 扫描：临期预警 + 到期告警 |
| ENV | 设备上报 | 温度/湿度/光照超出**该老人统一配置的 `envThreshold`**（不按药品细分，见文档 01 的 5.4） |
| DEVICE_OFFLINE | 后端判定 | 心跳超时，设备离线超阈值（**仅主控**，传感器子设备离线不生成告警） |
| DEVICE_FAULT | 设备上报 | 设备侧异常（`up/event/error`）：门超时 / 识别失败 / 硬件故障 |

> **MISS 告警归属**：由后端**生成** MISS 告警（落 `alarm` 表 + 经 WebSocket 推 `ALARM` 帧）；**查询/已读/处理与阈值配置归告警核心分支**（依赖本分支）。本分支不新增任何 REST。

> **告警阈值配置权限**：`GET /alarm-settings`、`GET /alarms`、`GET /alarms/unread-count`、`PATCH /alarms/{alarmId}/read` 用 `READ_ALARM`（四角色可读）；`PATCH /alarms/{alarmId}/handle` 用 `HANDLE_ALARM`（仅 FAMILY + NURSE）；`PUT /alarm-settings` 用新增的 `WRITE_ALARM_SETTINGS`（**仅 FAMILY** 监护人代老人配置，DOCTOR / NURSE / ELDER 写返回 40301）。`alarm_setting` 每老人一行（`elder_id` UNIQUE），无则插入、有则更新；请求字段可选、仅提供项覆盖原值。

**权限与接口口径**：

- 接口层：`getAlarms({elderId,type,readState,page,size})`、`getUnreadCount({elderId})`、`markAlarmRead(alarmId)`、`handleAlarm(alarmId)`、`getAlarmSettings({elderId})`、`saveAlarmSettings({elderId}, body)`（写操作自动注入 `X-Request-Id`）。`type`/`readState` 传 `'ALL'` 视为不过滤。
- 监护人选老人（elderId）：监护人侧可见老人由 `GET /devices` 推导（每台 MAIN 主控的 `elderId` 即一位老人）；ELDER 角色直接用自身 `userId`；WS 订阅主题同样由该推导结果传入（老人端省略）。
- 权限门控：列表 / 未读 / 已读 / 设置查询属 `READ_ALARM`（四角色）；`handleAlarm` 的 `HANDLE_ALARM`（FAMILY+NURSE）；`saveAlarmSettings` 的 `WRITE_ALARM_SETTINGS`（仅 FAMILY）。服务端均强校验。
- 未读角标与实时告警：`GET /alarms/unread-count` 驱动角标；WS 收到 `ALARM` 帧 → 全局弹窗 + 未读 +1；`RESYNC` 触发列表页 REST 全量拉取；`REMINDER` 帧 → 多药提醒（一次提醒可含多种药，非告警、不进列表）。
- 归一化与容错：列表响应兼容 `{list,total}` / `{records}` / `{items}` / 裸数组；缺字段兜底默认值（提前期 10 / 漏服容忍 15 / 温湿光上下限）。

## 9. 环境监测

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /env/realtime?deviceId=&sensorId= | 【鉴权】最新温湿光读数（按传感器合并） |
| GET | /env/history?deviceId=&sensorId=&from=&to=&interval= | 【鉴权】历史曲线（小程序图表） |

> **环境阈值按老人统一配置**（不再按药品单独设置），走 `PUT /alarm-settings?elderId=` 的 `envThreshold` 字段（见第 8 章）；判定口径见文档 01 的 5.4。超标告警归环境告警分支，**本分支只落库与查询、不告警、不推 WS**。

### 9.1 实时读数 `GET /env/realtime`
- Query：`deviceId`（必填，药箱主控）；`sensorId`（可选，按传感器查）。
- 权限：`requireDevice(deviceId, READ_ENV)`，越权 / 设备查不到一律 `40301`（与设备类接口同口径，防设备编号枚举）。
- 响应 `R<EnvRealtimeResponse>`：`{ temperature, humidity, lux, sampleTime }`（某传感器无数据则该字段 `null`；`sampleTime` 取温湿度行与光照行中较新者）。温湿度传感器与光照传感器分两条 `env_sample` 入库，本接口合并两者最新非空读数。

### 9.2 历史曲线 `GET /env/history`
- Query：`deviceId`（必填）；`sensorId`（可选）；`from` / `to`（必填，ISO-8601，含端点）；`interval`（可选，分钟，1~1440，分桶 `avg()` 聚合；缺省返回原始有序点）。
- 按 `sensorId` 查询时只筛该传感器 `device_id`，否则按药箱 `gateway_device_id` 筛其下全部传感器。
- 边界校验（不满足 → `40001`）：`from` / `to` 缺省；`from > to`；`interval` 越界（1~1440）；时间跨度 > 30 天。
- 权限：同上 `40301`。
- 响应 `R<EnvHistoryResponse>`：`{ points: [ { sampleTime, temperature, humidity, lux } ] }`。温湿度与光照来自不同传感器、上报时刻不同 —— 原始模式下单行只带一类数值、其余为 `null`；分桶模式下按桶 `avg()` 聚合（`avg` 天然忽略 `null`）。

### 9.3 设备详情附带最新遥测
- `GET /devices/{deviceId}` 的 `DeviceDetailResponse` 新增 `latestTelemetry` 字段（`EnvRealtimeResponse`，无上报时为 `null`），由 `DeviceService.detail` 一并取回。

---

## 10. 管理员接口（测试数据清理，dev/QA 工具）

> 本期 `medbox-admin/` 只做「测试数据清理」这一个 dev/QA 工具（其余管理功能待定，设计见 `09`）。所有接口为 **`ADMIN` 角色门禁**，独立于现有 elder 中心的权限体系（见 01 第 3 章）。
> 角色建模与种子账号见 06 的 2.1（`UserRole` 增加 `ADMIN`）。

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
  - 软删（`status=DELETED`）账号的 `phone` / `username` 已置 NULL，挑选用 `userId` + `status` + `createdAt`。
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
3. `med_record`：`WHERE elder_id = ?`
4. `medicine_stock`：`WHERE elder_id = ?`
5. `device`：`WHERE elder_id = ?`（及其传感器子设备）
6. `guardian_relation`：`WHERE elder_id = ? OR guardian_id = ?`
7. `alarm` / `alarm_setting`：`WHERE elder_id = ?`
8. `capture`：`WHERE elder_id = ?`
9. `user`：最后删除
