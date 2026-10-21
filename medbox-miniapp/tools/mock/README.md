# medbox-miniapp/tools/mock · 临时 Mock 后端（前端测试专用）

后端接口未落地期间，用这个**纯 Node 内置模块**（零依赖）的假后端把小程序跑通。
**不替代 `medbox-server/`**，联调以真后端为准；口径全部对照 `medbox-spec/02` / `06` / `01`。

## 用法

```bash
npm run mock        # 启动 Mock，默认监听 8080（src/config/index.js 的 BASE_URL 端口固定，勿改；
                    # 端口被 medbox-server 占用时会报错说明，不会静默失败）
npm run test:e2e    # 端到端冒烟测试：自起服务（18099 端口）、78 项检查、跑完即关
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
- `GET /plans?elderId=`（列表含 items 概览）、`GET /plans/{id}`（含 items 明细）、`POST /plans`、`PUT /plans/{id}`（整体覆盖）、`PATCH /plans/{id}/status`（请求体契约 `{status:'ENABLED'|'DISABLED'}`）、`DELETE /plans/{id}`（级联删明细）
  - 业务校验照 06 的 2.6.1：同计划药品重复 / times 格式错 / items 为空 → **40001**；药品无库存或已过期 → **40901**；老人写操作 → **40301**；PENDING 监护 → **40302**
  - 种子含 3 个计划（覆盖 ENABLED/DISABLED、单药/两种药、带备注、多时间、两位老人、DAILY/WEEKLY）；`times` 按设备本地墙钟**原样存取**（排序由前端 `normalizeTimes` 负责）
- `GET /records?elderId=&from=&to=`（分页 + 时间区间过滤，列表 join 计划名/药品名）、`GET /records/{id}`（关联计划与抓拍留证占位）、`POST /records/{id}/confirm`（监护人补录/确认）
  - 权限口径照 02 的 1.3.1 与 1.2：老人读他人 / 越权 → **40301**；监护 PENDING → **40302**；写操作仅 FAMILY → **40301**；不存在 → **40401**
  - 种子含 6 条记录（覆盖 u-1001/u-1002、按时/未按时/错服、已确认与未确认、部分带 `captureId`）；`capture-core` 属 P1，详情缩略图由前端留空占位

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

- **WebSocket 为最小实现**：`/medbox/ws` 完成握手 + `ping`/`pong` 心跳（首页状态条显示已连接），**不推业务帧**（ALARM / DEVICE_STATUS / REMINDER 留待真后端）。
- 纯内存，重启即还原种子数据；不校验密码强度、不做限流。
- 补药入库对"该药箱尚无此药品库存"从宽按首录处理（真实后端行为待定）。
- 字段名假设（stocks 裸数组、`{list,total}`、PUT/POST-in 请求体、PATCH `{status}`）以 `medbox-spec/02` 第 5 / 6 章"小程序侧口径"为准，后端定稿后两处一起收紧。

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
