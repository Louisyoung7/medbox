# medbox-miniapp/tools/mock · 临时 Mock 后端（前端测试专用）

后端接口未落地期间，用这个**纯 Node 内置模块**（零依赖）的假后端把小程序跑通。
**不替代 `medbox-server/`**，联调以真后端为准；口径全部对照 `medbox-spec/02` / `06` / `01`。

## 用法

```bash
npm run mock        # 启动 Mock，默认监听 8080（src/config/index.js 的 BASE_URL 端口固定，勿改）
npm run test:e2e    # 端到端冒烟测试：自起服务（18099 端口）、46 项检查、跑完即关
npm run test:all    # 单元测试（vitest）+ 端到端（mock）一次跑完
```

- 把 `.env.local` 的 `VITE_SERVER_HOST` 指向运行本脚本的机器 IP（本机调试填 `127.0.0.1`）。
- H5 联调：走既有的 Vite 反代（`/medbox → 127.0.0.1:8080`），照常 `npm run dev:h5`。
- 微信开发者工具：照常勾选「不校验合法域名」。

## 演示账号（密码统一 `123456`）

| 账号 | 角色 | 姓名 | 用途 |
|------|------|------|------|
| 13800001234 | GUARDIAN | 张伟 | 监护 2 位老人（ACTIVE），可读写药品 / 库存 |
| 13800001235 | GUARDIAN | 王芳 | 对 u-1001 关系 PENDING → 验证 **40302** 提示 |
| 13800001236 | GUARDIAN | 周杰 | 关系 REVOKED → 列表为空、访问 40301 |
| 13900000001 | ELDER | 张桂英 | 只读本人；写操作（新建药品 / 库存）→ **40301** |
| 13900000002 | ELDER | 李德海 | 只读本人 |

## 覆盖的接口（均按 02 的形状与错误码）

- `POST /auth/login`、`POST /auth/register`、`POST /auth/refresh`（轮换）、`GET /users/me`、`POST /users/me/delete`
- `GET /devices`、`GET /devices/{id}`（含 `sensors[]`）
- `GET /medicines`（分页 + keyword，返回 `{list,total}`）、`GET /medicines/{id}`、`POST /medicines`（新建/更新同一入口）、`DELETE /medicines/{id}`（被库存引用 → **40901**）
- `GET /devices/{id}/stocks`（裸数组）、`PUT .../stocks/{medicineId}`（数量绝对值）、`POST .../stocks/{medicineId}/in`（数量增量）、`DELETE .../stocks/{medicineId}`

## 可验证的场景

- 库存清单四种临期状态：BOXA1001 下 阿莫西林(正常) / 阿托伐他汀(临期) / 二甲双胍(已过期) / 硝苯地平(未填有效期)
- 删除被库存引用的药品 → 40901 弹窗提示；先移除库存再删 → 成功
- 录入数量填 0 / 小数 / 非数字 → 40001（前端先拦，Mock 兜底）
- 补药入库：数量为增量，日期选填（不传不更新）
- 老人端登录 → 写入口隐藏、直接调写接口 40301
- token 过期自动续期：`MOCK_ACCESS_TTL_MS=15000 npm run mock`，等 15s 后再操作，页面无感续期

## 测试辅助

```bash
curl -X POST http://127.0.0.1:8080/medbox/api/v1/__mock__/reset   # 重置内存数据（注销账号测试后恢复）
```

## 已知边界（与真后端的差异，联调时对齐）

- **无 WebSocket**：WS 状态条显示未连接属预期；库存 / 药品 / 设备链路不依赖 WS。
- 纯内存，重启即还原种子数据；不校验密码强度、不做限流。
- 补药入库对"该药箱尚无此药品库存"从宽按首录处理（真实后端行为待定）。
- 字段名假设（stocks 裸数组、`{list,total}`、PUT/POST-in 请求体）以 `medbox-spec/02` 第 5 章"小程序侧口径"为准，后端定稿后两处一起收紧。

## 目录

```
tools/mock/
├── README.md        # 本文件
├── src/
│   ├── server.js    # HTTP 服务：路由 + 鉴权 + 权限口径 + 错误码
│   └── data.js      # 种子数据（06 的 2.1~2.5，日期相对运行时刻计算）
└── test/
    └── smoke.js     # 端到端冒烟测试（自起服务、自选端口、跑完即关）
```
