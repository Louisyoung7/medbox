# 变更记录（CHANGELOG）

> 本文件记录文档集的每次变更，便于**各端（后端 / 小程序 / Web 前端）** 判断"要不要跟着改、影响哪些模块"。
>
> **本文件不再维护版本号**（2026-10-10 起）：条目用**日期**定位，**最新的在最上面**。历史条目里的 `V15` / `V16` 等编号是旧标识，**原样保留、不要回去改**，正文对它们的引用仍然有效。
>
> **条目格式（简洁优先）**：
>
> ```markdown
> ## [YYYY-MM-DD] 一句话说清这次改了什么
>
> - **改了哪个文件**：一句话改了什么（限 3 条以内）
> - 影响端：`medbox-server/` 是 / 否 · `medbox-miniapp/` 是 / 否 · 嵌入式 否
> ```
>
> 只写**别人需要跟着改的事**：新增 / 改动的接口字段、表、配置、跨分支约定、踩过的坑。**不写实现细节与文件清单**（那些看 commit 与代码），**不每次都画"影响提示"大表格** —— 一行 `影响端` 能说清就不展开。
>
> **V1.8 起代码与文档同仓**（`medbox-server/` / `medbox-miniapp/` / `medbox-admin/` + `medbox-spec/`），不再需要跨仓库同步；V1.7 及以前的"代码仓库"表述指当时的独立仓库。

## [2026-10-25] fix(backend-devtools) 种子 ID 与真实数据隔离：`u-debug-guardian` / `BOXA-DEBUG-01` / `s-debug-001`

- **改了哪个文件**：`02` §1.5、`03` §5、`medbox-server/README.md` §11 的种子示例与 ID 表；`DevtoolsProperties` / `application.yml` / `DevtoolsSeedService` 的默认值；`mp-devtools` 的 `devtools-sim.js` 默认设备 ID 同步
- 背景：`guardian-user-id` 原为 `u-1001`，与 `user_biz_id_seq`（START 1001）生成的**首个真实注册用户**必然重名；库存 `s-1001` 同理撞首个真实库存；设备 `BOXA1001` 可能与真机序列号重名。轻则 `existsGuardian` 误判"种子已存在"整批跳过（前端显示"已播种"、库里没数据），重则 `clearSeed` 按固定 ID 把真实账号 / 设备 / 库存当残留删掉。
- 修正：会撞的三个 ID 加 `debug` 段（`u-debug-guardian` / `BOXA-DEBUG-01` / `s-debug-001`）；`e-1001` / `m-205` / `p-3301` / `pi-9001` 不在业务序列生成值域内，保留短 ID。`existsGuardian` 追加 `role=GUARDIAN` 校验，防止占用同 ID 的非种子账号再次触发静默跳过。历史库清理 SQL 见 `medbox-server/README.md` §11。
- 影响端：`medbox-server/` 是 · `medbox-miniapp/` 是（`mp-devtools` 默认设备 ID）· 嵌入式 否

## [2026-10-25] 小程序落地个人资料与角色视图（feat/mp-profile）

`feat/mp-profile`（08 清单 P1）落地：个人页「账号」卡升「个人资料」卡（`/users/me` 全字段，缺字段才静默补齐），按角色分化可见入口（登记 `ENTRY_ROLES`：监护台仅监护人、新增老人绑定引导卡）；消费后端 `feat/user-profile` 的 `GET /users/{elderId}/profile`，在监护页把 elderId 解析为老人姓名。

- **`medbox-miniapp/`**：`api/user.js` 补 `getElderProfile`；`api/auth.js` 的 `getMe` 支持可选 options（`silent` 背景刷新）；新增 `utils/profile.js`；`utils/permission.js` 登记 `alarmSettings` / `elderBindGuide`（**顺带修**：白名单为空导致「监护台」卡此前对老人可见）；改造 `pages/profile/profile.vue` 与 `pages/guardian/guardian.vue`（申请卡 elderId → "张桂英（u-1001）"，40302/40301/网络失败静默回退）
- **`medbox-miniapp/tools/mock`**：补 `GET /users/{elderId}/profile` 路由与权限口径（本人或 ACTIVE 可见，PENDING→40302，越权→40301 防枚举）；smoke 新增 8 项（共 **121 项**）
- **`02` 第 3 章**：补"小程序侧落地（feat/mp-profile）"——资料字段口径、角色入口登记、profile 接口唯一消费点与静默回退边界
- **`AGENTS.md`（仓库根）**：3.1 的 smoke 计数更正为 121（`feat/mp-devtools` 加项时漏更，停留在 104）
- **测试报告**：`npm test` 305 通过 / 1 跳过（本分支新增 27 例：api 8 + utils 17 + permission 2）；`npm run test:e2e` 121 通过 / 0 失败
- 影响端：`medbox-server/` 否 · `medbox-miniapp/` 是 · 嵌入式 否

## [2026-10-25] Flyway 迁移编号铁律：版本号 = 合并进 main 时的「最大版本号 + 1」，禁止按 spec 规划预留槽位

- **改了哪个文件**：`AGENTS.md` 新增 3.2 节（Flyway 迁移编号约定）；`06` 迁移列表顶部加铁律警告并改掉 V11/V12「预留槽位」措辞
- 背景：V11 被预留给 admin 种子、V12 先合、admin 隔天合，致已升到 V12 的库无法补跑 V11，Flyway out-of-order 启动失败（见上面 3.2 第 4 条处置办法）
- 影响端：`medbox-server/` 否（仅约定，无代码改动）· `medbox-miniapp/` 否 · 嵌入式 否

## [2026-10-24] feat(admin-testdata-cleanup) 测试数据清理 Web 工具（medbox-admin/）落地

- `09`：`feat/admin-testdata-cleanup` 勾选为已完成；第 4 点种子账号落地引用由 `V10` 顺延为 `V11`（与 CHANGELOG 同日条目一致）。
- `medbox-admin/`（新建，Vue3 + Vite + Element Plus + Pinia + Vue Router + Vitest）：`/login` 复用 `POST /auth/login`、ADMIN 路由守卫、axios 封装解包 `R<T>` 并映射 `40902/40301/40401/40102/40101`；`/test-data` 筛选条 + 用户表格 + 批量入口 + 顶部护栏横幅 + 二次确认弹框（手输 userId / 数量）+ 可选审计；前端护栏①默认视图仅显 DELETED 或 `test*`/`1380000*` 测试号。全部按 `02` 第 10 章 / 第 3 章契约调用，未新增后端接口。
- 测试：`npm test` **9 通过 / 0 失败**（api 层 4 + 护栏纯函数 5）；`npm run build` 通过。admin 暂无 Mock 端到端工具，以 Vitest 单测覆盖请求层与护栏逻辑。
- 影响端：`medbox-server/` 否 · `medbox-miniapp/` 否 · `medbox-admin/` 是（首个前端分支）· 嵌入式 否

## [2026-10-24] feat(device-sensor) 传感器子设备心跳消费 + 180s 离线判定 + `GET /devices/{id}/sensors` 落地

- **新增接口**：`GET /devices/{deviceId}/sensors`（鉴权，READ_DEVICE）返回该药箱下传感器子设备列表（`deviceId` / `deviceType` / `onlineState` / `lastHeartbeat`）。此前 08 清单 `feat/mp-device` 标注"该接口不存在、详情从 `sensors[]` 取"——现在两个入口都可用，小程序可按需切换。
- **子设备在线状态口径**（与 03 第 3 章 / 06 的 2.3 一致，无口径变更）：消费 `up/sensor/heartbeat`，`online=true` 置 ONLINE 并刷 `last_heartbeat`，`online=false` 立即 OFFLINE（不刷心跳）；每 60s 扫描，ONLINE 且超 **180s** 未刷心跳的子设备判 OFFLINE；**子设备离线不生成 DEVICE_OFFLINE 告警**；跃变写设备事件流水并推 WS `DEVICE_STATUS`（payload 带 `parentDeviceId`）。
- **新增配置** `medbox.device.sensor-offline-after-ms`（默认 180000）与 `medbox.device.sensor-scan-interval-ms`（默认 60000）。
- 影响端：`medbox-server/` 是 · `medbox-miniapp/` 否（可后续切用新接口，非必须） · 嵌入式 否

## [2026-10-24] feat(backend-admin-cleanup) 管理后台测试数据清理（ADMIN 接口）落地

- `07`：`feat/backend-admin-cleanup` 勾选为已完成；修正其 `V10__admin_seed.sql` 笔误为 `V11`（V10 已被 dispense 占用）。
- `02` 第 10 章 / `06` 的 2.1 / `09`：ADMIN 角色门禁、`GET /admin/users`（列出+筛选）、`DELETE /admin/users/{userId}`（硬删+级联清理、ACTIVE 拒绝 `40902`）、`UserRole.ADMIN` 与种子账号 `admin`/`public` 已落地；`02` 与 `09` 里残留的 `V10__admin_seed.sql` 引用统一顺延为 `V11`。
- 影响端：`medbox-server/` 是 · `medbox-miniapp/` 否 · `medbox-admin/` 否（前端 `feat/admin-testdata-cleanup` 待实现）

## [2026-10-24] docs(spec) 修正 _debug 端点鉴权口径：seed / mqtt 免登录（承接 mp-devtools 条目标记的待确认项）

- `medbox-spec/02` 的 1.5 原称 `_debug` 端点"仍被 `AuthInterceptor` 强制登录，`Authorization` 不能省"，与代码不符：`WebMvcConfig` 实际已把 `/api/v1/_debug/seed` 与 `/api/v1/_debug/mqtt/**` 从拦截范围单独排除（`DevtoolsControllerTest.noLoginRequired` 验证不带 `Authorization` 也返回 200）。该错误表述源自 `feat/backend-devtools` 落地时的文档，其后的"收窄 `_debug` 鉴权排除范围"修复只改了代码与 CHANGELOG、漏改 02，本次补齐。
- 同步修正 02 的 1.5 与 03 的 5.5 里的 curl 示例（去掉误导性的 `-H "Authorization: Bearer <任意已登录用户>"`），并补两点说明：① 免登录的理由是端点已由 `medbox.devtools.enabled` 门禁（关闭时 `DevtoolsController` 不装配、访问返回 `40401` —— 依赖 `application.yml` 的 `throw-exception-if-no-handler-found: true` + `add-mappings: false` + `GlobalExceptionHandler`）；② **同前缀的 `/_debug/ws/publish` 仍必须登录**（要走 `AccessService.require` 归属校验，依赖 `AuthContext`），两者免登录策略刻意不同，防止后来者把整个 `_debug/**` 一刀切。
- 顺带在 02 的 1.5 明确 `mqtt/publish` 的 `payload` 必须是 JSON **字符串**（后端 DTO 声明为 `String` 并原样透传，传对象会被 Jackson 拒掉）—— 这是小程序 `feat/mp-devtools` 联调时实际踩到的坑。
- 纯文档修正，**无代码与接口契约变更**；历史条目（含 2026-10-24 `feat/mp-devtools` 里"未改，需后端侧确认"的标注）按约定不回改，以本条为准。
- 影响端：`medbox-server/` 否（仅文档）· `medbox-miniapp/` 否 · 嵌入式 否

## [2026-10-24] feat/mp-devtools 落地：小程序「开发调试」页（运行时改 HOST + 联调说明 + 调试端点）

- `medbox-miniapp` 新增单页 `pages/devtools/devtools.vue`（「我的 → 开发调试」，需登录、不限角色）：**服务器地址**（当前地址 / 生效 URL 预览 / 输入保存 / 恢复默认，写本地缓存 `serverHost`，**重启后生效**）+ **局域网联调说明**（不校验合法域名勾选、真机调试（开发版）流程、只需配一个 HOST）+ **后端调试端点可点**（`POST /_debug/seed` 一键播种并回显双 token 可复制、自动回填设备 ID；`POST /_debug/mqtt/publish` 模拟上报，7 种事件预设**直接罗列在卡片里、点哪行发哪条**，报文与 03 §5 同形态）。**每行带「落库 / 仅日志」标签，发送后出结果面板**（topic + msgId + 效果说明 + 「去记录页查看」）：目前仅 `event/dispense` 有专属处理器（`dispense-consume` 已落地），`telemetry` / `sensor/heartbeat` / `event/error` / `inventory` 的后端分支未落地、仅被兜底处理器记日志不改数据 —— 提前标出以免误判。
- **服药类预设按后端两条约束构造**（`utils/devtools-sim.js`，页面亦有说明）：① `onTime` 由后端拿 `ts` 现算（`!actualTime.isAfter(planTime + 容忍)`，`DispenseConsumeService`），payload 的 `onTime` 被忽略 —— 故 `ts` 按设备本地墙钟（UTC+8）取计划槽后数分钟而非 `Date.now()`，否则下午点"按时"会落 `onTime=false`；② `med_record` 的 `(plan_item_id, plan_time)` 部分唯一索引（06 的 2.7 规则 6）使同槽只落一条，而 devtools 种子仅 1 个计划项 × 每日 08:00、**槽位有限** —— 故服药类每次点击经**持久化计数器**（`takeNextSlotOffset`，key `simSlotSeq`，刷新不归零）换一个新日历天，不会"点一次就废"（早期写死 今天/昨天/前天，点完三轮后必触发幂等，实测后修正）。
- 分层：`src/api/devtools.js`（请求）+ `src/utils/devtools-sim.js`（事件预设 / msgId / payload 序列化 / 错误文案归一，纯函数）+ `src/utils/devtools.js`（HOST 读写薄封装）；`src/config/index.js` 补 `getServerHost()` 并导出 `DEFAULT_HOST` / `STORAGE_KEY` / `PORT`（`setServerHost` / `clearServerHost` / `normalizeHost` 早已由 `feat/mp-request` 落地）。
- `tools/mock` 同步实现这两个 `_debug` 端点（**免登录**，与真后端 `WebMvcConfig` 排除 `seed` / `mqtt/**` 的口径一致；Mock 不接 EMQX，只做参数校验与回显，不落库不推 WS）；`npm run test:e2e` 增至 **113 项**（新增 9 项）。
- **顺带修一个测试隔离坑**：`config/__tests__/index.test.js` 的 helper 未隔离 `VITE_BASE_URL`，本机 `.env.local`（H5 反代联调）会把它漏进 `import.meta.env`，导致"H5 未配 VITE_BASE_URL 回落绝对地址"用例**本地必红、CI 绿**（CI 无 `.env.local`）。helper 里显式 `vi.stubEnv('VITE_BASE_URL', '')` 修复，各用例断言意图不变。
- ⚠️ **发现一处后端文档与代码不一致（未改，需后端侧确认）**：`medbox-spec/02` 的 1.5 称 `_debug` 端点"仍被 `AuthInterceptor` 强制登录，`Authorization` 不能省"，但 `WebMvcConfig` 实际已把 `seed` / `mqtt/**` 排除（免登录，见 `feat/backend-devtools` 2026-10-24 的修复）。前端按"带 token 但不强依赖"实现，两种行为下都能跑。
- 影响端：`medbox-miniapp/` 是（新页 + api/util 三处 + 一个测试 helper + mock 两端点）· `medbox-server/` 否 · 嵌入式 否

## [2026-10-24] 09 文档改造：转为分支清单，后端分支回归 07

- `medbox-spec/09` 由"管理后台设计稿"改为**特性分支清单**（结构同 `07`/`08`）：本期只认领 `feat/admin-testdata-cleanup`（测试数据清理 dev/QA 工具），原设计稿的定位 / 角色 / 前端方案 / 已确认事项折进该分支条目的「内容」；原"第六章 分支计划"里登记的 `feat/backend-admin-cleanup` 移除——该后端分支早已在 `07` 第三章单项登记，`07` 为唯一事实源，09 仅以依赖列引用。
- 同步更新：`00_README_文档索引.md`（09 改为分支清单描述）、`AGENTS.md` 0.1（`medbox-admin/` 本期有 09 分支清单 + 首分支 `feat/admin-testdata-cleanup`，去掉"无 09 清单 / 本期占位"陈旧表述；分支命名补充 `09`/`feat/admin-xxx`）、`07` 的 `feat/backend-admin-cleanup` 备注（指向 09 分支清单）、`01_系统架构与部署.md`（第 3 章 / 4.0 目录树 / 地址规划表中 `medbox-admin/` 的"本期不实现 / 无任务清单"陈旧表述改为指向 09 分支清单）。
- 影响端：`medbox-server/` 否 · `medbox-miniapp/` 否 · `medbox-admin/` 待 `feat/admin-testdata-cleanup` 认领（端内未动）· 嵌入式 否

## [2026-10-24] feat/backend-devtools 修复：WsDebugController 恒 401（收窄 _debug 鉴权排除范围）

- `WebMvcConfig` 原把整个 `/api/v1/_debug/**` 一刀切免登录，误伤需鉴权的 `WsDebugController`（`/api/v1/_debug/ws/publish`）：其 `publish()` 调 `AccessService.require` 依赖 `AuthInterceptor` 写入的 `AuthContext`，被排除后 `AuthContext` 恒为空 → 带合法 token 也恒返 401。
- 改为只排除 `DevtoolsController` 的 `seed` / `mqtt/**` 端点，`/api/v1/_debug/ws/**` 恢复走鉴权（与 `WsDebugController` 文档既定的"强制登录 + 归属校验、越权 40301"一致）；`DevtoolsControllerTest.noLoginRequired` 仍免登录不受影响。
- 影响端：`medbox-server/` 是（`config/WebMvcConfig` 一处）· `medbox-miniapp/` 否 · 嵌入式 否

## [2026-10-24] feat/backend-devtools 落地：联调辅助（种子数据 + 服务端模拟上报）

后端分支 `feat/backend-devtools`（P2）完成：新增 `devtools/` 包，提供两个调试端点（均受 `medbox.devtools.enabled` 开关门禁，默认关闭、生产零暴露面，路径落在 `/api/v1/**` 仍被 `AuthInterceptor` 强制登录）：
- `POST /api/v1/_debug/seed`：以系统身份经各 `Mapper` 直写最小数据集（1 监护人 `u-1001`/老人 `e-1001`/`ACTIVE` 监护关系 + 药箱 `BOXA1001` + 3 传感器 + 公共药 `m-205`/私有药 `m-206` + 库存 `s-1001` + 计划 `p-3301`/`pi-9001`），幂等（已存在跳过，`?reset=true` 先清后种），响应带监护人 token；
- `POST /api/v1/_debug/mqtt/publish`：复用 `MqttTransport` 发到 `medbox/{productKey}/{deviceId}/up/{subTopic}`，等价于服务端版 `mosquitto_pub`，流经真实入站链路；MQTT 未连返回 `50000`。
集成测试 `DevtoolsControllerTest` + `DevtoolsGatingTest` 共 9 例覆盖门禁/登录强制/seed 幂等与 reset/mqtt 拼装与错误码，全绿。

- **`02` 的 1.4**：新增 §1.5「快速起联调数据」，给 seed 与 mqtt/publish 示例并复用固定 ID（`u-1001`/`e-1001`/`BOXA1001`/`m-205`/`p-3301`/`pi-9001`）
- **`03` 第 5 章**：新增 §5.5「服务端模拟上报」，替代/补充 `mosquitto_pub`
- **`07`**：`feat/backend-devtools` 勾选 `[x]`
- 影响端：`medbox-server/` 是（新增 `devtools/` 4 文件 + `config/DevtoolsConfig` + application.yml `medbox.devtools` 段 + 集成测试）· `medbox-miniapp/` 否 · 嵌入式 否

## [2026-10-24] feat/record-core 落地：服药记录查询与监护人手工补录/校准

后端分支 `feat/record-core`（P0 第 11 步）完成：新增 `RecordController`（`GET /records` 分页筛选、`GET /records/{recordId}` 详情、`POST /records/{recordId}/confirm`）+ `RecordService`；列表/详情由后端 join 计划名/药名、`hasCapture`/`captureId` 供小程序缩略图占位与抓拍取用（文件流归 `capture-core`）；`confirm` 为**单接口双语义**——命中记录则校准（仅覆盖请求体非空字段、`source` 恒 `MANUAL`），未命中则新建（补录=创建，必填四项、校验计划/药品存在、路径 id 忽略并由 `med_record_biz_id_seq` 生成新 `mr-` 号返回、`planTime` 经就近槽重算或 NULL）。权限矩阵严格：写仅 FAMILY（老人写 40301、监护 PENDING 40302、越权 40301）、详情记录不存在 40401。不动 Flyway（字段/序列已由 `dispense-consume` 就绪）。

- **`02` 第 7 章**：新增 `confirm` 单接口双语义落地说明（校准 vs 新建、覆盖规则、`source=MANUAL` 恒定、新建忽略路径 id 由服务端生成、`planTime` 重算）
- **`06` 的 2.7**：补 `source` 在补录/校准恒为 `MANUAL`、新建 `planTime` 经就近槽重算或 NULL 的口径
- **`07`**：`feat/record-core` 勾选 `[x]`
- 影响端：`medbox-server/` 是（新增 `RecordController`/`RecordService`/`dto/record/` 三文件 + 集成测试 `RecordCoreWebIntegrationTest` 12 例）· `medbox-miniapp/` 否（联调待 `mp-record` 认领）· 嵌入式 否

## [2026-10-23] 后端落地用户资料查询 `GET /users/{elderId}/profile`（feat/user-profile）

- **`02` 第 3 章**：补 `GET /users/{elderId}/profile` 响应示例与可见性口径（老人本人 / ACTIVE 监护人可见；`PENDING`→40302、无关系/`REVOKED`/目标非老人→40301 防枚举）；1.3 的"未做（有意）"更新为"已由 `feat/user-profile` 交付"
- **`07`**：勾选 `feat/user-profile`
- 影响端：`medbox-server/` 是 · `medbox-miniapp/` 是（`mp-profile` 分支按此口径拉老人资料）· 嵌入式 否

## [2026-10-23] feat/miss-alarm 落地：定时扫描漏服、生成 MISS 告警并经 WS 推送

后端分支 `feat/miss-alarm`（P0 第 10 步）完成：仅定时扫描（`@Scheduled` 每 60s，窗口 `[now-lookback, now-tolerance]`），按设备时区展开 `times` 槽、比对 `med_record`（三级容忍 `MissToleranceResolver`），**任一 item 缺失即生成 1 条 MISS**（`message` 列全部缺失药，`medicine_id` 记第一个），落 `alarm` 后推 `ALARM` 帧；`dedup_key = MISS:{planId}:{slotUTC}` + 部分唯一索引去重，重启/重扫同槽不重复告警。跳过规则：计划非 ENABLED、`sync_state=SYNC_FAILED`、主控设备离线（`medbox.alarm.miss.skip-when-device-offline` 默认开）。本分支只落库 + 推 WS，**REST 列表/已读/处理归 `feat/alarm-core`**。

- **`06` 的 2.9**：`alarm` 补 `alarm_biz_id_seq`（前缀 `a-`，坑同 `med_record_biz_id_seq` 不叫 `alarm_id_seq`）、`dedup_key` 列 + 部分唯一索引 `uk_alarm_dedup_key`；沉淀 🧭 判定规则表（8 条）；Flyway 清单补 `V12__miss_alarm.sql`（V11 已预留给 admin 种子，本迁移用 V12）
- **`02` 第 8 章**：MISS 条目补"由 `feat/miss-alarm` 生成并推 WS、REST 归 `alarm-core`"说明
- **`07`**：`feat/miss-alarm` 勾选 `[x]`
- 影响端：`medbox-server/` 是（新增 `MissAlarmScanner` / `PlanSlotResolver`（抽自 dispense 的就近槽）/ `Alarm` 实体 + `AlarmMapper`、三处 Mapper 查询、`SchedulingConfig`（把 `@EnableScheduling` 从 `WsConfig` 挪出，与 WS 开关解耦）、`medbox.alarm.miss.*` 配置、`V12__miss_alarm.sql`）· `medbox-miniapp/` 否 · 嵌入式 否

## [2026-10-23] feat/dispense-consume 落地：消费 up/event/dispense 落 med_record + 扣库存 + 业务幂等

后端分支 `feat/dispense-consume`（P0 第 9 步）完成：新增 `up/event/dispense` 处理器，按"计划时间就近槽 + 单侧容忍窗口"重算 `onTime` 落 `med_record`（含 `capture_id`），按识别到的药品扣减 `medicine_stock`（`actualDose` 解析失败取 1、不足扣到 0、错服也扣），两层幂等（`msgId` 网关已完成 + `med_record` 部分唯一索引 `(plan_item_id, plan_time)` 冲突回滚），落库后发 `DispenseRecordedEvent` 供 `miss-alarm` / `wrong-drug-alarm` 消费（本分支不推 WS、不生成告警、不提供 REST）。

- **`06` 的 2.7**：`med_record` 补 `record_id` 前缀 `mr-`（`med_record_biz_id_seq`，`START 1001`，坑：序列不能叫 `med_record_id_seq`）、`source` 列、`V10__dispense.sql` 索引；沉淀 🧭 判定规则表（准入 / 就近槽 / 三级容忍 / 单侧窗口 / 库存 / 幂等 / 事件）；Flyway 清单补 `V8`/`V9`/`V10`，原预留的 `V10__admin_seed.sql` 顺延到 `V11`
- **`06` 的 2.5**：补"库存双重扣减"口径——`dispense` 权威扣减，`inventory-consume` 只按 `quantity` 绝对值校准、不做 delta 累加
- **`03` 第 3 章 / 第 4 章**：`onTime` 后端重算算法（就近槽 + 单侧窗口 + 三级容忍）指针，指向 06 §2.7
- **`07`**：`feat/dispense-consume` 勾选 `[x]`
- 影响端：`medbox-server/` 是（新增 `DispenseUpMessageHandler` / `DispenseConsumeService` / `DeviceTimezoneResolver` / `MissToleranceResolver` / `MedRecord` / `AlarmSetting` 及 Mapper、`MedicineStockMapper.deduct`、`V10__dispense.sql`，并把 `PlanService.resolveTimezone` 委托给 `DeviceTimezoneResolver`）· `medbox-miniapp/` 否 · 嵌入式 否

## [2026-10-23] 小程序落地监护绑定（申请 / 待确认 / 确认 / 解除，feat/mp-guardian）

`feat/mp-guardian`（08 清单 P1 第 1 项）落地：监护人输入老人用户ID 发起申请（PENDING 即时可见、可撤销），老人端待确认列表确认（指定 FAMILY / DOCTOR / NURSE）或拒绝。后端 `guardian-relation` 四条接口已落地，本分支按 02 的 3.2 契约实现，**无前端先行契约**。

- **`medbox-miniapp/`**：新增 `api/guardian.js`（四条接口封装）、`utils/guardian.js`（角色 / 状态文案、`parsePendingList`、`normalizeElderId`）、`store/guardian.js`（"我的申请"本地缓存，`clearSession` 即清）、`pages/guardian/guardian.vue`（按角色分屏；注册 `pages.json`，首页功能入口加"监护关系"一行）
- **`medbox-miniapp/tools/mock`**：补齐四条路由与权限口径（非监护人 / 目标非老人 / 查不到 / 非关系双方 → 40301 防枚举；PENDING·ACTIVE 重复申请 → 40901；REVOKED 复活清 role；非法 / 缺失 role → 40001；重复确认 → 40901）；种子 `relations` 补 `createdAt`；smoke 新增 26 项（共 **104 项**）
- **`02` 的 3.2**：补"小程序侧落地"小节 —— "输入老人账号"= 用户ID（无按手机号查号接口）、"我的申请"本地视图边界、撤销 vs 移除记录语义
- **`AGENTS.md`（仓库根）**：3.1 的 Mock 检查项计数由过期的"46 项"更正为 104 项
- **测试报告**：`npm test` 232 通过 / 1 跳过（本分支新增 36 例：api 11 + utils 11 + store 14）；`npm run test:e2e` 104 通过 / 0 失败
- 影响端：`medbox-server/` 否 · `medbox-miniapp/` 是 · 嵌入式 否

## [2026-10-22] feat/plan-sync 落地：计划经 MQTT 下行到设备并回执驱动 sync_state

后端分支 `feat/plan-sync`（P0 第 8 步）完成：保存/启停/删除服药计划后，经 `down/schedule` 把计划（含 `items` 数组）推送到老人 MAIN 药箱；设备 `up/ack` 成功→`med_plan.sync_state=SYNCED_TO_DEVICE`，回执 `FAILED` 或 5s 超时（重发 3 次）放弃→`SYNC_FAILED`。

- **`03` 第 3 章**：`down/schedule` 补 `op=DELETE` 语义（删除/停用下发，其余字段可空）与 `sync_state` 回执驱动口径
- **`02` 第 6 章 D 段**：`syncState` 终态由 `feat/plan-sync` 写入（已实现），补前端触发映射（UPSERT/DELETE 与回执口径）
- **`07`**：`feat/plan-sync` 勾选 `[x]`
- 影响端：`medbox-server/` 是 · `medbox-miniapp/` 否（仅解锁 mp-plan 同步状态回显的契约）· 嵌入式 否

## [2026-10-22] 拆分管理后台文档：后端接口契约归位 02/06，09 仅留前端页面

`09` 原把 ADMIN 接口契约、级联删除、角色建模与前端页面混在一起。按「02 是 REST 唯一权威、06 是库表唯一权威」口径拆分：

- **`02` 第 10 章新增《管理员接口（测试数据清理）》**：收编 `09` 原第三/四章 —— `GET /admin/users`、`DELETE /admin/users/{userId}`（硬删+级联、ACTIVE 拒绝 `40902`）、级联清理顺序；**错误码 `40902` 补入 1.2**（活跃账号禁止经管理接口硬删）
- **`06` 的 2.1 `user.role` 补 `ADMIN`**、`V10__admin_seed.sql`（种子管理员账号）补入 Flyway 清单
- **`07` 补 `feat/backend-admin-cleanup`** 分支条目（此前清单缺失）
- **`09` 精简**：删除后端接口/角色/级联章节，改为指向 02 第 10 章 + 06；仅保留定位、前端方案（Vue3+Element Plus、`/test-data` 清理页）、前端侧安全护栏、分支计划与已确认事项（决策记录）
- 影响端：`medbox-server/` 是（实现 `feat/backend-admin-cleanup` 时照 02 第 10 章 + 06 V10）· `medbox-miniapp/` 否 · 嵌入式 否 · `medbox-admin/` 是（前端按 09 第六章 + 02 第 10 章调用）

## [2026-10-21] 新增管理后台设计文档 `09`（medbox-admin · 测试数据清理工具）

- **新增 `medbox-spec/09_管理后台设计_medbox-admin.md`**：规划 Web 管理后台 `medbox-admin/`，本期只做「测试数据清理」dev/QA 工具；含 ADMIN 角色方案、`GET /api/v1/admin/users` 列出 + `DELETE /api/v1/admin/users/{userId}` 级联硬删、级联范围、安全护栏、前端（Vue3+Element Plus）方案与分支计划
- **索引更新（`00_README_文档索引.md`）**：把「暂无 09」替换为对 `09` 的引用；目录约定表里 `medbox-admin/` 由「占位暂不实现」改为「本期先做测试数据清理工具」
- **新增错误码（待落地时补入 02 第 1.2）**：`40902` 活跃账号禁止经管理接口硬删（仅设计，尚未写代码）
- 影响端：`medbox-server/` 否（纯文档）· `medbox-miniapp/` 否 · 嵌入式 否

## [2026-10-21] 小程序落地服药记录（列表 / 详情 / 补录，feat/mp-record）

`feat/mp-record`（07 清单 P0 第 7 步）落地：记录列表与按时间 / 老人筛选、记录详情（计划与药品）、监护人补录 / 确认。

- **`medbox-miniapp/`**：新增 `api/record.js`（`listRecords` / `getRecord` / `confirmRecord`）+ `utils/record.js`（分页容错、按时 / 错服标签、时间本地化）；改造 `pages/record/record.vue`（老人选择器复用 `GET /devices` 归纳 elderId + 日期区间筛选 + 状态标签）、新增 `pages/record/detail.vue`（抓拍留证占位、监护人补录 / 确认入口仅 FAMILY 可见，已注册 `pages.json`）
- **`medbox-miniapp/tools/mock`**：新增记录假数据、三条路由（列表 / 详情 / 补录）与权限口径（老人读他人 / 越权 40301、监护 PENDING 40302、写仅 FAMILY、不存在 40401）；`smoke.js` 新增 12 项记录检查（共 **78 项**），README 同步
- **⚠️ 后端 `record-core` 未落地**：分页响应暂定 `{list, total}`（前端 `parseRecordsPage` 已容错 `records`/`items` 与裸数组）、详情由后端 join 计划名 / 药品名、`confirm` 仅 FAMILY 可调用，后端定稿后三处收紧；**抓拍留证依赖 `capture-core`（P1，feat/mp-capture），本期详情页缩略图留空占位**
- 影响端：`medbox-server/` 否 · `medbox-miniapp/` 是 · 嵌入式 否

## [2026-10-21] 新增服药计划后端 `feat/plan-core`（计划 CRUD + 未来待服时间线）

- **后端新增接口（02 第 6 章）**：`POST/PUT/PATCH/DELETE/GET /plans` 与 `GET /plans/{planId}/next-doses`；写仅 `WRITE_PLAN`（FAMILY+DOCTOR）、读 `READ_PLAN`（全角色），越权统一 40301
- **`med_plan` 新增 `sync_state` 列（V9，默认 `PENDING`）**：本期只置 `PENDING`，真正的 `SYNCED_TO_DEVICE`/`SYNC_FAILED` 状态机归 `feat/plan-sync`；动手前先读 06 §2.6 与 `V9__plan.sql` 注释（V1~V8 别改）
- **时区口径不变**：`times` 仍是设备本地墙钟，next-doses 按老人 MAIN 设备 `device.timezone`（缺省 `Asia/Shanghai`）换算 `scheduledTimeUtc`/`scheduledTimeLocal`；本期 `repeatRule` 仅 `DAILY`
- 影响端：`medbox-server/` 是 · `medbox-miniapp/` 否（时间线页仍归 P1 `feat/mp-plan-timeline`）· 嵌入式 否

## [2026-10-20] 消除 WS 关闭时的 `closeSocket:fail WebSocket is not connected` 噪声

小程序控制台在后端不可达时会被这条错误刷屏，掩盖真正的排错信息（哪个 HTTP 接口失败、WS 为什么握手失败）。

- **根因不在"页面忘了判断"**：WS 是本层单例（`store/ws.js`），页面碰不到 socket。真正的原因是 `openSocket()` **每次重连都要调一次 `closeSocket()`**，而上一次握手失败时 `handleDisconnect` 已把 `socket` 置 null，于是落进 `else` 分支去调**全局** `uni.closeSocket()` —— 底层此刻并没有连接可关，于是报错。**后端没起时这条链路每 1s→2s→…→30s 触发一次**
- **修复**：① 手上没有 task 就**不去关**（只有确实用全局 API 接过连接才关）；② 关闭前看 `SocketTask.readyState`，非 OPEN 直接跳过；③ `close()` 带 `fail` 回调，让底层的拒绝被显式吃掉而不是变成 `MiniProgramError`
- **顺带修了同源问题**：心跳 `send()` 前同样校验 `readyState`（否则会报 `sendSocketMessage:fail`）；迟到的旧连接在它自己的 `onOpen` 里被关掉（世代号已过期，不关会一直占着小程序仅有的 5 条连接额度）
- 回归用例 6 条，并做了变异验证：把旧逻辑改回去时用例如期失败，`stderr` 里复现了 `closeSocket:fail WebSocket is not connected`

## [2026-10-20] 小程序统一配置出口：兜底改回环地址、TLS 开关移入 `.env`（`feat/mp-server-config`）

整理 `medbox-miniapp/` 的网络配置口径。地址 / 协议（REST / WS 路径、8080 端口）**均未变**，改的是"没配环境变量时怎么兜底"与"开关放哪个文件"。

- **`medbox-miniapp/`**：`src/config/index.js` 兜底 HOST 由 `192.168.1.10` 改为 **`127.0.0.1`**（语义"后端跑在本机"，不会误连别人的内网 IP；真机仍需 `.env.local` 填局域网 IP）；`setServerHost()` 写入前**归一化**（剥协议 / 端口 / 路径，容忍粘贴整串地址），非法输入不再写空串而是返回 `false`；新增导出 `normalizeHost()`
- **`medbox-miniapp/` 环境变量分工**：`VITE_TLS` 从 `.env.development` 移到入库的 **`.env`** —— Vite 的 `.env.[mode]` 只在对应 mode 加载，`uni build`（production）读不到 `.env.development`，开关打包后恒为 `false`，"上线切 https / wss"会静默失效。`.env.development` 保留为 development 专属入口（当前为空）
- **`medbox-miniapp/README.md`**：配置三层表补 `.env`、写清 mode 差异与优先级链；`tools/mock` 端口被占（多半是 `medbox-server` 还在跑）时报错说明并退出；mock 头注释与检查项数量对齐实际（**65 项**）
- **⚠️ 踩坑记录：`VITE_BASE_URL` 现在只在 H5 生效**（原委见 README「浏览器联调」）：它是**编译期常量**，以前写进 `.env.local` 后小程序产物也带着它，`BASE_URL` 变成相对路径 `/medbox/api/v1`，小程序会把请求打到**自己的 origin** 上 —— 表现为"H5 能登录、小程序登录不了"，报错还是 `code=-1` 网络异常，与 IP 填错完全一样。修复：`src/config/index.js` 用 `process.env.UNI_PLATFORM === 'h5'` 做平台隔离，小程序 / App 编译时忽略该变量且给出告警。**同一份 `.env.local` 现在可以安全地同时喂 H5 与微信小程序**

## [2026-10-20] 后端落地药品档案与库存（`feat/medicine-core`）

`feat/medicine-core`（07 清单 P0 第 6 步）落地：药品档案 CRUD + 药箱库存录入 / 补药，打通"公共药品 + 我监护老人的私有药品"的可见范围与写权限。

- **`medbox-server/`**：新增 `Medicine` / `MedicineStock` 实体、`MedicineMapper` / `MedicineStockMapper`（业务号 `m-1001` / `s-1001` 走 `medicine_biz_id_seq` / `medicine_stock_biz_id_seq` 序列）；`MedicineService`（save / list / detail / delete）+ `MedicineStockService`（list / save / in / delete）；`MedicineController`（`/api/v1/medicines`）+ `MedicineStockController`（`/api/v1/devices/{deviceId}/stocks`）
- **权限口径（02 第 5 章）**：公共药写 = 新增 `AccessService.requireFamilyGuardian()`（任一 FAMILY 监护人）；私有药写 = `require(ownerElderId, WRITE_MEDICINE)`；列表可见 = 公共 + `visibleElderIds()`；**`ownerElderId` 归属不可变**（更新接口忽略请求里的值）；越权一律 40301、删除有库存引用 40901、库存引用药品不存在 40401
- **`06` 的 V8 已落地**：Flyway `V8__medicine.sql` 建 `medicine_biz_id_seq` / `medicine_stock_biz_id_seq` 两个业务号序列（⚠️ 不能叫 `medicine_id_seq`，那是 `medicine.id BIGSERIAL` 自动建的序列，`CREATE SEQUENCE IF NOT EXISTS` 会静默跳过导致业务号编成 `m-1`）
- **测试**：`./gradlew test` 共 **264 例**（执行 259、跳过 5、失败 0），本分支新增 `MedicineCoreWebIntegrationTest` **15 例**（MockMvc 打完整链路，`@Primary` 内嵌 H2 + Mapper `@MockitoBean` 打桩）
- 影响端：`medbox-server/` 是 · `medbox-miniapp/` 否 · 嵌入式 否

## [2026-10-20] 小程序落地服药计划（`feat/mp-plan`）

`feat/mp-plan`（08 清单 P0 第 6 步，🧭 判定型）落地：计划列表（老人选择器 + 药品概览 + 启停开关 + `syncState` 徽标）+ 创建 / 编辑（一次提醒多种药、**时间为设备本地墙钟**、过期 / 无库存药品拦截）+ 删除（级联明细）；规则表经用户确认后沉淀进 02 第 6 章。

- **新增 `src/api/plan.js`**（计划六接口）与 **`src/utils/plan.js`**（规则表纯函数：`normalizeTimes` / `buildPlanPayload` / `pickableMedicines` / `parsePlansPage` / 文案函数）；改造 `pages/plan/plan.vue` + 新增 `pages/plan/edit.vue` 与 `components/PlanItemRow.vue`
- **⚠️ 后端 `plan-core` / `plan-sync` 未落地**（07 未勾选）：按 02 第 6 章先行实现；**`times` 为设备本地墙钟、前端不做时区换算**（提交前去重 + 升序）；PATCH 请求体先行契约 `{status:'ENABLED'|'DISABLED'}`；分页响应容错 `{list,records,items}+total`；`repeatRule` 先提供 DAILY / WEEKLY —— 后端定稿后三处收紧并同步 02 第 6 章
- **规则表已按 AGENTS.md 3.1.2 经用户确认并沉淀进 02 第 6 章**（A 时间墙钟口径 × B items 组装 × C 过期/无库存 40901 拦截 × D 启停与 syncState 契约 × E 权限与老人上下文 × F 本期不做项）
- **mock 后端扩展计划域**：`tools/mock` 种子增 3 个计划 / 4 条明细，服务端增计划六端点（含 40901 无库存/过期校验、同计划药品去重 40001、PATCH 契约、级联删除）；**测试**：`npm test` 共 **151 例**（通过 150、跳过 1 —— 跳过的是需本机后端的 `ws.local.test.js`），本分支新增 24 例；`npm run test:e2e` 共 **64 例全通过**（新增 18 例计划用例）；`npm run build:mp-weixin` 编译通过
- 影响端：`medbox-server/` 否 · `medbox-miniapp/` 是 · 嵌入式 否

## [2026-10-19] 小程序 Mock 后端移入 `tools/mock`，端到端测试纳入 AI 完工流程

后端接口未落地期间的前端自测底座：Mock 后端迁到 `medbox-miniapp/tools/mock/`，新增 `npm run test:e2e` 与 `test:all`，并把"单元测试后自动跑一遍 mock 端到端"写进 AGENTS.md。

- **新增 `medbox-miniapp/tools/mock/`**：`src/server.js`（按 02 的形状 + 错误码 + 权限口径）+ `src/data.js`（06 的 2.1~2.5 种子数据，故意覆盖正常/临期/过期/未填四种库存状态）+ `test/smoke.js`（46 项端到端，自起服务、18099 端口、跑完即关）；`package.json` 新增 `mock` / `test:e2e` / `test:all` 脚本
- **`AGENTS.md` 3.1 与第 7 节自检清单**：小程序功能分支完工时，`npm test` 之后**自动跑 `npm run test:e2e`**，两份结果一并贴报告；08 顶部"通行做法"同步补了该约定
- **顺带修复**：`pages/medicine/edit.vue` 的 `.row > *` 通配符 WXSS 不支持（H5 能过、小程序编译报错），改显式类 `row__item` —— 属 `mp-medicine` 页面，提交时注意分支归属
- 影响端：`medbox-server/` 否 · `medbox-miniapp/` 是（新增 `tools/`，测试流程变化）· 嵌入式 否

## [2026-10-19] 小程序落地药箱库存（`feat/mp-stock`）

`feat/mp-stock`（08 清单 P0 第 5 步）落地：药箱库存清单（按药品展示数量 / 生产日期 / 有效期 + 临期过期标签）+ 录入更新（PUT，绝对值）+ 补药入库（POST `/in`，增量、可同步更新日期）+ 移除（DELETE）；入口在设备详情页。

- **新增 `src/api/stock.js`**（库存四条）与 **`src/utils/stock.js`**（`parseStocks` 容错 / `joinStockMedicine` 药品 join / `expiryTag` 复用 `datetime.expiryState` / `buildStockPayload` / `buildRestockPayload`）；新增 `pages/stock/stock.vue` 与 `pages/stock/edit.vue`（日期用 `picker mode="date"`，DATE 列无时刻、**不做 UTC 换算**），`pages/device/detail.vue` 加入口行
- **⚠️ 后端 `medicine-core` 未落地**（07 未勾选）：按 02 第 5 章先行实现；**stocks 响应暂按裸数组**（容错 `{list}` 等）、**PUT 数量为绝对值 / POST `/in` 为增量**、**行级不内嵌药品摘要**（小程序拉药品字典 join，超出首页 100 条回退显示 `medicineId`）—— 后端定稿后收紧并同步 02 第 5 章新补的"库存四条 · 小程序侧口径"6 条
- **写权限**：四条均属 `WRITE_MEDICINE`（仅 FAMILY），只给监护人显示写入口；`GET /devices/{id}/expiring` 明确归 P1 `mp-expiring`，本分支未实现
- **测试**：`npm test` 共 **127 例**（通过 126、跳过 1 —— 跳过的是需本机后端的 `ws.local.test.js`），本分支新增 27 例（请求层 7 + 纯函数 20）；`npm run build:mp-weixin` 编译通过
- 影响端：`medbox-server/` 否 · `medbox-miniapp/` 是 · 嵌入式 否

## [2026-10-18] 07 / 08 补跨端依赖标注，《跨端依赖速查表》上线

三人并行（后端 P0 / 后端 P1 / 小程序）后，只写本端依赖不够排期，故把"对端依赖"补进两份清单。

- **07**：顶部约定"依赖列只写本端"；新增《五、跨端依赖速查表》= 后端分支 → 解锁哪些小程序分支的**反向索引**（由 08 的依赖列推导，**08 是唯一事实源**）；**`GET /devices/{deviceId}/expiring`（临期/过期清单）此前无任何分支认领，现明确归 `feat/medicine-core`**（`feat/stock-expiring` 只做定时扫描与 EXPIRED 告警，不重复提供该查询）
- **08**：依赖列统一拆成「本端 / 后端」两段并补全缺失项 —— `mp-ws`→`backend-ws`、`mp-plan`→`plan-sync`（`syncState`）、`mp-plan-timeline`→`plan-core`、`mp-record`→`capture-core`（缩略图）、`mp-expiring`→`medicine-core`、`mp-alarm`→`plan-reminder` + 5 个告警来源分支、`mp-env`→`alarm-core`（阈值高亮）
- **通行做法写进 08 顶部**：后端接口未就绪时，按 02 的字段定义先行实现 + 字段容错，后端定稿后收紧（`feat/mp-medicine` 已验证可行）
- 影响端：`medbox-server/` 是（`/expiring` 接口归属待实现时遵守） · `medbox-miniapp/` 否 · 嵌入式 否

## [2026-10-18] 小程序落地药品档案 CRUD（`feat/mp-medicine`）

`feat/mp-medicine`（08 清单 P0 第 4 步）落地：药品列表（分页 + 关键词搜索）+ 详情（禁忌 / 储存条件）+ 新建编辑（归属公共或某老人）+ 删除（40901 库存引用提示）。

- **新增 `src/api/medicine.js`**（`listMedicines` / `getMedicine` / `saveMedicine` / `deleteMedicine`，新建与更新同走 `POST /medicines`）与 **`src/utils/medicine.js`**（payload 组装 / 分页容错 / 归属文案 / `canWriteMedicine` 纯函数）；列表页 `pages/medicine/medicine.vue` 改造 + 新增 `detail.vue` / `edit.vue`（已注册 `pages.json`）；`MbField` 扩 `type="textarea"`
- **⚠️ 后端 `medicine-core` 未落地**（07 未勾选）：本分支按 02 第 5 章先行实现，联调待后端；**分页响应列表字段名暂定 `list`**（前端容错 `records` / `items`），后端定稿后收紧并同步 02 第 5 章新补的"小程序侧落地口径"5 条
- **写入口仅监护人可见**：`WRITE_MEDICINE` 仅 FAMILY（02 的 1.3 矩阵），老人端只读；relationRole 未暴露前用 GUARDIAN 代理，服务端强校验不变
- **测试**：`npm test` 共 **100 例**（通过 99、跳过 1 —— 跳过的是需本机后端的 `ws.local.test.js`），本分支新增 18 例（请求层 8 + 纯函数 10）；`npm run build:mp-weixin` 编译通过
- 影响端：`medbox-server/` 否 · `medbox-miniapp/` 是 · 嵌入式 否

## [2026-10-18] 小程序 Node 版本统一为 24

CI 与本地开发环境的 Node 版本由 20 统一到 24。Vite 5.2.8 的 engines 为 `^18 || >=20`（24 在支持范围），本机 Node 24 实测 `npm ci` / `npm test` / `build:mp-weixin` 全通，`package-lock.json`（lockfileVersion 3）无需改动。

- **`ci-miniapp.yml`**：`setup-node` 的 `node-version` 20 → 24；`README.md` 与 `01` 的 CI 描述同步
- 影响端：`medbox-server/` 否 · `medbox-miniapp/` 是（本地建议用 Node 24）· 嵌入式 否

## [2026-10-18] 小程序落地设备列表与详情（`feat/mp-device`）

`feat/mp-device`（08 清单 P0 第 3 步）落地：设备列表页展示当前用户可见的药箱主控 + 在线状态，点入详情看设备档案与传感器子设备，在线状态靠 WS 的 `DEVICE_STATUS` 帧实时刷新。

- **新增 `src/api/device.js`**（`listDevices` / `getDevice`，全需鉴权）与 **`src/utils/device-status.js`**（`toOnlineState` / `applyStatus`：把 WS 的 boolean `online` 合并成 REST 的 `onlineState`，按 `deviceId` 匹配）；列表页 `pages/device/device.vue` 改造 + 新增详情页 `pages/device/detail.vue`（已在 `pages.json` 注册）
- **WS 实时刷新**：列表/详情订阅 `DEVICE_STATUS` 按 `deviceId` 匹配更新；`RESYNC` 重拉 REST 全量；监护人列表页从列表项 `elderId` 归纳订阅主题（老人端 App.vue 已起连自己）
- **⚠️ 口径修正（08 两处与实际后端不符）**：① 传感器子设备**没有** `GET /devices/{id}/sensors`（归 P1 `device-sensor`），改从详情接口 `GET /devices/{deviceId}` 的 `sensors[]` 取；② "详情含最新遥测"当前不可达（`env_sample` 无查询接口，温湿度/光照读数留待 `mp-env`），本期只展示在线状态 + 最后心跳
- **测试**：`npm test` 共 **82 例**（通过 81、跳过 1 —— 需本机后端的 `ws.local.test.js`），本分支新增 8 例（请求层 3 + 状态合并 5）；`npm run build:mp-weixin` 编译通过
- 影响端：`medbox-server/` 否 · `medbox-miniapp/` 是 · 嵌入式 否

## [2026-10-15] 小程序落地注销账号入口（`feat/mp-user-delete`）

`POST /users/me/delete` 的前端闭环已通：个人页"危险操作"卡片 → 二次确认弹窗输密码 → `store/auth.js` 的 `deleteAccount()` → 清凭据 + 断 WS + `reLaunch` 登录页。注销链路的另一端（后端接口已于 10-14 就绪）至此可端到端联调。

- **新增 `src/api/user.js`**（用户域接口首例，全是需鉴权接口，与免鉴权的 `api/auth.js` 分开）：`deleteAccount(password)` 走 `{ silent: true, loading: '注销中' }`，**不带 `noAuthRetry`**
- **注销编排只在 `src/store/auth.js`**（与 `logout()` 并列）：成功后 `clearSession()` + `disconnect()` + `reLaunch`；**失败原样抛 `ApiError`** 由页面渲染（40102 / 40001 / -1 都**保留**本地凭据，只有 `code=0` 才清）
- **`MbModal` 扩了三个 prop**（`confirmType="danger"` / `confirmLoading` / `closeOnConfirm=false`）：危险操作的弹窗**关闭时机交给页面**，否则提交中弹窗自关、密码错了还得重输一遍；`MbButton` 补 `type="danger"`
- ⚠️ **口径补充（02 的 3.1）**：服务端不踢 WS 连接，但**客户端注销成功后主动 `disconnect()`** —— 不该继续用已注销身份订阅 `elder:{id}`
- **测试**：`npm test` 共 **74 例**（通过 73、跳过 1 —— 跳过的是需本机后端的 `ws.local.test.js`），本分支新增 11 例（请求层 7 + 编排层 4）；`npm run build:mp-weixin` 编译通过
- 影响端：`medbox-server/` 否 · `medbox-miniapp/` 是 · 嵌入式 否

## [2026-10-14] 后端落地账号注销（`feat/backend-user-delete`）

`POST /api/v1/users/me/delete`（**密码二次确认**）已可用：软删除账号并释放手机号、撤销全部 refresh token、双向解除监护关系；此后旧密码登录 40102、刷新 40101。**小程序端入口未做**（08 的 `feat/mp-user-delete`）。

- ⚠️ **踩坑（写注销类接口必读）**：MyBatis-Plus 的 `updateById` **默认忽略 `null` 字段** —— `setPhone(null)` 不会进 UPDATE 语句，"置空手机号释放唯一键"会**静默失败**。注销走 `UserMapper` 的 `@Update` 手写 SQL（同理不用 `LambdaUpdateWrapper`：Mapper 打桩时 `set(...)` 立即解析列名会抛 `can not find lambda cache`）
- **⚠️ 口径修正**：02 的 3.1 草稿里"拦截器识别 `DELETED`"与"旧 access 2 小时内仍可用"矛盾，**实现取后者、不改 `AuthInterceptor`**（保持 JWT 无状态、不每请求查 `user` 表）。判定 `DELETED` 的三处：`AuthService.login` / `refresh`、`UserService.me`
- **`06` 的 V7 已落地**：`"user"` 表加 `status` / `deleted_at`；**业务数据不级联删除**（用药记录留痕，且保留 `user` 行才能让历史记录里的 `user_id` 继续成立）
- **测试**：`./gradlew test` 共 **249 例**（执行 244、跳过 5、失败 0），本分支新增 16 例
- 影响端：`medbox-server/` 是 · `medbox-miniapp/` 是（`feat/mp-user-delete` 现在可以联调：注销成功后清本地凭据 + `reLaunch` 登录页）· 嵌入式 否

## [2026-10-14] 新增**账号注销**设计（此前只有"退出登录"，接口侧没有注销）

调试时随手注册的账号无法清掉（退出登录只在客户端清凭据、不调后端），故补上注销。**当前只做文档设计，代码未实现** —— 后端 `feat/backend-user-delete`、小程序 `feat/mp-user-delete` 两条待办已分别进 07 / 08 的 P1。

- **`02` 新增 3.1 注销账号**：`POST /users/me/delete`（**密码二次确认**）+ 规则表（校验顺序 × 软删除数据处理优先级 × 7 条边界用例）；⚠️ **注销接口不能放 `/auth/**` 下**（该路径在拦截器里是放行的，注销必须先登录）
- **`06` 新增 Flyway `V7__user_delete.sql`**：`"user"` 表加 `status` / `deleted_at`；注销 = **软删除**（置 `DELETED` + `phone`/`username` 置 `NULL` 释放唯一键，**业务数据不级联删除**）；`REVOKED` 状态由此成为"注销"的主动触发点（此前只有重放保护被动触发）
- **已知口径（后续分支遵守）**：注销**不引入 access token 黑名单**，旧 access 最长 2 小时仍可用，靠 refresh 已撤销兜底；注销后 refresh / 旧密码登录分别返 40101 / 40102
- 影响端：`medbox-server/` 是（`feat/backend-user-delete` 待实现）· `medbox-miniapp/` 是（`feat/mp-user-delete` 待实现，依赖后端先落地）· 嵌入式 否

## [2026-10-13] 修复 `feat/device-core` 集成测试在 CI 全红（500）

`DeviceService.register` 是 `@Transactional`，CI 无 PostgreSQL → 进入方法时开连接失败 `CannotCreateTransactionException` → 统一变 500；本地装了 Postgres 故一直绿（且 Gradle 构建缓存会放大"本地假绿"）。

- `DeviceCoreWebIntegrationTest` 新增 `@TestConfiguration`（`@Primary` 嵌入式 H2 `DataSource` 替换 Postgres）；`build.gradle.kts` 测试依赖加 `com.h2database:h2`
- ⚠️ Boot 4 已移除 `@AutoConfigureTestDatabase`，改用 `@Primary` H2 方案（详见 AGENTS.md 3.1）
- 影响端：`medbox-server/` 是 · 其余 否

## [2026-10-13] 后端落地设备主干（`feat/device-core`）

`feat/device-core`（07 清单 P0 第 5 步）落地：设备注册 + 列表 / 详情 / 状态 / 事件流水四个 GET，并补上 `DeviceOwnerResolver` 实现（`requireDevice` 由此可用）。

- **`02` 第 4 章新增 4.0 节**：注册是**监护人代老人**（`WRITE_DEVICE` 仅 FAMILY，老人本人不可自注册）；**重复登记 40901、不静默改绑**；事件流水分页按时间倒序，`size` 超上限静默裁剪；查询他人设备与设备不存在**一律 40301**（不返回 40401）
- **`06` 新增 2.16 `device_event`**（V6 建表）：本分支只写 `REGISTERED` / `ONLINE` / `OFFLINE`，`FAULT` / `COMMAND` 留给 `feat/device-fault` / `feat/device-command` 续写；V6 同时给 `device` 表补 `elder_id` / `parent_device_id` 两个索引
- **`04` 第 4 章补 `DEVICE_STATUS` 推送约定**：触发点是 MQTT 侧状态**跃变**（非 HTTP），推到 `elder:{elderId}`；心跳每 60s 一次，只在真的变了才推，否则会刷爆小程序
- **⚠️ 跨分支约定（后续分支必读）**：① 只有状态**真的跃变**才写 `ONLINE` / `OFFLINE`（否则单设备一天 1440 条噪声）；② `device` 表新增子设备后，其 `elder_id` 必须与父设备一致 —— 行级过滤与归属解析都依赖它；③ `medbox.device.*` 默认 productKey 为 `medbox-box`，它是 Topic 第 2 层，不能为空
- **⚠️ 踩坑（测试侧）**：Mapper 被 `@MockitoBean` 替换后 MyBatis 不会为实体建表信息，而 **`LambdaQueryWrapper.in(...)` 与 `LambdaUpdateWrapper.set(...)` 会立即解析列名**，会抛 `can not find lambda cache for this entity`（上行链路会把它吞掉，表现为"静默什么都不做"）；`eq` / `orderBy` 是惰性的，所以看起来"有的用例能跑"。用 `support/TableInfoWarmup.init(Entity.class)` 预热（生产无此问题）
- **依赖**：`build.gradle.kts` 新增 `mybatis-plus-jsqlparser`（分页插件 `PaginationInnerInterceptor` 自 3.5.9 起从这个 starter 里拆出去了，不显式声明就编译不过）
- **测试**：`./gradlew test` 共 **233 例**（执行 228、跳过 5、失败 0），本分支新增 **58 例**，含 `DeviceCoreWebIntegrationTest`（`@SpringBootTest` + MockMvc 打完整链路，17 例）
- 影响端：`medbox-server/` 是 · `medbox-miniapp/` 是（`feat/mp-device` 可开始联调）· 嵌入式 否

## [2026-10-11] 小程序落地 WebSocket 封装（`feat/mp-ws`）

`feat/mp-ws`（08 清单地基，P0 第 2 步）落地：小程序侧接进"后端 → 小程序"实时通道，只交付**连接与分发**，告警 / 设备状态的业务页面由后续分支消费。

- **`medbox-miniapp/`**：新增 `src/store/ws.js`（**唯一长连接出口**：连接 / 30s 心跳 / 指数退避重连 1s→30s / `lastEventId` 补推 / 按类型分发 / 世代号防重）、`src/utils/ws-frame.js`（帧解析与摘要纯函数）、`components/WsStatusBar.vue`（首页顶部状态条：连接状态 + 最近事件 + 手动重连，联调入口）；`App.vue` 登录后起连、回前台幂等补连；首页插入状态条
- **`04`**：新增第 5 章《小程序落地》—— ① **握手失败客户端拿不到 HTTP 状态与 `code`**（`uni.connectSocket` 的 fail 只给 `errMsg`），一律按"连接失败 + 退避重连"处理；② 续期口径：**从未连上过不 refresh**（后端没起时不误清凭据），**曾连上过且连续失败 3 次才 refresh 一次**；③ `PONG` 不推进 `lastEventId`；`RESYNC` 记 `latestEventId` 并改走 REST 全量
- **测试**：`npm test` 共 **63 例**（执行 62、跳过 1、失败 0），本分支新增 **38 例**（帧解析 14 + 连接编排 24）；真链路 `ws.local.test.js` **默认跳过**（需本机后端）：`MEDBOX_WS_LOCAL_TEST=true` 已跑通 —— 注册拿真 JWT → 连 `/medbox/ws` → `_debug/ws/publish` 推 ALARM → 客户端收到并分发
- **⚠️ 跨分支约定（后续分支必读）**：① 页面**只调 `store/ws.js` 的 `onEvent(type, handler)`**，不要再 `uni.connectSocket`；② **订阅主题由调用方传**，监护人端必须传 `elderId`（"我监护的老人列表"接口属 P1 `feat/guardian-relation`，不传则不起连）；③ `RESYNC` 的消费者是 `feat/mp-alarm` / `feat/mp-device`（改走 REST 拉全量）
- **测试配置**：`medbox-miniapp/vitest.config.js` 的 `test.include` 从 `src/utils/__tests__` 放开到 `src/**/__tests__`（`store/` 也要写测试）；uni 插件塞的 include 在 `vite.config.js` 里改不动（插件返回值优先级更高），故改在独立的 vitest 配置里
- 影响端：`medbox-server/` 否 · `medbox-miniapp/` 是 · 嵌入式 否

## [2026-10-11] 后端落地 WebSocket 推送骨架（`feat/backend-ws`）

`feat/backend-ws`（07 清单地基，P0 第 4 步）落地：打通"后端 → 小程序"的实时通道。只交付**通道 + 帧协议 + 唯一推送出口**，具体告警业务仍由后续分支接入。

- **`04`**：补齐三处原文留白 —— ① 握手失败返回 **HTTP 401/403 + 统一响应体**（握手不走 MVC，`GlobalExceptionHandler` 管不到）；② `lastEventId` 走**握手 query**（`&lastEventId=evt-9a1c`），重连建连即补；③ 主题语法定为**仅 `elder:{elderId}`**（逗号分隔多主题），`device:` 本期不支持；另补心跳 90s 空闲阈值、按主题的补推窗口边界，并新增 **`RESYNC` 帧**（窗口过期时告知客户端改走 REST）
- **`medbox-server/`**：新增 `ws/` 包 —— `WsProperties`+`WsConfig`（`medbox.ws.enabled` 整包条件装配）、`WsFrame`/`WsFrameType`/`WsEventId`、`WsTopic`、`WsHandshakeInterceptor`、`MedboxWebSocketHandler`、`WsSessionManager`、`WsEventBuffer`、`WsPushService`、`WsIdleSessionReaper`、`WsDebugController`；新增 `payload/{AlarmPayload,DeviceStatusPayload}`
- **依赖**：`build.gradle.kts` 引入 `spring-boot-starter-websocket`；**用原生 WebSocket 不用 STOMP**（04 是自定义 JSON 帧，含 ping/pong 与按 lastEventId 补推，STOMP 表达不了）
- **配置**：`application.yml` 新增 `medbox.ws.*`（endpoint `/ws`、心跳 30s、空闲 90s、补推 200 条 / 10 分钟、`debug-endpoint-enabled` **默认 false**）
- **测试**：`./gradlew test` 共 **175 例全通过**（执行 170、跳过 5、失败 0）；本分支新增 **43 例**；真链路：`MEDBOX_WS_LOCAL_TEST=true ./gradlew test --tests '*WsLocalIntegrationTest'` 已跑通（真实客户端连 `/medbox/ws` 收到后端主动推送的 ALARM 帧）
- **⚠️ 跨分支约定（后续分支必读）**：① 推送一律走 **`WsPushService.publish(topic, type, payload)`**，不要自己持有 `WebSocketSession`；② **`device:` 主题尚未支持**（`DeviceOwnerResolver` 待 `feat/device-core`）；③ 事件缓冲在**内存**、按主题保留，**重启 / 多实例即丢**，换外部存储时调用方不用改；④ 未注册 `ServletServerContainerFactoryBean`（Boot 4 下它取 `ServerContainer` 太早会拖垮启动），单帧沿用容器默认 8KB
- 影响端：`medbox-server/` 是 · `medbox-miniapp/` 是（后续 `feat/mp-*` 按 04 的 query 参数与 `RESYNC` 帧对接） · 嵌入式 否

## [2026-10-11] 后端落地 MQTT 客户端与消息骨架（`feat/backend-mqtt`）

`feat/backend-mqtt`（07 清单地基，P0 第 3 步）落地：打通"设备 ⇄ EMQX ⇄ 后端"的消息通道。**只做通信骨架**（连接 / 订阅 / 去重 / 在线态 / 下行 ack 重发），**不含任何业务落库** —— 各上行 topic 的业务语义留给后续分支接 `UpMessageHandler` 实现。

- **`07`**：地基 `feat/backend-mqtt` 勾选完成，并补"落地"一行；本分支依赖的后续 P0 分支（`device-core` / `env-core` / `dispense-consume` / `plan-sync` 等）无需改动即可在此基础上接手业务处理
- **`medbox-server/`**：新增 `mqtt/` 包 —— `MqttProperties` + `MqttConfig`（整包 `@ConditionalOnProperty(medbox.mqtt.enabled)`，**缺 broker 不阻塞启动**）、`MqttConnectionManager`（`MqttAsyncClient` 自动重连、`connectComplete` 里重订阅）、`MqttTopics`、`MqttInboundGateway`（统一兜异常，异常不外溢 Paho 回调线程）、`payload/`（按 03 第 3 章 1:1 的上行 DTO）、`MqttMessageDeduplicator`（**先 INSERT 抢唯一键**，冲突即重复）、`DeviceStatusService`、`DownlinkPublisher` + `PendingAckRegistry`（**内存账本**）、`DownlinkFailureListener` 告警 hook；并提前引入 `domain/Device` + `mapper/DeviceMapper`（由 `feat/device-core` 复用，**仍未实现 `DeviceOwnerResolver`**）
- **表结构**：Flyway `V5__mqtt.sql` 新增 `mqtt_message`（上行去重，默认保留 7 天），见 06 的 2.15
- **依赖**：`build.gradle.kts` 引入 `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`（**不用 Spring Integration MQTT**：底层同样是 Paho，但自动重连后的重订阅与 ack 重发要精确可控）；测试任务补 `MEDBOX_MQTT_ENABLED=false`（与已有 `SPRING_FLYWAY_ENABLED=false` 并列，CI 无中间件也能跑）
- **配置与联调**：`application.yml` 的 `medbox.mqtt.*` 扩为结构化配置（含 ack 超时 / 最大下发次数 / 扫描间隔 / 去重保留天数）；本机没起 EMQX 时用 `medbox.mqtt.enabled=false` 或环境变量 `MEDBOX_MQTT_ENABLED=false` 跳过连接；EMQX 起法见 `medbox-server/README.md` 第 9 节
- **测试**：`./gradlew test` 共 **132 例**（执行 128、跳过 4、失败 0）；本分支新增 **35 例**（topic 解析、payload 字段回归、去重、ack 账本重发、下行发布、入站集成测试）+ 1 例需本地 EMQX/PostgreSQL 的默认跳过集成测试（`MEDBOX_MQTT_LOCAL_TEST=true` 开启）；本地真链路验收：EMQX 发 `up/telemetry` 后端收到、重复 `msgId` 只处理一次、缺 `msgId` 丢弃、LWT 写 `online_state`
- **⚠️ 跨分支约定（后续分支必读）**：① 业务处理一律通过新增 `UpMessageHandler` 的 `@Bean`（`supports()` 返回 `up/` 之后的子路径）接入，**不要改动本分支代码**；② **下行出口只有一个** —— `DownlinkPublisher.publish(deviceId, "schedule"|"command", payload, needAck)`，信封字段 `msgId`/`needAck` 由它注入，业务 caller 不要再自己拼；③ 下行失败目前只打日志，告警生成待 `feat/alarm-core` 替换 `DownlinkFailureListener`；④ 下行账本在内存里，**重启丢失少量待确认消息**，如需零丢失再换成带 `next_retry_at` 的表（调用方不用改）
- 影响端：`medbox-server/` 是 · `medbox-miniapp/` 否 · 嵌入式 否

## [2026-10-11] 后端落地本地 OCR / Embedding 与向量库接入（feat/backend-ai-infra）

`feat/backend-ai-infra`（07 清单地基，P0 第 2 步）落地：把 OCR 转文本、中文文本向量化、向量入库与召回三块本地能力接进 Spring 容器，为后续 P2 的 `ai-chat` / `manual-ingest` 打地基。所有 AI 操作局域网内闭环（不出网、无需 Key），仅 Chat 问答后续出网。

- **`07`**：地基 `feat/backend-ai-infra` 勾选完成，并补"落地"一行
- **`medbox-server/`**：新增 `ai/` 包 —— `OcrClient` 抽象 + `LocalOcrClient`（RestClient 调本地 RapidOCR 侧车 `localhost:8000/ocr`）+ `CloudOcrClient` 骨架（按 `medbox.ocr.provider` 切换）、`OcrProperties`、`AiConfig`（条件化装配）；Embedding（Ollama `bge-m3` 1024 维）与 `PgVectorStore` 由 Spring AI 2.0.1 自动装配（`spring.ai.*` 已在 application.yml，`initialize-schema` 默认 false，缺 PG 不阻塞启动）
- **`medbox-server/ocr-sidecar/`**：新增可独立部署的 RapidOCR 侧车（FastAPI `main.py` + `requirements.txt` + `Dockerfile`，`POST /ocr` 收图片/PDF 返回纯文本）；`docker-compose.yml` 加 `ocr` 服务（构建自该目录）；README 第 8 节改为指向该目录。**不进 `src/main/resources`**（那只是 Java classpath 资源，会被打进 JAR）
- **依赖**：`build.gradle.kts` 引入 `spring-ai-bom:2.0.1` + `spring-ai-starter-model-ollama` + `spring-ai-starter-vector-store-pgvector`（Jackson 3 与 Boot 4 一致）
- **测试**：`./gradlew test` 共 **97 例**（执行 94、跳过 3、失败 0）；新增 6 例（`AiInfraConfigTest` 2 + `OcrProviderSwitchTest` 1 + `AiLocalIntegrationTest` 3，后者需本地 Ollama/RapidOCR/PG，CI 默认跳过）；本地验收：`MEDBOX_AI_LOCAL_TEST=true` 跑 `AiLocalIntegrationTest` 验证 embed→1024 维 / OCR→文字 / 向量回环
- **⚠️ 架构约定（后续分支必读）**：① 本分支 `PgVectorStore` 用其默认 `vector_store` 表仅作向量能力打通；真实 RAG 库仍是 Flyway 建的 `drug_manual_chunk`（结构化列 `medicine_id`/`elder_id`），由 `feat/manual-ingest` 自定义 INSERT、`feat/ai-chat` 用 06 §3.3 自定义 SQL 召回；② 云端 OCR 仅骨架，实装待后续分支
- 影响端：`medbox-server/` 是 · `medbox-miniapp/` 否 · 嵌入式 否

## [2026-10-11] 小程序地基 UI Kit（feat/mp-ui-kit）：组件库 + 工具 + 页面骨架

- **`medbox-miniapp/`**：新增 `components/`（MbCard / MbCell / MbEmpty / MbLoading / MbModal / MbButton / MbField / MbNavbar / MbTabBar / RoleGate）、`utils/datetime.js`（UTC→本地：绝对 / 相对 / 临期）、`utils/permission.js`（`canAccess` + `ENTRY_ROLES`）、`config/tabbar.js`（`TAB_ENTRIES`）；`uni.scss` 加 MedBox 主题变量；预建 device / medicine / plan / record / profile 占位页 + **自定义角色化 TabBar**（绕开原生 5 项限制，按角色过滤入口）
- **跨分支约定**：① 入口角色可见性集中在 `ENTRY_ROLES`，改可见性只改配置不动组件；② 时间一律 UTC，展示统一走 `datetime.js`；③ 组件 `Mb` 前缀统一，避免与 uni 内置重名
- **测试**：Vitest 单测 **24 例全通过**（datetime 19 + permission 5）；`npm run build:mp-weixin` 编译通过（新增 devDependency：`vitest` / `sass`）
- 影响端：`medbox-miniapp/` 是 · `medbox-server/` 否 · 嵌入式 否

## [2026-10-10] 后端落地权限与监护关系校验（`feat/backend-authz`）

`GET /users/me` 终于可用，小程序冷启动的静默续期打通。落地了**集中式权限矩阵**与四步校验顺序，后续功能分支（`medicine-core` / `plan-core` / `alarm-core` / `ai-chat` ...）统一调 `AccessService.require(elderId, Permission)`，不再各自写角色判断。

- **`02`**：1.3 新增 **1.3.1 权限判定规则表** —— 四步校验顺序 × 角色 × 资源归属 → 放行 / `40301` / `40302`，含 **19 项 `Permission` 集中矩阵**与边界口径（PENDING 优先于权限判定；REVOKED 与"无关系"同码；抓拍对护理 / 医生显式拒绝）
- **`06`**：2.2 `guardian_relation` 新增 **`relation_id` 业务列**（`r-1001`，供 P1 `feat/guardian-relation` 的 accept / delete 接口直接用）+ `guardian_relation_biz_id_seq` 序列 + `guardian_id` / `elder_id` 带 `status` 的索引（Flyway `V4__authz.sql`）；头部 Flyway 清单同步
- **`07`**：地基第 5 项 `feat/backend-authz` 勾选完成并补"落地"一行
- **新增代码（`medbox-server/`）**：`common/security/{Permission,AccessIdentity,DeviceOwnerResolver}`、`domain/GuardianRelation`、`domain/enums/{RelationRole,RelationStatus}`、`mapper/GuardianRelationMapper`、`service/{AccessService,UserService}`、`controller/UserController`、`dto/user/UserProfileResponse`
- **⚠️ 后续分支必读**：① 权限判定统一走 `AccessService.require`，**判定在 Service 层不在拦截器**；② 列表接口用 `visibleElderIds()` 做 IN 过滤（防 N+1）；③ 设备维度 `requireDevice(...)` 的 `DeviceOwnerResolver` **本分支只有接口没有实现**，`feat/device-core` 引入 `DeviceMapper` 后补上即可；④ **定时任务 / MQTT 消费线程没有 `AuthContext`**，别走 `AccessService`，直接调 Mapper
- **测试**：`./gradlew test` 共 **91 例全通过**（本分支新增 36 例：规则表全矩阵 23 + Web 集成 9 + `/users/me` 4）；Mapper 打桩，**不连数据库**
- 影响端：`medbox-server/` 是 · `medbox-miniapp/` 是（`/users/me` 已可用）· 嵌入式 否

## [2026-10-10] 清单按优先级重构、新增三条实现约定、CHANGELOG 不再编号

- **`07` / `08` 清单本体重构**：由"按业务模块平铺"改为**按实施顺序分章**（地基 → P0 主干闭环 → P1 完善 → P2 锦上添花），**章节顺序即实施顺序**；同类分支已合并，后端 46→32、小程序 38→23 个分支
- **引用改用分支名**：PR / commit 一律写"对应 07/08 的 `feat/xxx`"，**不再用"第 N 项"**（清单会重排，序号不稳定）；`AGENTS.md` 与 `00` 的示例同步改
- **`AGENTS.md` 新增 3.1 三条实现约定**：功能分支带集成测试并出报告；判定型分支（清单标 🧭）先出规则表再写代码；同类待办合并成一个分支
- **`backend-ai-infra` 提前**：地基内由第 8 位提到第 2 位（需人在本地起 Ollama + RapidOCR，放后期会阻塞）；其上 AI 功能分支整体归 P2
- **`CHANGELOG.md` 不再维护版本号**：条目用日期定位、力求简洁，历史条目原样保留
- 影响端：`medbox-server/` 否（仅流程）· `medbox-miniapp/` 否 · 嵌入式 否

## [V16] — 2026-10-10（后端落地账号密码登录与 JWT）

`feat/backend-auth`（07 清单地基第 4 项）落地，小程序 `feat/mp-auth` 终于可以联调：注册 / 登录 / 刷新三个接口、BCrypt 存密码、access 2 小时 + refresh 7 天轮换、`X-Request-Id` 保护注册幂等。**本次新增两张表**（`refresh_token`、`idempotency_record`）与 `user_id_seq` 序列，**不改 MQTT / WS 协议**。

- **`07`**：地基第 4 项 `feat/backend-auth` 勾选完成，并补"落地"一行（jjwt + 不引 Spring Security、拦截器形态、首个引入 MyBatis-Plus 的分支、V3 脚本、幂等只保护 register、`/users/me` 仍留给 authz）
- **`06`**：新增 **2.13 `refresh_token`**（轮换 / 重放检测，只存 SHA-256 哈希）与 **2.14 `idempotency_record`**（`X-Request-Id` 幂等，当前只保护注册）；头部 Flyway 清单与实体关系总览同步补上 V3 与 RefreshToken
- **`02`**：第 3 章新增《后端侧落地（`feat/backend-auth`）》—— 令牌 claims 与 TTL、refresh 轮换与重放撤销、密码口径（失败统一 40102）、注册校验（role / 密码 6~64）、幂等范围与 TTL、登录校验范围
- **⚠️ 踩坑（后续分支照做）**：业务 `user_id` 的序列叫 **`user_biz_id_seq`**，不能叫 `user_id_seq` —— 后者是 `"user"` 表 `id BIGSERIAL` 自动创建的序列，`CREATE SEQUENCE IF NOT EXISTS` 会静默跳过，首个用户会被编成 `u-1`（已修，见 06 的 2.13）
- **后端新增（`medbox-server/src/main/java/com/medbox/server/`，后续分支直接复用）**：
  - `common/security/`：`JwtProvider`（签发 / 解析，失败统一 40101）、`JwtPayload`、`TokenType`、`AuthContext`（当前登录用户，ThreadLocal）、`PasswordHasher`（BCrypt + 陪跑哈希）
  - `common/web/AuthInterceptor` + `config/WebMvcConfig`：只做校验顺序第 ① 步「是否登录」，只拦 `/api/v1/**`、放行 `/api/v1/auth/**` 与 `/actuator/**`
  - `controller/AuthController`、`service/{AuthService, RefreshTokenService, IdempotencyService}`、`dto/auth/*`（record）
  - `domain/`（`User` / `RefreshToken` / `IdempotencyRecord`）与 `mapper/`（MyBatis-Plus，`@MapperScan` 在 `config/MybatisPlusConfig`）
  - Flyway `V3__auth.sql`：`user_id_seq`、`refresh_token`、`idempotency_record`
- **依赖变化**：新增 `mybatis-plus-spring-boot4-starter`（Boot 4 专用 starter）、`jjwt`（**JSON provider 取 `jjwt-orgjson` 而非 `jjwt-jackson`** —— 后者依赖 Jackson 2，会被 Boot 4 自带的 Jackson 3 顶掉）、`spring-security-crypto`（只用它的 BCryptPasswordEncoder，不引 Security 过滤器链）
- **新增配置**：`medbox.jwt.secret`（可用 `MEDBOX_JWT_SECRET` 覆盖，≥32 字节）、`medbox.jwt.access-token-ttl-seconds`、`medbox.jwt.refresh-token-ttl-seconds`、`medbox.idempotency.ttl-hours`
- **未做（有意）**：`GET /users/me`（留给 `feat/backend-authz`）、微信登录、短信验证码、限流（`feat/ai-limit`）

### 影响提示

| 端 / 目录 | 是否需要改代码 | 说明 |
|-----------|----------------|------|
| `medbox-miniapp/`（uni-app 小程序） | ✅ 可联调 | `feat/mp-auth` 已按本集文档实现，字段与后端一致（`token` / `refreshToken` / `userId` / `role` / `expiresIn`），写操作已自动带 `X-Request-Id`；**`/users/me` 仍未实现**，`App.vue` 启动的静默校验会拿到 40401 —— 属预期，等 `feat/backend-authz` |
| `medbox-server/`（Java 后端） | ✅ 后续分支必读 | ① 取"当前登录用户"用 `AuthContext.current()` / `required()`；② 签发 / 解析令牌用 `JwtProvider`（`feat/backend-ws` 握手鉴权直接复用）；③ **新增需要登录的接口无需额外配置**（拦截器已拦 `/api/v1/**`），但**新增无需登录的接口要在 `WebMvcConfig` 里加排除路径**；④ 持久层用 MyBatis-Plus，实体放 `domain/`、Mapper 放 `mapper/`（实体主键记得 `@TableId(type = IdType.AUTO)`，MP 默认雪花 ID） |
| 嵌入式（设备端） | ➖ 不受影响 | 设备走 MQTT |

## [V15] — 2026-10-10（小程序落地登录注册与 token 管理）

`feat/mp-auth`（08 清单地基第 3 项）落地，小程序有了"进门的第一道关"：登录 / 注册页、token 与 refreshToken 的本地存储、**过期自动刷新（含重试）**、未登录的路由守卫。**本次不新增接口、不改表结构与 MQTT / WS 协议**，只补齐小程序侧的认证落地口径。

- **`08`**：地基第 3 项 `feat/mp-auth` 勾选完成，并补六条跨分支约定（编排层 `store/auth.js`、token 唯一入口、只做被动刷新、新页面要进守卫白名单、`LOGIN_PATH` 改动要同步、注册角色只有两种）；第 2 项 `feat/mp-request` 的三条约定同步更新（登录页已落地、40101 处理顺序已在 V15 修正、token.js 之上加了编排层）
- **`02`**：第 3 章补**注册 / 刷新 / `/users/me` 的请求与响应示例**（此前只有登录示例），并新增《小程序侧落地（V15）》——被动刷新流程、并发只刷一次、`noAuthRetry`、登录接口 `silent`、路由守卫与回跳白名单、启动校验；**1.1 补 `40101` 处理顺序的修正说明**
- **⚠️ 修正 `feat/mp-request` 的 40101 处理顺序（后续分支必读）**：旧实现在调处理器**之前**就 `clearTokens()`，refresh token 已被清空 → 处理器根本无法刷新；且 40101 时 Promise 已 reject，处理器内部重试的结果回传不到调用方。现改为「**先交处理器续期 → 兑现真值则用同一份参数重发一次（只重发一次）→ 失败才清凭据跳登录**」，`setUnauthorizedHandler` 的处理器因此支持返回 `Promise<boolean>`（旧契约返回 `void` 仍兼容）
- **小程序新增（`medbox-miniapp/src/`，后续分支直接复用）**：
  - `store/auth.js`：**登录态编排层**（页面只调它）——`login` / `register` / `logout` / `refreshAuth` / `restoreSession` / `isLoggedIn` / `setupAuth` / `navigateAfterLogin`
  - `store/user.js`：用户资料缓存（`getUserInfo` / `setUserInfo` / `clearUserInfo` / `getRole` / `getUserId` / `roleLabel`）与角色常量 `ROLE`（`ELDER` / `GUARDIAN`）
  - `store/guard.js`：路由守卫 `installAuthGuard()`（`uni.addInterceptor` 拦四个跳转 API + 白名单 + `redirect` 回跳）与 `safeTarget()`（回跳只接受 `pages/` 开头，防开放重定向）
  - `pages/auth/login.vue` / `pages/auth/register.vue`：账号密码登录 / 注册（注册角色二选一），已注册进 `pages.json`；`pages/index/index.vue` 加登录态展示与退出登录 / 去登录 / 去注册入口（**临时**，后续由 `mp-ui-kit` / `mp-profile` 接管）
  - `App.vue`：`onLaunch` 调 `setupAuth()`（注入 401 接管 → 装路由守卫 → 静默校验登录态）
  - `api/request.js`（修 Bug）：`send(options, retried)` 内部重发、`noAuthRetry` 选项、清凭据时机后移；`api/auth.js`：登录 / 注册 / 刷新补 `silent` 与 `noAuthRetry`
- **`medbox-miniapp/README.md`**：新增《登录与鉴权（`feat/mp-auth`）》小节（编排层用法、刷新流程、守卫、登出、角色）
- **未做（有意）**：微信登录、密码前端 SHA-256 预哈希（等 HTTPS）、主动解析 JWT `exp` 提前刷新
- **版本号改为单一递增序号**：本版为 **V15**（即原 `V1.15`，序号连续），**各文档（00~06）头部不再标注版本号**，只在 `CHANGELOG.md` 顶部维护 —— 升版本从此只改一个文件（**此编号规则已于 2026-10-10 废弃**，改用日期标识，见本文件开头）

### 影响提示

| 端 / 目录 | 是否需要改代码 | 说明 |
|-----------|----------------|------|
| `medbox-miniapp/`（uni-app 小程序） | ✅ 后续分支必读 | ① 页面判断登录态用 `isLoggedIn()`、取角色用 `getRole()` / `roleLabel()`，**不要自己读 storage**；② 登录 / 注册 / 登出一律走 `store/auth.js`；③ **新建页面若允许未登录访问，必须加进 `store/guard.js` 的 `WHITE_LIST`**，否则会被挡到登录页；④ 业务请求**不要自己处理 40101**（请求层已自动续期 + 重发），只在页面里处理 `40301` / `40302` 等权限类错误 |
| `medbox-server/`（Java 后端） | ✅ 后续分支必读（`feat/backend-auth`） | 登录 / 注册响应需带 `token` / `refreshToken` / `userId` / `role` / `expiresIn`；`/auth/refresh` 请求体为 `{ refreshToken }`、响应换新的一对（轮换）；`/users/me` 至少返回 `userId` / `role` / `name` / `phone`；**本版本前端已按此实现，后端 `/auth/*` 尚未落地，暂无法联调** |
| 嵌入式（设备端） | ➖ 不受影响 | 设备走 MQTT |

## [V1.14] — 2026-10-10（后端落地数据库初始化与迁移）

`feat/backend-db`（07 清单地基第 3 项）落地，后端接上 **PostgreSQL 17 + pgvector**，15 张表由 **Flyway** 管理。**本次不新增接口、不改 MQTT / WS 协议**，只补齐"库怎么起、表怎么建"的落地口径。

- **`07`**：地基第 3 项 `feat/backend-db` 勾选完成；该项下新增"落地"一行（脚本位置、数据源占位符、持久层选型）；《构建工具约定》新增**持久层**与**数据库**两段
- **持久层框架定为 MyBatis-Plus**：本分支**不引入依赖**，由后续功能分支按需引入并写实体 / Mapper（`mapper/` 与 `domain/` 包已预留）
- **`06`**：头部新增《落地方式（V1.14）》——DDL 由 Flyway 管理（`V1__init_schema.sql` / `V2__vector_hnsw.sql`）；修正 2.5 节末尾多余的代码块收尾标记
- **`01`**：4.0.1 的 CI service 镜像 `pgvector/pgvector:pg16` → **pg17**；4.1.1 的 `application.yml` 示例补 `spring.datasource` 账号占位与 `spring.flyway` 配置段；**新增 4.1.3《本地数据库：PostgreSQL 17 + pgvector》**（两种起库方式、验收命令、两个坑）
- **后端新增（`medbox-server/`，后续分支直接复用）**：
  - `src/main/resources/db/migration/V1__init_schema.sql`：`CREATE EXTENSION IF NOT EXISTS vector` + 15 张表 + 约束 + 5 个普通索引（`user` 是保留字，建表带双引号）
  - `src/main/resources/db/migration/V2__vector_hnsw.sql`：`drug_manual_chunk` 的 HNSW 索引（余弦距离）；换 embedding 模型须删索引 → 改维度 → 重建向量
  - `docker-compose.yml`（`pgvector/pgvector:pg17`，库 `medbox` / 账号 `myuser` / 密码 `mypassword`）、`README.md`（起库 / 验收 / 常见问题）
  - `build.gradle.kts`：`spring-boot-starter-jdbc`、`spring-boot-flyway`、`flyway-core`、`flyway-database-postgresql`、`runtimeOnly("org.postgresql:postgresql")`；test 任务注入 `SPRING_FLYWAY_ENABLED=false`
- **两个 Boot 4 / Flyway 的坑（后续分支注意）**：① Boot 4 把各技术的自动装配拆成独立模块，**必须引 `org.springframework.boot:spring-boot-flyway`**，只加 `flyway-core` 会**静默不迁移**（无报错、表不建）；② Flyway 10+ 需 `flyway-database-postgresql`，否则报 `Unsupported Database: PostgreSQL`
- **数据源约定**：入库配置不含内网 IP，库名 / 账号走 `${MEDBOX_DB_URL}` / `${MEDBOX_DB_USERNAME}` / `${MEDBOX_DB_PASSWORD}`；各人用环境变量或不入库的 `application-local.yml` 覆盖（4.5 口径不变）
- **版本号**：文档集统一升到 V1.14（02 / 03 / 04 / 05 内容未变，仅版本号同步）

### 影响提示

| 端 / 目录 | 是否需要改代码 | 说明 |
|-----------|----------------|------|
| `medbox-server/`（Java 后端） | ✅ 后续分支必读 | 启动前须先有 PostgreSQL 17 + pgvector（`docker compose up -d`，或已有实例 `CREATE DATABASE medbox OWNER myuser;`）；`GET /medbox/actuator/health` 现在含 `db` 指示器，**库不可达会 DOWN（503）**；写实体 / Mapper 时引入 MyBatis-Plus；`med_record` / `env_sample` / `alarm` 属高频写入表，地基期只建了最小索引，后续按实际查询再补 |
| `medbox-miniapp/`（uni-app 小程序） | ➖ 不受影响 | 接口、字段与协议均未变 |
| 嵌入式（设备端） | ➖ 不受影响 | 设备走 MQTT |

## [V1.13] — 2026-10-09（小程序落地请求封装）

`feat/mp-request`（08 清单地基第 2 项）落地，小程序所有 REST 调用有了统一出口。**本次不新增接口、不改表结构与 MQTT / WS 协议**，只补齐小程序侧的请求约定。

- **`08`**：地基第 2 项 `feat/mp-request` 勾选完成；新增四条**跨分支约定**：① 统一出口为 `src/api/request.js`，业务模块不得直接调 `uni.request`；② 登录页路径常量 **`LOGIN_PATH = '/pages/auth/login'`**（页面由 `feat/mp-auth` 建）；③ `setUnauthorizedHandler()` 可接管 40101，mp-auth 接入 refresh 时不必改 `request.js`；④ `src/store/token.js` 是 token 存储的**唯一入口**，mp-auth 在此文件内扩展，不另建平行模块
- **`02` 1.1 新增《小程序侧落地》**：`X-Request-Id` 由小程序自动生成 **32 位小写十六进制**、**只注入写方法**（POST / PUT / PATCH / DELETE），与后端 `newTraceId()` 同形因而必被沿用；失败**只看 `code`**，`data` 缺失按 `null` 处理；网络层失败用客户端本地码 **`-1`**（非业务码，不进 1.2 的封闭集合）
- **小程序新增（`medbox-miniapp/src/`，后续分支直接复用）**：
  - `api/request.js` 统一出口：`request` / `get` / `post` / `put` / `del`，自动拼 `BASE_URL`、注入 `Authorization: Bearer` 与 `X-Request-Id`、解析 `code/message/data`、loading（并发计数）与网络兜底；成功兑现 `data`，失败拒绝 `ApiError{code,message,traceId,httpStatus}`
  - `api/auth.js`：`login` / `register` / `refresh` / `getMe`（02 第 3 章，`feat/mp-auth` 直接复用）
  - `store/token.js`：最小 token 存储（key 常量 + 读写 / 清除）
  - `utils/error.js`（错误码表 + `ApiError`）、`utils/uuid.js`（请求号生成）
- **`medbox-miniapp/README.md`**：新增《请求封装用法》小节（导入方式、选项表、错误处理示例）
- **流程约定调整**：`07` / `08`（及仓库根 `AGENTS.md`、根 `README.md`、`00`）**删除"文档改动必须与代码同一个 commit"要求** —— 改为**同一个 PR 即可**，允许按层分批提交（只要文档与代码在同一 PR 内）
- **版本号**：文档集统一升到 V1.13（01 / 03 / 04 / 05 / 06 内容未变，仅版本号同步）

### 影响提示

| 端 / 目录 | 是否需要改代码 | 说明 |
|-----------|----------------|------|
| `medbox-miniapp/`（uni-app 小程序） | ✅ 后续分支必读 | 一律 `import { get, post } from '@/api/request.js'`；要自绘错误提示时传 `silent: true` 并在 `catch` 里按 `err.code` 细分（如 40901 设备离线、50310 AI 未开通）；loading 默认关闭，需要遮罩时显式传 `loading: true` |
| `medbox-server/`（Java 后端） | ➖ 不受影响 | 接口与错误码未变；写操作会收到小程序自带的 `X-Request-Id`（32 位十六进制），会被 `TraceContext` 沿用为 traceId |
| 嵌入式（设备端） | ➖ 不受影响 | 设备走 MQTT |

## [V1.12] — 2026-10-09（后端落地统一响应、异常与错误码）

`feat/backend-response`（07 清单地基第 2 项）落地，后端所有 REST 接口有了统一出口。**本次不新增接口、不改表结构与 MQTT / WS 协议**，只补齐"响应与错误码"的落地口径。

- **`07`**：地基第 2 项 `feat/backend-response` 勾选完成
- **`02` 1.1 新增《落地约定》**：① `traceId` 由后端生成（32 位十六进制），客户端可用 `X-Request-Id` 自带，后端校验字符合法性后沿用，并用 **`X-Trace-Id` 响应头**回写，日志带 `[trace=xxx]`（响应里的 traceId 可直接 grep 日志）；② `timestamp` 是 epoch **秒**；③ `data` 为 null 时受全局 `non_null` 影响**不输出该字段**；④ `/medbox/actuator/**` 保持 Spring 原生格式，不套这层包装
- **`02` 1.2 新增两条口径**：错误码是**封闭集合**（改枚举必须改文档）；**未知路径**与**请求方法用错**（文档无 405 码）一律 `40401`；校验失败 / JSON 非法 / 缺参 / 类型不匹配一律 `40001`；未捕获异常一律 `50000`，堆栈只进日志
- **后端新增（后续分支直接复用）**：`R<T>`、`PageResult<T>`（page/size/total/list）、`ErrorCode` 枚举、`BizException` + `BizAssert`、`TraceIdFilter`、`GlobalExceptionHandler`
- **构建**：新增**测试依赖** `spring-boot-webmvc-test` —— Boot 4 把 `@WebMvcTest` / `@AutoConfigureMockMvc` 拆成了独立模块（`org.springframework.boot.webmvc.test.autoconfigure`），`spring-boot-starter-test` 不再自带；**仅 test 作用域，不影响运行时**

### 影响提示

| 端 / 目录 | 是否需要改代码 | 说明 |
|-----------|----------------|------|
| `medbox-server/`（Java 后端） | ✅ 后续分支必读 | Controller 一律返回 `R<T>`；失败抛 `BizException`（或用 `BizAssert.forbiddenIf(...)` 等快捷方法），不要自己拼错误响应；列表接口用 `R<PageResult<T>>` |
| `medbox-miniapp/`（uni-app 小程序） | ➖ 字段未变，暂无改动 | 请求封装分支落地时按 02 的 1.1 解析 `code/message/data`；注意**失败时 `data` 字段可能整个不存在**，按 `null` 处理，不要用 `data === undefined` 当异常 |
| 嵌入式（设备端） | ➖ 不受影响 | 设备走 MQTT，不走 REST |

## [V1.11] — 2026-10-08（CI：按端拆分两个 workflow）

单仓两套技术栈（后端 Gradle / JDK 21，小程序 uni-app + Node），CI 按目录拆成两个 GitHub Actions workflow，互不触发。

- **新增 `.github/workflows/ci-server.yml`**：`paths: medbox-server/**`；`setup-java` 21（temurin）+ `cache: gradle` → `chmod +x gradlew` → `./gradlew build --no-daemon`（含测试）；失败时上传 `build/reports/tests`
- **新增 `.github/workflows/ci-miniapp.yml`**：`paths: medbox-miniapp/**`；`setup-node` 20 + `cache: npm`（`cache-dependency-path` 指向 `medbox-miniapp/package-lock.json`）→ `npm ci` → `npm run build:mp-weixin` → 上传 `dist/build/mp-weixin` 产物
- 两个 workflow 均设 `concurrency`（同分支取消旧的）与 `workflow_dispatch`（可手动触发）
- **不引入 Monorepo 工具**（Nx / Turborepo / Lerna）：项目不长期维护，`paths` 过滤已足够
- **不自动上传小程序**：发布需 `miniprogram-ci` + 小程序密钥 + HTTPS 域名，局域网演示阶段不做
- **`01`**：新增 4.0.1《CI：按端拆分（GitHub Actions）》；修正 4.0 约定第 1、3 条残留的 Maven / `target/` 表述（改为 Gradle / `build/` `.gradle/`）；新增第 5 条"CI 按端拆分"
- **根 `README.md`**：新增 CI 小节（两个 workflow 与触发目录）
- 记录了一个坑：带 `paths` 过滤的 job 在未改动端会显示 **skipped**，GitHub 视为必需检查未通过会卡合并；若要开分支保护，需加一个 `if: always()` 的汇总 job 作为唯一 required（示例已写在 01 的 4.0.1）

### 影响提示

| 端 / 目录 | 是否需要改代码 | 说明 |
|-----------|----------------|------|
| `medbox-server/` | ➖ 否 | 仅新增 CI；本地命令不变（`./gradlew build`） |
| `medbox-miniapp/` | ➖ 否 | 仅新增 CI；本地命令不变（`npm run dev:mp-weixin`） |
| `medbox-admin/` | ⏸ 本期不做 | 将来建 Web 前端时照抄 `ci-miniapp.yml` 加一个 `ci-admin.yml` 即可 |

## [V1.10] — 2026-10-08（小程序脚手架落地：uni-app + Vue3 + Vite）

`feat/mp-scaffold` 落地时确定小程序端的工程形态与配置出口。**本次不改变任何接口、表结构与协议**（`BASE_URL` / `WS_URL` 与 02 / 04 一致）。

- **`08`**：地基第 1 项 `feat/mp-scaffold` 勾选完成；新增《工程约定（V1.10 起）》——uni-app 官方 CLI（Vue 3 + Vite 5，无仓库根统一构建脚本）、`.gitignore` 由仓库根统一维护
- **`01`** 4.2 补两条实操要点：① Vite 中 **`.env.[mode]` 优先级高于 `.env.local`**，故 `VITE_TLS` 只写 `.env.development`，各人的 `VITE_SERVER_HOST` 只写 `.env.local`，两者不冲突；② 脚手架生成的是嵌套一层目录，需把工程文件提升到 `medbox-miniapp/`（仓库约定路径）
- **`medbox-miniapp/README.md`**（新增）：运行命令、后端地址三层配置（`.env.development` / `.env.local` / 本地缓存 `serverHost`）、微信开发者工具"不校验合法域名"与共用 AppID 的注意事项
- **版本号**：文档集统一升到 V1.10（02 / 03 / 04 / 05 / 06 内容未变，仅版本号同步）

### 影响提示

| 端 / 目录 | 是否需要改代码 | 说明 |
|-----------|----------------|------|
| `medbox-miniapp/`（uni-app 小程序） | ✅ 需按 README 初始化 | 首次搭建 `cp .env.example .env.local` 并填自己的 IP；业务代码一律从 `src/config/index.js` 取地址 |
| `medbox-server/`（Java 后端） | ➖ 不受影响 | 接口地址与协议未变 |
| 嵌入式（设备端） | ➖ 不受影响 | MQTT / HTTP 协议未变 |

## [V1.9] — 2026-10-08（后端定为 Gradle / Spring Boot 4 / JDK 21）

`feat/backend-scaffold` 落地时确定后端的技术栈与构建方式，并把 07 清单第 1 项的口径同步为实际实现：**Spring Boot 4（4.1.1）+ JDK 21 + Gradle**。**本次不改变任何接口、表结构与协议。**

- **`07`**：地基第 1 项 `feat/backend-scaffold` 勾选完成；内容由"Spring Boot 3 + Java 17 工程"改为"**Spring Boot 4 + Java 21**（**Gradle / Kotlin DSL**）"；验收由 `mvn spring-boot:run` 改为 `./gradlew bootRun`，健康检查路径明确为 `GET /medbox/actuator/health`（context-path 为 `/medbox`）
- **`07` 新增《构建工具约定》**：单项目 Gradle 工程（Wrapper 9.7.1）+ Spring Boot 4 + JDK 21，**编译与目标运行时都是 21**（不再做 `--release 17` 降级）；基础包名 `com.medbox.server`
- **`01`**：4.1.1 的 `application.yml` 示例补 `server.servlet.context-path: /medbox`（与小程序 `BASE_URL` / `WS_URL` 对齐）；4.2 的 `.gitignore` 清单把 Maven 的 `target/` 换成 Gradle 的 `build/`、`.gradle/`
- **`00`** 目录约定表、**根 `README.md`** 与**仓库根 `AGENTS.md`** 的技术栈一列改为"Spring Boot 4 / Java 21 / Gradle"（`README.md` 的"各端独立构建"一句同步改成 Gradle）
- **`07` 补记 Spring Boot 4 的两个差异**：① 自带 **Jackson 3**（DTO / 自定义序列化按 Jackson 3 的 API 写）；② `spring.ai.*`（Spring AI）需按与 Boot 4 匹配的版本引入，`feat/backend-ai-infra` 落地时对齐
- **仓库根 `.gitignore`**：Java 段改为 Gradle（`.gradle/`、`build/`、`out/`），并放行 `gradle/wrapper/gradle-wrapper.jar`（否则会被上面的 `*.jar` 一并忽略，别人 clone 后无法使用 Wrapper）
- **版本号**：文档集统一升到 V1.9（02 / 03 / 04 / 05 / 06 内容未变，仅版本号同步）

### 影响提示

| 端 / 目录 | 是否需要改代码 | 说明 |
|-----------|----------------|------|
| `medbox-server/`（Java 后端） | ✅ 需升级环境 | **JDK 最低要求 21**；用 `./gradlew` 代替 `mvn`；工程为**单项目**（源码在 `medbox-server/src/main/java/...`），不是 `gradle init` 默认的多项目 `app/` 子工程；Spring Boot 4 带 **Jackson 3**，自定义序列化代码按新 API 写 |
| `medbox-miniapp/`（uni-app 小程序） | ➖ 不受影响 | 接口地址与协议未变 |
| 嵌入式（设备端） | ➖ 不受影响 | MQTT / HTTP 协议未变 |

## [V1.8] — 2026-10-08（改为单仓多目录）

项目结构由"文档仓库 + 若干独立代码仓库（git subtree 双向同步）"改为**单仓多目录**：后端 `medbox-server/`、小程序 `medbox-miniapp/`、Web 前端 `medbox-admin/`（本期占位）、文档 `medbox-spec/`。

- **`00_README_文档索引.md`**：删除整节《文档如何同步到代码仓库（git subtree）》，改为《文档就在本仓库》；新增目录约定表（含 `medbox-admin/` 本期不实现）；分支约定补充"一个分支只动一个端"
- **`AGENTS.md`**：重写并**移到仓库根目录**（原在 `medbox-spec/` 下）。删除全部 `git subtree add/pull/push --prefix=spec spec-repo` 流程与"推回文档仓库"要求，改为"开工 `git switch main && git pull`、完工时**文档与代码同 commit 提交**"；新增 0.1《仓库目录约定》（先确认自己在哪个端、相对路径相对端目录、`medbox-admin/` 本期不动）；自检清单同步
  - **放置位置**：根目录真实文件、**唯一一份**，`medbox-spec/` 下不再放同名文件。**不要用软链** —— Windows 下 git 默认 `core.symlinks=false`，软链会被检出成普通文本文件（真软链需开发者模式 / 管理员权限）
- **`01_系统架构与部署.md`**：新增 4.0《仓库与目录结构（单仓多目录）》；4.1 端口表补 Web 前端 5173 行（占位）；全部路径示例加目录前缀（`medbox-server/src/main/resources/application.yml`、`medbox-miniapp/src/config/index.js`、`.env.local` 等）；4.5 的 `.gitignore` 改为仓库根统一维护；第 3 章注明 `medbox-admin/` 本期不实现且管理员接口待设计；**提前记录一个坑**：将来做 Web 前端时浏览器受同源策略限制，需后端放行 CORS 或 dev proxy
- **`07` / `08`**：顶部新增路径与分支说明（代码只在各自端目录下、一个分支只改一个端）；地基项的目录与 `.gitignore` 说明同步
- **版本号**：文档集统一升到 V1.8（02 / 03 / 04 / 05 / 06 内容未变，仅版本号同步）
- **暂不新增 09 清单**：Web 前端 `medbox-admin/` 本期不做，等后端与小程序完成后再补清单与管理员接口设计（涉及 02 新增管理员接口章节、06 新增管理员账号表）

### 影响提示

| 端 / 目录 | 是否需要改代码 | 说明 |
|-----------|----------------|------|
| `medbox-server/`（Java 后端） | ➖ 结构层面 | 接口与表结构未变；仅工程根目录从仓库根下沉到 `medbox-server/` |
| `medbox-miniapp/`（uni-app 小程序） | ➖ 结构层面 | 同上，工程根目录为 `medbox-miniapp/` |
| `medbox-admin/`（Web 前端） | ⏸ 本期不做 | 目录占位，无代码、无清单 |
| 嵌入式（设备端） | ➖ 不受影响 | MQTT / HTTP 协议与地址规划均未变 |

### 对 AI 助手的影响（重要）

见到下列命令一律**不要执行**，它们属于已废弃的 subtree 方案：`git subtree add/pull/push --prefix=spec spec-repo ...`。现在只有 `origin` 一个远端，文档改动直接落在 `medbox-spec/` 并与代码一起提交。

## [V1.7] — 2026-10-06（任务清单与 AI 工作流）

新增三份文件，让"两个代码仓库 + 学弟学妹 + 各自的 AI 助手"能按同一套流程协作：

- **`07_特性分支清单_后端.md`**：44 个待办，每个 = 一个 `feat/xxx` 分支。前 8 项为**地基**（项目骨架、统一响应、建表、登录 JWT、权限、MQTT、WebSocket、本地 OCR/Embedding），其余为可分配任务，均标注依赖分支、参考章节与验收标准。
- **`08_特性分支清单_小程序.md`**：33 个待办，前 5 项为地基（初始化与配置出口、请求封装、登录注册、WebSocket 封装、基础 UI 组件），其余为可分配任务。
- **`AGENTS.md`**：给 AI 助手的工作约定 —— 不在主分支开发、开工前 `git subtree pull --prefix=spec spec-repo main --squash`、完工时更新文档并 `git subtree push --prefix=spec spec-repo docs/<分支>`、远程更新时回主分支 fetch 再 rebase，以及 AI 的**提醒义务**与自检清单。
- `00_README` 增加《分支与任务约定》小节；文档同步改为**双向**（pull + push），远端别名统一为 `spec-repo`（与目录 `spec/` 区分）。

### 本次修订（对齐文档与清单时发现的缺口）

- 告警枚举新增 **`DEVICE_FAULT`**，与 03 的 `up/event/error`（门超时 / 识别失败 / 硬件故障）对齐；`DEVICE_OFFLINE` 注明仅主控产生；06 的 `alarm.type` 同步
- 03 明确 `up/event/error` 的处理：记入设备事件流水并生成 DEVICE_FAULT 告警
- 07 补充 4 个遗漏分支：`inventory-consume`（消费 `up/inventory`）、`device-fault`、`alarm-device-offline`、`plan-reminder`（REMINDER 推送）
- 08 修正 `mp-ai-chat` 的参考章节（改指向 05）；`mp-device-events` 补充设备侧异常
- AGENTS.md 补充"在文档仓库内直接提交，无需 subtree"

### 影响提示

本次**不改变接口与表结构**（仅新增一个告警类型），对代码的影响沿用 V1.5 的《影响提示》；新增的 07 / 08 是**任务划分依据**，各代码仓库据此认领分支。

## [V1.6] — 2026-10-06（消除歧义与补充调试手段）

文档面向"AI 助手 + 接手任务的同学"，因此把所有**条件句与未定参数收敛为明确决定**，避免不同人 / 不同 AI 各做各的。**本次不改变接口与表结构**，对代码的影响沿用下方 V1.5 的《影响提示》。

| 项 | 已确定 |
|----|--------|
| 老人账号创建 | **保留 `/auth/register`**，监护人可代建；本期不做 Web 后台创建 |
| 会话有效期 | access token **2 小时**，refresh token **7 天**且轮换 |
| 抓拍上传鉴权 | 共用密钥 HMAC 签名 + **时间窗 ±5 分钟**；不做"只校验 `X-Device-Id`"的退化方案 |
| 抓拍去重 | 同一服药事件只留 1 张，按 `planId + 60s 时间窗` 兜底去重 |
| 下行 ack 重发 | 超时 **5s** 重发，**最多 3 次**，仍无回执则标记失败并告警 |
| 上行 `msgId` 格式 | 固定 `{deviceId}-{10位递增序号}`，序号持久化不归零 |
| 传感器心跳 | 主控 **60s** 汇总上报一次，连续 **3 个周期（180s）**未上报判子设备离线 |
| 摄像头抓拍张数 | 每个服药事件**只 1 张**，禁止连拍多张 |
| RAG 参数 | Top-K **= 5**；余弦距离 **> 0.6** 视为检索无依据，只答结构化数据 |
| 多模态读图兜底 | **本期不实现**，OCR 失败标记 `FAILED` 由监护人重传 |
| Embedding 选型 | **Ollama + `bge-m3`（1024 维）**，不采用 `bge-small-zh` 轻量备选 |
| 流式问答通道 | **只用 HTTP SSE**，不实现 WebSocket 的 `AI_STREAM` |
| OCR 预处理 | 只做**灰度 + 去噪**，本期不做透视 / 版面矫正 |
| IP 固定方式 | 两人各自在自己路由器上做 DHCP 静态绑定 |
| 小程序 AppID | 两人**共用同一个 AppID**（互加开发者），不用各自的号 |
| WebSocket 补推窗口 | 服务端保留最近 **200 条 / 10 分钟**，超出走 REST 拉取全量 |

**新增调试手段**：

- **02 新增 1.4《接口调试（curl）》**：登录取 token → 带 token 调接口 → 带 `X-Request-Id` 的写操作 → `curl -F` 上传抓拍图片；
- **03 新增第 5 章《联调与调试》**：① **EMQX Dashboard 内置 WebSocket 客户端**（可订阅 + 发布，是联调主力，记得改默认 `admin/public`）；② `mosquitto_pub` **只能发布**，看回包需另开 `mosquitto_sub`，给了完整命令（注意 `-i` 的 clientId 须等于 deviceId）；③ curl 指向 02；④ WebSocket 用 `wscat` 验证。

## [V1.5] — 2026-10-05

### 01 系统架构与部署
- 新增**双人独立开发**方案：IP 只写在 `.env.local` / `application-local.yml`（不入库），`git pull` 无需改动任何配置
- 新增 `application.yml` 说明与"为什么监听 `0.0.0.0` 不能省掉地址配置"的辨析
- 新增**角色权限默认规则**（角色 × 权限矩阵 + 四步校验顺序）
- 明确**时区口径**：服药计划 `times` 为设备本地墙钟时间（`device.timezone`），其余一律 UTC
- 明确**漏服容忍优先级**：计划级 > 老人级 > 系统默认 15 分钟
- **药箱不划分仓位**；设备拆分为药箱主控 + 传感器子设备
- 新增 4.6 抓拍图片的存储、鉴权与过期规则（已读 7 天 / 未读 30 天）
- 附录新增《凭据的"加密存储"——防什么、什么时候做》
- **删除 OTA / 固件版本**相关内容（仅保留一句范围说明）
- 5.2 错服识别改为摄像头识别药品；5.3 过期改为按 `medicine_stock.expiry_date`

### 02 REST 接口设计
- 登录改为**账号 + 密码**，删除短信验证码登录与微信登录（`wechat_open_id` 预留）
- 新增 1.3 权限默认规则与 `40302`（监护关系未生效）
- 监护绑定改为**两步**：监护人发起（PENDING）→ 老人端确认（ACTIVE）
- 服药计划支持**一次提醒多种药**（`items` 数组，无仓位字段）
- **仓位接口删除**，改为库存接口 `/devices/{id}/stocks*`；`/devices/{id}/sensors` 归入设备管理
- 新增 `DELETE /medicines/{medicineId}`；删除按药品设置的环境阈值接口
- 新增 4.1 设备抓拍图片接口（上传 / 列表 / 详情 / 读取文件流 / 删除）
- 环境阈值统一为老人级；告警枚举 `ENV`、`WRONG_DRUG` 描述同步更新
- 错误码表排序整理；设备注册改为"注册即生效，不做准入审核"

### 03 设备接入协议 MQTT
- 新增 `up/ack` 回执主题与超时重发；上行统一带 `msgId` 做幂等去重
- Topic 前缀去掉前导 `/`；明确**一个药箱只有主控建 MQTT 连接**
- 新增第 4 章**摄像头识别链路**（WiFi → 单片机 → MQTT；图片 HTTP 直传不过 MQTT）
- 遥测改为**每个传感器各发一条**；新增 `up/sensor/heartbeat` 判子设备离线
- 明确**错服只走 `dispense.wrongDrug`**，`up/event/error` 仅用于设备侧异常
- 低置信度**不判错服**，抓拍标 `LOW_CONFIDENCE` 交人工复核
- 补充 `up/inventory` payload 示例；`dispense` 增加 `confidence` / `source` / `imageId`
- **删除一机一密章节**；明确本期为匿名连接，两级加固仅在 03 / 01 附录各提一句

### 04 实时推送协议 WebSocket
- 告警 `level` 统一为 `INFO / WARN / CRITICAL`（示例原为 `HIGH`）
- `REMINDER` 改为 `medicines[]`（一次提醒含多种药）
- `DEVICE_STATUS` 增加 `deviceType` / `parentDeviceId`，覆盖传感器子设备；去掉 `battery` 与 `firmwareVer`

### 05 AI 大模型与 RAG 方案
- **OCR 与 Embedding 本地化**：RapidOCR 侧车服务 + Ollama `bge-m3`（1024 维），不出网、无需 Key
- 向量维度 1536 → **1024**（换模型须重建全部分块向量）
- 新增 5.1 RAG 检索路径（锁定老人 → 定位 medicineId → 带过滤召回 → 兜底说明）
- 补全会话接口（创建 / 重命名 / 删除，首次提问隐式创建 `sessionId`）

### 06 数据库设计
- **删除 `compartment`**，新增 `medicine_stock`（药箱 + 药品维度）
- `device` 增加 `device_type` 与 `parent_device_id`（主控 / 温湿度 / 光照 / 摄像头）
- 新增 `capture` 抓拍表（含 `viewed_at`、`expire_at`）
- `med_plan_item`、`med_record` 删除 `slot_no`；`med_record` 增加 `capture_id`
- `medicine` 增加 `owner_elder_id`（公共字典 / 私有药品）
- `med_record`、`env_sample` 冗余 `elder_id`；`env_sample` 增加 `gateway_device_id`
- `user` 增加 `username`、`password_hash`，`phone` 改 UNIQUE
- `drug_manual_chunk` 外键统一为 `VARCHAR(64)`
- `device.secret` 标注为预留未使用（本期匿名，无设备凭据）

### 影响提示

| 端 / 目录（当时为独立仓库） | 是否需要改代码 | 说明 |
|----------|----------------|------|
| `medbox-server/`（Java 后端） | ✅ 需要 | 计划主子表、库存表、抓拍表与接口、本地 OCR/Embedding、匿名 MQTT 连接 |
| `medbox-miniapp/`（uni-app 小程序） | ✅ 需要 | 登录改账号密码、计划改为 `items`、监护两步确认、抓拍图片查看 |
| 嵌入式（设备端） | ✅ 需要 | 上行统一带 `msgId`、`up/ack` 回执、遥测按传感器分条、传感器心跳、摄像头链路 |
| `medbox-admin/`（Web 前端） | ⏸ 本期不做 | 管理员接口待设计（V1.8 确认本期不实现） |

## [V1.4] — 2026-10-03（基线）

文档集初始版本：系统架构与部署（01）、REST 接口设计（02）、设备接入协议 MQTT（03）、实时推送协议 WebSocket（04）、AI 大模型与 RAG 方案（05）、数据库设计（06）。
