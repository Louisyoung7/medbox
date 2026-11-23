# 契约测试场景（mock 与真后端双跑）

本目录是**跨端接口契约的声明式事实源**：同一份场景既跑 Mock，也跑真后端。目的是发现「Mock 与真后端各自手写、长期漂移」的问题。

> 机器可读口径仍以 `medbox-spec/openapi/*.yaml` 为准；本目录只抽取**联调必过的核心场景**，不重复全量 schema。

## 目录

```
contracts/
├── README.md
├── baseline.mock.json      # Mock 的账号 / 设备 / 药品 / 计划基线
├── baseline.real.json      # 真后端 /_debug/seed 的基线
└── scenarios/
    ├── core/               # 必须在 mock 与 real 都通过
    └── mock-only/          # 后端未落地或只有 Mock 有数据（如 AI 洞察）
```

## 场景格式

一个文件一个场景：

```json
{
  "name": "auth: 监护人登录成功",
  "profiles": ["mock", "real"],
  "steps": [
    {
      "name": "登录",
      "request": {
        "method": "POST",
        "path": "/auth/login",
        "body": { "account": "${guardian.account}", "password": "${guardian.password}" }
      },
      "expect": {
        "status": 200,
        "code": 0,
        "shape": { "data.token": "string", "data.userId": "string" },
        "equals": { "data.role": "GUARDIAN" }
      },
      "capture": { "guardianToken": "data.token" }
    }
  ]
}
```

### 字段

| 字段 | 说明 |
|------|------|
| `profiles` | 该场景适用的 profile；`["mock"]` 表示只在 Mock 跑 |
| `request.method` / `path` / `body` | 相对 Base URL 的请求；`body` 可为空 |
| `auth` | 使用哪个已捕获的 token 变量，如 `guardianToken`；登录步骤不填 |
| `expect.status` | HTTP 状态码 |
| `expect.code` | 统一响应体里的业务码 |
| `expect.shape` | `路径 → 类型`；类型取 `string / number / boolean / object / array` |
| `expect.equals` | `路径 → 期望值`，支持占位符 |
| `expect.minLength` | `路径 → 最小长度`，用于数组 / 字符串 |
| `capture` | `变量名 → 响应路径`，供后续步骤用 `${vars.xxx}` 引用 |

### 占位符

| 占位符 | 来源 |
|--------|------|
| `${guardian.account}` / `${guardian.password}` / `${guardian.userId}` | baseline |
| `${elder.account}` / `${elder.password}` / `${elder.userId}` | baseline |
| `${deviceId}` / `${medicineId}` / `${planId}` | baseline |
| `${runSuffix}` / `${runPhone}` | runner 本次运行的唯一后缀 / 手机号 |
| `${vars.xxx}` | 前序步骤 `capture` 的结果 |

### 断言原则

- 只断言**形状与错误码**，不断言列表条数、记录数、告警数。
- 两端种子不同，`GET /devices` 在 Mock 可能 2 条、真后端 1 条；只断言 `data` 是数组且首项字段类型正确。
- 业务错误同时断言 HTTP 状态与 `code`：真后端按 `ErrorCode` 映射 4xx，Mock 已对齐。

## 运行

```bash
# 对 Mock（需先起 npm run mock，默认 8080；CI 用 18099）
node medbox-miniapp/tools/contract/run.mjs \
  --base http://127.0.0.1:8080/medbox/api/v1 --profile mock

# 对真后端（需先起后端 + /_debug/seed?reset=true）
node medbox-miniapp/tools/contract/run.mjs \
  --base http://127.0.0.1:8080/medbox/api/v1 --profile real
```

`--only <关键字>` 只跑名称匹配的场景，`--list` 只列场景不执行。
