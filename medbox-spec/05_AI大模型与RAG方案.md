# 05 · AI 大模型接入（洞察层 · Spring AI）

> **方向重定位（2026-10-29）**：本文档已从"AI 问答 + 说明书 RAG 知识库"重定位为"**洞察层**"——LLM 只对本项目已有的**结构化数据**做**分析、预测、提建议**，不产出结构化内容（程序的事）、不做决策（人的事）。下文按"功能点 → 接口"组织。

## 0. 方向与边界

### 0.1 三层职责分离

| 层 | 职责 | 由谁做 |
|----|------|--------|
| **程序** | 产生结构化数据、执行动作、输出格式化报告 | 代码（确定性、零成本） |
| **LLM** | 对数据做**分析、预测、提建议**——输出自然语言洞察 / 假设 | 大模型（消费结构化数据） |
| **人** | 做**决策** | 监护人 / 医生 |

### 0.2 两条硬边界（铁律）

1. **不产出结构化数据**：LLM 的输出是分析 / 预测 / 建议这类自然语言洞察，不落库、不替程序生成结构化记录。
2. **不自动执行、不替代决策**：LLM 的所有输出均为"仅供参考"的建议，绝不自动落库或触发动作；是否采纳由监护人 / 医生决定。涉医疗结论一律后置过滤并附 `disclaimer`。

### 0.3 部署因此简化

LLM 只消费已结构化的数据，原方案的 OCR 侧车、Embedding（Ollama + bge-m3）、pgvector 与说明书知识库（`drug_manual` / `drug_manual_chunk`）**本期不再需要**。LLM 调用收敛为"监护人代配的 Chat 配置（沿用 `llm_config`）+ 结构化数据拼入 Prompt + 返回自然语言洞察"，出网范围不变（仅 Chat 问答出网）。

### 0.4 接入模式：用户自带 Key（BYOK），后端不托管大模型

- **后端不部署、不托管**任何大模型推理服务，也不为多用户承担 LLM 调用的高并发与费用；推理完全发生在监护人所选的外部 / 自建模型端点。
- 采用 **BYOK（Bring Your Own Key）**：每老人一份 `llm_config`（provider / apiKey / model / baseUrl），由监护人代配（见 2.1）。
- `baseUrl` 不限定公有云：监护人也可填**本机 / 局域网内自建端点**（如本地 Ollama `http://localhost:11434`）。"本地大模型"未来如需支持，仍走同一 `llm_config`，**不另起架构、不新增后端部署**。
- 出网范围：仅 Chat 调用出网（放行 `baseUrl` 域名 / IP）；若 `baseUrl` 指向局域网内端点，则连出网都不需要。

## 1. 功能点

LLM 洞察层只消费本项目已有的结构化数据（`med_record` / `med_plan` / `plan_reminder` / `medicine_stock` / `env_sample` / `alarm`），输出自然语言的分析、预测与建议；所有输出**不落库、不自动执行**，仅供监护人 / 医生决策参考。功能点（FP）如下，第 2 章接口将按编号对应实现。

### 1.1 分析 / 预测 / 建议场景

| 编号 | 功能点 | 消费的结构化数据 | LLM 输出（洞察 / 建议，非结构化文本） |
|------|--------|------------------|--------------------------------------|
| FP-1 | 服药依从性归因分析 | `med_record` + `plan_reminder` + `alarm(MISS)` | 漏服率变化、时段 / 星期相关性、疑似成因假设与干预建议 |
| FP-2 | 库存耗尽预测 | `medicine_stock`（数量 / 有效期）+ 近 N 日消耗 | 预计耗尽时间、异常消耗提示、补配建议 |
| FP-3 | 服药计划时序建议 | `med_plan` / `med_plan_item` 的服药时段与同服组合 | 间隔过短 / 同服日依从性偏低的错峰建议 |
| FP-4 | 环境-用药关联分析 | `env_sample`（温湿度超标）+ 对应时段 `med_record` | 环境异常与取药依从 / 药效风险的关联假设与处置建议 |
| FP-5 | 周期趋势预测与预警 | 历史 `med_record` / `alarm` 趋势 | 漏服 / 异常的趋势外推与提前介入建议 |

> 每个功能点输出的都是**假设或建议**，不是结论；"是否与某因素相关""建议如何调整"由监护人 / 医生判断，系统绝不自动改计划、不自动补配、不自动发药。

### 1.2 触发方式

| 编号 | 功能点 | 说明 |
|------|--------|------|
| FP-6 | 按需分析 | 监护人主动发起"分析某老人某时段用药情况"，同步 / 流式返回洞察 |
| FP-7 | 周期洞察 | 系统定时（如每日 / 每周）批量生成洞察，经 WebSocket 推送给监护人 |

### 1.3 配置与接入

| 编号 | 功能点 | 说明 |
|------|--------|------|
| FP-8 | 复用监护人代配的 Chat 配置 | 沿用 `llm_config`（详见文档 02 认证），按 `elderId` 取 `{baseUrl, apiKey, model}` 动态构造调用；无需新增配置表、除 Chat 出网外无额外部署 |

### 1.4 安全与合规

| 编号 | 功能点 | 说明 |
|------|--------|------|
| FP-9 | 后置过滤 + disclaimer | 涉医疗结论统一后置过滤，所有洞察附"仅供参考，由监护人 / 医生判断"声明；未配置 / Key 无效返回友好提示（错误码 50310） |
| FP-10 | 只读不写 | 洞察结果不落库、不触发任何写操作或设备指令；上游超时 / 失败时降级为模板化安全提示 |

## 2. 接口设计

> 本章接口均【鉴权】；**错误码复用 `02` §1.2，不新增**：`50310`（AI 服务暂不可用 / Key 无效）、`40001`（参数错误）、`40301`（权限不足）。统一响应 `R<T>` 见 `02` §1.1。

### 2.1 LLM 配置接口（FP-8，监护人代配 Chat Key）

洞察层调用大模型所需的 Key 由监护人为老人代配，沿用 `06` 的 `llm_config` 表；老人端不接触 Key。配置接口归并如下（原"AI 问答"的 Key 配置沿用，仅用途变为供给洞察层）：

| 方法 | 路径 | 说明 |
|------|------|------|
| PUT | /ai/config?elderId= | 【鉴权·仅监护人】保存配置（provider、apiKey、model、baseUrl） |
| GET | /ai/config?elderId= | 【鉴权】查询配置（apiKey 脱敏回显 `sk****abcd`） |
| POST | /ai/config/test?elderId= | 【鉴权·仅监护人】用当前 Key 发一次探测请求，验证连通 / 额度 |
| DELETE | /ai/config?elderId= | 【鉴权·仅监护人】清除配置 |

示例（PUT /ai/config?elderId=e-1001）：

```json
{ "provider":"openai-compatible", "apiKey":"sk-xxxxxxxx", "model":"gpt-4o-mini", "baseUrl":"https://api.openai.com/v1", "enabled":true }
// 响应（不回显明文 Key）
{ "code":0, "data":{ "elderId":"e-1001","provider":"openai-compatible","model":"gpt-4o-mini","apiKeyMask":"sk****xxxx","enabled":true } }
```

### 2.2 按需洞察接口（FP-6 + FP-1~5）

监护人主动发起对某老人某时段的结构化数据分析，后端按 `elderId` 取出配置好的 Key，拼装对应维度的结构化数据（见 FP-1~5）为上下文，调用大模型生成自然语言洞察。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /ai/insight | 【鉴权】同步生成洞察（`type` 指定分析维度，默认 `COMPREHENSIVE`） |
| POST | /ai/insight/stream | 【鉴权】SSE 流式生成，长洞察逐字输出 |

请求：

```json
POST /ai/insight
{ "elderId":"e-1001",
  "type":"COMPREHENSIVE",
  "range":{ "from":"2026-10-15T00:00:00Z", "to":"2026-10-29T00:00:00Z" },
  "deviceId":"d-001" }
```

`type` 取值（`COMPREHENSIVE` 为默认，综合下列维度）：

| type | 对应 FP | 消费的结构化数据 |
|------|---------|------------------|
| `ADHERENCE` | FP-1 | `med_record` + `plan_reminder` + `alarm(MISS)` |
| `STOCK` | FP-2 | `medicine_stock` + 近 N 日消耗 |
| `PLAN_TIMING` | FP-3 | `med_plan` / `med_plan_item` |
| `ENV_CORRELATION` | FP-4 | `env_sample` + 对应时段 `med_record` |
| `TREND` | FP-5 | 历史 `med_record` / `alarm` |
| `COMPREHENSIVE` | FP-1~5 | 以上全部 |

响应（**不落库**，FP-10）：

```json
{ "code":0, "data":{
    "elderId":"e-1001","type":"COMPREHENSIVE",
    "range":{ "from":"2026-10-15T00:00:00Z","to":"2026-10-29T00:00:00Z" },
    "content":"近14天晚饭后漏服率较上旬上升18个百分点，且与周末强相关……（仅作假设，建议核查作息）；二甲双胍按当前消耗约6天后耗尽，建议本周补配。",
    "citations":["med_record#rec-123","alarm#MISS:...","medicine_stock#m-205","env_sample#..."],
    "disclaimer":"本分析仅供参考，用药与设备调整请由监护人 / 医生决策。" } }
```

- `citations`：洞察所引用的结构化数据锚点（`表#id`），便于溯源与复核，**不是结论**。
- 流式（`/ai/insight/stream`）：增量帧 `{"type":"delta","content":"…"}`，结束帧 `{"type":"done","citations":[...],"disclaimer":"…"}`。

### 2.3 周期洞察接口（FP-7）

系统定时（默认每日）为在册老人批量生成 `COMPREHENSIVE` 洞察，经 WebSocket 推送监护人，不在 REST 轮询。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /ai/insight/schedule?elderId= | 【鉴权】查询该老人的周期洞察开关与节奏 |
| PUT | /ai/insight/schedule?elderId= | 【鉴权·仅监护人】开关 / 调整节奏（`DAILY` / `WEEKLY`） |

- 推送：经 WebSocket 下发，帧类型 `AI_INSIGHT`（在文档 `04` 补充；原 `AI_STREAM` 仅用于 2.2 的按需流式，周期洞察不另走 SSE）。
- 推送载荷复用 2.2 的 `data` 形状（含 `content` / `citations` / `disclaimer`）。

### 2.4 安全、降级与边界（FP-9 / FP-10）

- **脱敏与权限**：`/ai/config` 的 apiKey 全程脱敏；未配置 / Key 无效时调用洞察 → 返回 `50310`（"AI 助手暂未开通，请联系监护人配置"）。
- **后置过滤 + disclaimer**：涉医疗结论统一后置过滤，所有洞察响应必带 `disclaimer`（FP-9）。
- **不落库、不自动执行**（FP-10）：洞察结果不写入任何业务表、不改动计划 / 库存 / 设备；上游超时（如 15s）或失败时降级为模板化安全提示（仍可返回 `code:0` 的兜底文案），不抛出未捕获异常。
- **出网范围**：仅大模型 Chat 调用出网（放行监护人所选模型域名），结构化数据不出内网。

## 3. 跨文档一致性（待清理）

AI 重定位后，以下文档 / 代码仍残留指向旧"RAG / OCR / Embedding / 说明书知识库"的引用，需在 07 后端清单的 `refactor/ai-insight-layer` 分支一并清理，避免"文档说删、引用还在"的不一致：

- **01_系统架构与部署.md**：4.1.2「本地数据库：PostgreSQL 17」已写"无需 pgvector 扩展"，但 4.1 / 4.1.3 仍提 pgvector、`/ai/chat` 拼知识库；需改为"PostgreSQL 17，无向量扩展，AI 仅走 BYOK Chat"。
- **02_REST接口设计.md**：仍含 `WRITE_DRUG_MANUAL` 权限位、`DrugManual` 实体与说明书上传接口（原 AI 问答 + 知识库）；需删除或迁移到本 05 定义的 `/ai/config` / `/ai/insight`。
- **04_实时推送协议.md**：`AI_STREAM` 帧"见文档 05"指向已删除的旧章节；周期洞察改用 `AI_INSIGHT`，按需流式仍走 SSE（不走 WS 帧），需修订。
- **06_数据库设计.md**：`drug_manual` / `drug_manual_chunk` 相关定义需随迁移清理（见 07 `refactor/ai-insight-layer`），本期不再需要。
- **10_产品形态调整方案.md**：提及"本地大模型（OCR + Embedding）支撑 RAG"；需改为"洞察层仅 BYOK Chat，无本地大模型 / 向量库"。
- **medbox-server 代码与 docker-compose**：见 07 `refactor/ai-insight-layer`（向量 / OCR / Embedding 依赖与 `pgvector/pgvector:pg17` 镜像下线）。


