# 变更记录（CHANGELOG）

> 本文件记录文档集的每次版本变更，便于**各端（后端 / 小程序 / Web 前端）** 判断"要不要跟着改、影响哪些模块"。
> 版本格式：文档集统一版本号（各文件头部标注）。
> **V1.8 起代码与文档同仓**（`medbox-server/` / `medbox-miniapp/` / `medbox-admin/` + `medbox-spec/`），不再需要跨仓库同步；V1.7 及以前的"代码仓库"表述指当时的独立仓库。

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
