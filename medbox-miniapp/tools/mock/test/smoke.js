/**
 * Mock 后端端到端冒烟测试（medbox-miniapp/tools/mock/test）。
 *
 * 自包含：自动在 18099 端口拉起 Mock 服务、跑完全部检查（当前约 207 项）、跑完即关，
 * 不依赖外部进程，也不占用 8080（真实后端 / 手动 npm run mock 都在 8080）。
 *
 * 运行：npm run test:e2e
 * 约定：AI 每个功能分支完工时，在单元测试（npm test）之后自动跑一遍（见 AGENTS.md 3.1）。
 */

const { spawn } = require('node:child_process')
const path = require('node:path')

const PORT = 18099
const BASE = `http://127.0.0.1:${PORT}/medbox/api/v1`

let pass = 0
let fail = 0

function check(name, cond, extra) {
  if (cond) {
    pass += 1
    console.log(`  ok  ${name}`)
  } else {
    fail += 1
    console.log(`FAIL  ${name}${extra ? '  ' + extra : ''}`)
  }
}

async function call(method, apiPath, { token, body } = {}) {
  const res = await fetch(BASE + apiPath, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  const json = await res.json().catch(() => null)
  return { status: res.status, code: json && json.code, data: json && json.data }
}

async function login(account) {
  const r = await call('POST', '/auth/login', { body: { account, password: '123456' } })
  return r.data
}

/** 等待服务就绪（最多 5 秒）。 */
async function waitReady() {
  for (let i = 0; i < 50; i += 1) {
    try {
      await fetch(BASE + '/users/me')
      return true
    } catch (err) {
      await new Promise((resolve) => setTimeout(resolve, 100))
    }
  }
  return false
}

;(async () => {
  const child = spawn(process.execPath, [path.join(__dirname, '..', 'src', 'server.js')], {
    env: { ...process.env, MOCK_PORT: String(PORT) },
    stdio: 'ignore',
  })
  try {
    if (!(await waitReady())) throw new Error('Mock 服务未就绪')

    console.log('== 登录 / 鉴权 ==')
    const gw = await login('13800001234')
    check('监护人登录', gw && gw.token && gw.role === 'GUARDIAN' && gw.userId === 'u-2001')
    const elder = await login('13900000001')
    check('老人登录', elder && elder.role === 'ELDER')
    const pending = await login('13800001235')
    check('PENDING 监护人登录', pending && pending.userId === 'u-2002')
    const revoked = await login('13800001236')
    check('REVOKED 监护人登录', revoked && revoked.userId === 'u-2003')
    check('错误密码 40102', (await call('POST', '/auth/login', { body: { account: '13800001234', password: 'x' } })).code === 40102)
    check('无 token 40101', (await call('GET', '/users/me')).code === 40101)
    check('假 token 40101', (await call('GET', '/users/me', { token: 'mock.u-2001.1.x' })).code === 40101)
    const me = await call('GET', '/users/me', { token: gw.token })
    check('GET /users/me', me.code === 0 && me.data.userId === 'u-2001' && me.data.name === '张伟')

    console.log('== 设备 ==')
    const devices = await call('GET', '/devices', { token: gw.token })
    check('监护人可见 2 台主控', devices.code === 0 && devices.data.length === 2)
    const detail = await call('GET', '/devices/BOXA1001', { token: gw.token })
    check('详情含 3 个传感器', detail.code === 0 && detail.data.sensors.length === 3 && detail.data.sensorCount === 3)
    check('他人/不存在设备 40301', (await call('GET', '/devices/BOXA9999', { token: gw.token })).code === 40301)
    check('子设备编号 40301', (await call('GET', '/devices/BOXA1001-S-CAM01', { token: gw.token })).code === 40301)
    const revokedDevices = await call('GET', '/devices', { token: revoked.token })
    check('REVOKED 关系看不到设备', revokedDevices.code === 0 && revokedDevices.data.length === 0)
    const pendingDevice = await call('GET', '/devices/BOXA1001', { token: pending.token })
    check('PENDING 关系 40302', pendingDevice.code === 40302)

    console.log('== 设备命令与事件（02 第 4 章；feat/mp-device-extra） ==')
    // 命令下发：异步受理返回 {commandId, state:PENDING}；Mock 同步落 COMMAND 事件（state=SUCCESS）模拟设备 ack
    const cmdRes = await call('POST', '/devices/BOXA1001/commands', { token: gw.token, body: { cmd: 'BUZZ', params: { durationSec: 5 }, operator: 'u-2001' } })
    check('监护人在线设备下发 BUZZ → PENDING', cmdRes.code === 0 && cmdRes.data.commandId && cmdRes.data.state === 'PENDING')
    check('离线设备下发 40901', (await call('POST', '/devices/BOXA1002/commands', { token: gw.token, body: { cmd: 'BUZZ' } })).code === 40901)
    check('老人（无 WRITE_DEVICE）下发 40301', (await call('POST', '/devices/BOXA1001/commands', { token: elder.token, body: { cmd: 'BUZZ' } })).code === 40301)
    check('非法 cmd 40001', (await call('POST', '/devices/BOXA1001/commands', { token: gw.token, body: { cmd: 'FLY' } })).code === 40001)
    check('子设备编号下发 40301', (await call('POST', '/devices/BOXA1001-S-CAM01/commands', { token: gw.token, body: { cmd: 'BUZZ' } })).code === 40301)
    check('未登录下发 40101', (await call('POST', '/devices/BOXA1001/commands', { body: { cmd: 'BUZZ' } })).code === 40101)
    // 事件流水：倒序分页；FAULT/COMMAND 由 device-fault/device-command 续写（Mock 已播种 + 刚下发产生 COMMAND）
    const events = await call('GET', '/devices/BOXA1001/events', { token: gw.token })
    check('事件流水 200 且倒序', events.code === 0 && Array.isArray(events.data.list) && events.data.list.length >= 5)
    check('FAULT 事件可读性（≥2）', events.data.list.filter((e) => e.eventType === 'FAULT').length >= 2)
    const topCmd = events.data.list[0]
    check('最新一条为刚下发的 COMMAND 且 ack 成功', topCmd.eventType === 'COMMAND' && topCmd.detail.commandId === cmdRes.data.commandId && topCmd.detail.state === 'SUCCESS')
    const page2 = await call('GET', '/devices/BOXA1001/events?page=1&size=2', { token: gw.token })
    check('分页 size=2 返回 2 条且倒序', page2.code === 0 && page2.data.list.length === 2 && page2.data.list[0].createdAt >= page2.data.list[1].createdAt)
    check('page=0 → 40001', (await call('GET', '/devices/BOXA1001/events?page=0', { token: gw.token })).code === 40001)
    check('老人可读事件（READ_DEVICE 双方）', (await call('GET', '/devices/BOXA1001/events', { token: elder.token })).code === 0)
    check('子设备编号事件 40301', (await call('GET', '/devices/BOXA1001-S-CAM01/events', { token: gw.token })).code === 40301)

    console.log('== 告警（02 第 8 章；feat/mp-alarm；alarm-core 已落地，Mock 先行） ==')
    // 列表分页（u-1001 种子 3 条：2 未读 + 1 已读；WRONG_DRUG 类告警已随后端删除，本期不播）
    const alarmList = await call('GET', '/alarms?elderId=u-1001', { token: gw.token })
    check('监护人查 u-1001 告警列表 200 且 3 条', alarmList.code === 0 && alarmList.data.total === 3 && alarmList.data.list.length === 3)
    const unread0 = await call('GET', '/alarms/unread-count?elderId=u-1001', { token: gw.token })
    check('未读计数 = 2', unread0.code === 0 && unread0.data.count === 2)
    // 类型 / 已读筛选
    const missOnly = await call('GET', '/alarms?elderId=u-1001&type=MISS', { token: gw.token })
    check('类型筛选 MISS = 2 条', missOnly.code === 0 && missOnly.data.total === 2)
    const unreadOnly = await call('GET', '/alarms?elderId=u-1001&readState=UNREAD', { token: gw.token })
    check('已读筛选 UNREAD = 2 条', unreadOnly.code === 0 && unreadOnly.data.total === 2)
    // 分页 size=1
    const p1 = await call('GET', '/alarms?elderId=u-1001&page=1&size=1', { token: gw.token })
    check('分页 size=1 返回 1 条', p1.code === 0 && p1.data.list.length === 1)
    // 老人可读自己（u-1001 即 elder 本人）
    check('老人可读自己告警', (await call('GET', '/alarms?elderId=u-1001', { token: elder.token })).code === 0)
    // 越权：监护人查不存在老人 → 40301
    check('查他人老人告警 40301', (await call('GET', '/alarms?elderId=u-9999', { token: gw.token })).code === 40301)
    // 标记已读后未读 -1
    check('标记已读 200', (await call('PATCH', '/alarms/a-1001-miss-1/read', { token: gw.token })).code === 0)
    const unread1 = await call('GET', '/alarms/unread-count?elderId=u-1001', { token: gw.token })
    check('标记已读后未读 = 1', unread1.code === 0 && unread1.data.count === 1)
    // 处理：监护人可；老人不可（HANDLE_ALARM = FAMILY+NURSE，小程序仅 GUARDIAN）
    check('监护人处理告警 200', (await call('PATCH', '/alarms/a-1001-env-1/handle', { token: gw.token })).code === 0)
    check('老人处理告警 40301', (await call('PATCH', '/alarms/a-1001-env-1/handle', { token: elder.token })).code === 40301)
    // 阈值设置：查询 / 监护人可改 / 老人不可改
    const set0 = await call('GET', '/alarm-settings?elderId=u-1001', { token: gw.token })
    check('查询告警设置 200 且默认 advanceMin=10', set0.code === 0 && set0.data.advanceMin === 10 && set0.data.envThreshold.temp.max === 30)
    check('监护人保存设置 200', (await call('PUT', '/alarm-settings?elderId=u-1001', { token: gw.token, body: { advanceMin: 20, envThreshold: { temp: { min: 15, max: 35 } } } })).code === 0)
    const set1 = await call('GET', '/alarm-settings?elderId=u-1001', { token: gw.token })
    check('保存后 advanceMin=20 且 temp.max=35 合并成功', set1.code === 0 && set1.data.advanceMin === 20 && set1.data.envThreshold.temp.max === 35 && set1.data.envThreshold.temp.min === 15)
    check('老人保存设置 40301', (await call('PUT', '/alarm-settings?elderId=u-1001', { token: elder.token, body: { advanceMin: 5 } })).code === 40301)
    // 未登录 / 非法分页
    check('未登录查告警 40101', (await call('GET', '/alarms?elderId=u-1001')).code === 40101)
    check('page=0 → 40001', (await call('GET', '/alarms?elderId=u-1001&page=0', { token: gw.token })).code === 40001)

    console.log('== 药品 ==')
    const search = await call('GET', '/medicines?keyword=' + encodeURIComponent('阿莫'), { token: gw.token })
    check('关键词搜索', search.code === 0 && search.data.total === 1 && search.data.list[0].medicineId === 'm-201')
    const page = await call('GET', '/medicines?page=1&size=2', { token: gw.token })
    check('分页 {list,total}', page.code === 0 && page.data.list.length === 2 && page.data.total === 6)
    check('私有药品（ACTIVE）可读', (await call('GET', '/medicines/m-205', { token: gw.token })).code === 0)
    check('私有药品（PENDING）40302', (await call('GET', '/medicines/m-204', { token: pending.token })).code === 40302)
    const elderOwn = await call('GET', '/medicines/m-204', { token: elder.token })
    check('老人读自己的私有药品', elderOwn.code === 0)
    check('老人读他人私有药品 40301', (await call('GET', '/medicines/m-205', { token: elder.token })).code === 40301)
    check('老人写药品 40301', (await call('POST', '/medicines', { token: elder.token, body: { name: '测试' } })).code === 40301)

    console.log('== 库存四条 ==')
    const stocks = await call('GET', '/devices/BOXA1001/stocks', { token: gw.token })
    check('BOXA1001 库存 4 行', stocks.code === 0 && Array.isArray(stocks.data) && stocks.data.length === 4)
    check('未填有效期的行省略字段', !('expiryDate' in stocks.data.find((s) => s.medicineId === 'm-204')))
    const put = await call('PUT', '/devices/BOXA1001/stocks/m-206', { token: gw.token, body: { quantity: 6, productionDate: '2026-09-01', expiryDate: '2027-03-31' } })
    check('PUT 录入（绝对值）', put.code === 0 && put.data.quantity === 6)
    const put2 = await call('PUT', '/devices/BOXA1001/stocks/m-206', { token: gw.token, body: { quantity: 3 } })
    check('PUT 覆盖为 3 且保留日期', put2.code === 0 && put2.data.quantity === 3 && put2.data.expiryDate === '2027-03-31')
    check('PUT 数量 0 -> 40001', (await call('PUT', '/devices/BOXA1001/stocks/m-206', { token: gw.token, body: { quantity: 0 } })).code === 40001)
    check('PUT 数量 1.5 -> 40001', (await call('PUT', '/devices/BOXA1001/stocks/m-206', { token: gw.token, body: { quantity: 1.5 } })).code === 40001)
    check('PUT 缺数量 -> 40001', (await call('PUT', '/devices/BOXA1001/stocks/m-206', { token: gw.token, body: {} })).code === 40001)
    check('PUT 日期格式错 -> 40001', (await call('PUT', '/devices/BOXA1001/stocks/m-206', { token: gw.token, body: { quantity: 1, expiryDate: '2027/01/01' } })).code === 40001)
    const restock = await call('POST', '/devices/BOXA1001/stocks/m-206/in', { token: gw.token, body: { quantity: 4 } })
    check('POST /in 增量 3+4=7', restock.code === 0 && restock.data.quantity === 7)
    check('老人写库存 40301', (await call('PUT', '/devices/BOXA1001/stocks/m-201', { token: elder.token, body: { quantity: 1 } })).code === 40301)
    check('PENDING 写库存 40302', (await call('PUT', '/devices/BOXA1001/stocks/m-201', { token: pending.token, body: { quantity: 1 } })).code === 40302)
    const delStock = await call('DELETE', '/devices/BOXA1001/stocks/m-206', { token: gw.token })
    check('DELETE 移除库存', delStock.code === 0)
    check('DELETE 不存在的行 40401', (await call('DELETE', '/devices/BOXA1001/stocks/m-206', { token: gw.token })).code === 40401)

    console.log('== 药品删除 40901 ==')
    check('被库存引用 40901', (await call('DELETE', '/medicines/m-201', { token: gw.token })).code === 40901)

    console.log('== 注册 / 注销 / 刷新 ==')
    const reg = await call('POST', '/auth/register', { body: { phone: '13700009999', password: '123456', role: 'GUARDIAN', name: '测试号' } })
    check('注册并自动登录', reg.code === 0 && reg.data.token && reg.data.role === 'GUARDIAN')
    check('重复手机号 40901', (await call('POST', '/auth/register', { body: { phone: '13700009999', password: '123456', role: 'ELDER' } })).code === 40901)
    check('注册角色非法 40001', (await call('POST', '/auth/register', { body: { phone: '13700009988', password: '123456', role: 'DOCTOR' } })).code === 40001)
    check('注销密码错 40102', (await call('POST', '/users/me/delete', { token: reg.data.token, body: { password: 'bad' } })).code === 40102)
    const del = await call('POST', '/users/me/delete', { token: reg.data.token, body: { password: '123456' } })
    check('注销成功', del.code === 0 && del.data.userId === reg.data.userId)
    check('注销后旧 token 40101', (await call('GET', '/users/me', { token: reg.data.token })).code === 40101)
    const fresh = await login('13800001234')
    const refreshed = await call('POST', '/auth/refresh', { body: { refreshToken: fresh.refreshToken } })
    check('refresh 轮换', refreshed.code === 0 && refreshed.data.token && refreshed.data.refreshToken !== fresh.refreshToken)
    check('旧 refresh 失效 40101', (await call('POST', '/auth/refresh', { body: { refreshToken: fresh.refreshToken } })).code === 40101)
    check('新 token 可用', (await call('GET', '/users/me', { token: refreshed.data.token })).code === 0)

    console.log('== 计划（02 第 6 章） ==')
    const plans = await call('GET', '/plans?elderId=u-1001', { token: gw.token })
    check('u-1001 计划列表 2 个', plans.code === 0 && plans.data.total === 2 && plans.data.list.length === 2)
    const planDetail = await call('GET', '/plans/p-3301', { token: gw.token })
    check('计划详情含 2 种药（times 墙钟原样）', planDetail.code === 0 && planDetail.data.items.length === 2 && planDetail.data.times[0] === '08:00')
    check('老人读计划列表', (await call('GET', '/plans?elderId=u-1001', { token: elder.token })).code === 0)
    check('PENDING 读计划 40302', (await call('GET', '/plans?elderId=u-1001', { token: pending.token })).code === 40302)

    // 创建：两种药（在 u-1001 药箱均有未过期库存）
    const created = await call('POST', '/plans', {
      token: gw.token,
      body: {
        elderId: 'u-1001',
        name: '测试计划',
        times: ['20:00', '08:00'],
        repeatRule: 'DAILY',
        items: [
          { medicineId: 'm-201', dose: '1', unit: '粒' },
          { medicineId: 'm-202', dose: '2', unit: '片', note: '餐后' },
        ],
      },
    })
    check('创建计划（一次两种药）', created.code === 0 && created.data.items.length === 2 && created.data.syncState === 'SYNCED_TO_DEVICE')
    // 排序由前端 normalizeTimes 负责（utils 单测覆盖），mock 原样存取
    check('times 墙钟原样存取', JSON.stringify(created.data.times) === JSON.stringify(['20:00', '08:00']))
    const newPlanId = created.data.planId

    // 06 的 2.6.1 约束与参数校验
    check('过期药品仍有库存不再拦截 → 200', (await call('POST', '/plans', { token: gw.token, body: { elderId: 'u-1001', name: 'x', times: ['08:00'], items: [{ medicineId: 'm-203' }] } })).code === 0)
    check('无库存药品加入 40901', (await call('POST', '/plans', { token: gw.token, body: { elderId: 'u-1001', name: 'x', times: ['08:00'], items: [{ medicineId: 'm-206' }] } })).code === 40901)
    check('同计划药品重复 40001', (await call('POST', '/plans', { token: gw.token, body: { elderId: 'u-1001', name: 'x', times: ['08:00'], items: [{ medicineId: 'm-201' }, { medicineId: 'm-201' }] } })).code === 40001)
    check('times 格式错 40001', (await call('POST', '/plans', { token: gw.token, body: { elderId: 'u-1001', name: 'x', times: ['25:00'], items: [{ medicineId: 'm-201' }] } })).code === 40001)
    check('items 为空 40001', (await call('POST', '/plans', { token: gw.token, body: { elderId: 'u-1001', name: 'x', times: ['08:00'], items: [] } })).code === 40001)
    check('老人创建 40301', (await call('POST', '/plans', { token: elder.token, body: { elderId: 'u-1001', name: 'x', times: ['08:00'], items: [{ medicineId: 'm-201' }] } })).code === 40301)
    check('PENDING 创建 40302', (await call('POST', '/plans', { token: pending.token, body: { elderId: 'u-1001', name: 'x', times: ['08:00'], items: [{ medicineId: 'm-201' }] } })).code === 40302)

    // 整体覆盖更新 + 启停 + 删除（级联）
    const updated = await call('PUT', `/plans/${newPlanId}`, {
      token: gw.token,
      body: { elderId: 'u-1001', name: '测试计划-改', times: ['09:00'], repeatRule: 'WEEKLY', items: [{ medicineId: 'm-201', dose: '3' }] },
    })
    check('PUT 整体覆盖（1 种药 / 09:00 / WEEKLY）', updated.code === 0 && updated.data.items.length === 1 && updated.data.times[0] === '09:00' && updated.data.repeatRule === 'WEEKLY')
    const disabled = await call('PATCH', `/plans/${newPlanId}/status`, { token: gw.token, body: { status: 'DISABLED' } })
    check('PATCH 停用（契约 {status}）', disabled.code === 0 && disabled.data.status === 'DISABLED')
    check('PATCH 非法状态 40001', (await call('PATCH', `/plans/${newPlanId}/status`, { token: gw.token, body: { status: 'ON' } })).code === 40001)
    check('DELETE 删除计划', (await call('DELETE', `/plans/${newPlanId}`, { token: gw.token })).code === 0)
    check('删除后详情 40401（级联生效）', (await call('GET', `/plans/${newPlanId}`, { token: gw.token })).code === 40401)

    console.log('== 未来待服药时间线（02 第 6 章；feat/mp-plan-timeline；next-doses） ==')
    const nd7 = await call('GET', '/plans/p-3301/next-doses?days=7', { token: gw.token })
    check('GET next-doses 200 且 7天×1次=7 条', nd7.code === 0 && Array.isArray(nd7.data.doses) && nd7.data.doses.length === 7)
    check('next-doses 含 scheduledTimeLocal 与 items(2 种药)', nd7.data.doses.every((d) => d.scheduledTimeLocal && Array.isArray(d.items) && d.items.length === 2))
    check('next-doses item 含药品名/剂量/单位', nd7.data.doses[0].items.every((it) => it.medicineId && it.medicineName && it.dose && it.unit))
    const nd15 = await call('GET', '/plans/p-3301/next-doses?days=15', { token: gw.token })
    check('days=15 → 15 条', nd15.code === 0 && nd15.data.doses.length === 15)
    // 停用计划 p-3302 → 空（空态之一）
    const ndDisabled = await call('GET', '/plans/p-3302/next-doses?days=7', { token: gw.token })
    check('停用计划 next-doses 空', ndDisabled.code === 0 && Array.isArray(ndDisabled.data.doses) && ndDisabled.data.doses.length === 0)
    // 老人读自己（READ_PLAN 全角色）
    check('老人读 next-doses 200', (await call('GET', '/plans/p-3301/next-doses?days=7', { token: elder.token })).code === 0)
    // 权限分支：REVOKED → 40301、PENDING → 40302
    check('REVOKED 监护人查 next-doses 40301', (await call('GET', '/plans/p-3301/next-doses?days=7', { token: revoked.token })).code === 40301)
    check('PENDING 监护人查 next-doses 40302', (await call('GET', '/plans/p-3301/next-doses?days=7', { token: pending.token })).code === 40302)
    // 未知计划 40401；days 越界 40001；未登录 40101
    check('未知计划 next-doses 40401', (await call('GET', '/plans/p-9999/next-doses?days=7', { token: gw.token })).code === 40401)
    check('days=0 → 40001', (await call('GET', '/plans/p-3301/next-doses?days=0', { token: gw.token })).code === 40001)
    check('days=61 → 40001', (await call('GET', '/plans/p-3301/next-doses?days=61', { token: gw.token })).code === 40001)
    check('未登录 next-doses 40101', (await call('GET', '/plans/p-3301/next-doses?days=7')).code === 40101)

    console.log('== 环境监测（02 第 9 章；feat/mp-env；env-core 已落地，Mock 先行） ==')
    const now = new Date()
    const iso = (d) => d.toISOString()
    const from7 = iso(new Date(now.getTime() - 7 * 86400000))
    const toNow = iso(now)
    // 实时：合并温湿度（TEMP_HUMI）+ 光照（LIGHT）
    const rt = await call('GET', '/env/realtime?deviceId=BOXA1001', { token: gw.token })
    check('GET /env/realtime 200 且三项数值 + sampleTime', rt.code === 0 && typeof rt.data.temperature === 'number' && typeof rt.data.humidity === 'number' && typeof rt.data.lux === 'number' && typeof rt.data.sampleTime === 'string')
    const rtTh = await call('GET', '/env/realtime?deviceId=BOXA1001&sensorId=BOXA1001-S-TH01', { token: gw.token })
    check('按温湿度传感器：temp/humidity 数值、lux 为 null', rtTh.code === 0 && typeof rtTh.data.temperature === 'number' && typeof rtTh.data.humidity === 'number' && rtTh.data.lux === null)
    const rtLgt = await call('GET', '/env/realtime?deviceId=BOXA1001&sensorId=BOXA1001-S-LGT01', { token: gw.token })
    check('按光照传感器：lux 数值、temp/humidity 为 null', rtLgt.code === 0 && typeof rtLgt.data.lux === 'number' && rtLgt.data.temperature === null && rtLgt.data.humidity === null)
    check('实时缺 deviceId → 40001', (await call('GET', '/env/realtime', { token: gw.token })).code === 40001)
    check('实时未知设备 → 40301', (await call('GET', '/env/realtime?deviceId=BOXA9999', { token: gw.token })).code === 40301)
    check('实时子设备编号作 deviceId → 40301', (await call('GET', '/env/realtime?deviceId=BOXA1001-S-CAM01', { token: gw.token })).code === 40301)
    check('实时未登录 → 40101', (await call('GET', '/env/realtime?deviceId=BOXA1001')).code === 40101)
    check('老人读实时 200（READ_ENV 全角色）', (await call('GET', '/env/realtime?deviceId=BOXA1001', { token: elder.token })).code === 0)
    // 历史：近 7 天原始
    const hist = await call('GET', `/env/history?deviceId=BOXA1001&from=${from7}&to=${toNow}`, { token: gw.token })
    check('GET /env/history 200 且 points 数组非空', hist.code === 0 && Array.isArray(hist.data.points) && hist.data.points.length > 100)
    check('原始点按时间升序且带 sampleTime', hist.data.points.every((p, i) => !!p.sampleTime && (i === 0 || hist.data.points[i - 1].sampleTime <= p.sampleTime)))
    check('原始点单行多为 null（温/光分属不同传感器）', hist.data.points.some((p) => p.temperature == null || p.lux == null))
    // 历史分桶：interval=60（1 小时）
    const histB = await call('GET', `/env/history?deviceId=BOXA1001&from=${from7}&to=${toNow}&interval=60`, { token: gw.token })
    check('分桶(1h) 点数 < 原始 且 三项全为数值（avg 忽略 null）', histB.code === 0 && Array.isArray(histB.data.points) && histB.data.points.length > 0 && histB.data.points.length < hist.data.points.length && histB.data.points.every((p) => typeof p.temperature === 'number' && typeof p.humidity === 'number' && typeof p.lux === 'number'))
    check('分桶 sampleTime 升序', histB.data.points.every((p, i) => i === 0 || histB.data.points[i - 1].sampleTime <= p.sampleTime))
    // 历史按传感器：光照
    const histLgt = await call('GET', `/env/history?deviceId=BOXA1001&sensorId=BOXA1001-S-LGT01&from=${from7}&to=${toNow}`, { token: gw.token })
    check('按光照传感器：lux 数值、temp/humidity 为 null', histLgt.code === 0 && histLgt.data.points.every((p) => typeof p.lux === 'number' && p.temperature == null && p.humidity == null))
    // 历史校验分支
    check('历史缺 from → 40001', (await call('GET', `/env/history?deviceId=BOXA1001&to=${toNow}`, { token: gw.token })).code === 40001)
    check('历史 from>to → 40001', (await call('GET', `/env/history?deviceId=BOXA1001&from=${toNow}&to=${from7}`, { token: gw.token })).code === 40001)
    const from31 = iso(new Date(now.getTime() - 31 * 86400000))
    check('历史跨度 >30 天 → 40001', (await call('GET', `/env/history?deviceId=BOXA1001&from=${from31}&to=${toNow}`, { token: gw.token })).code === 40001)
    check('历史 interval 越界(2000) → 40001', (await call('GET', `/env/history?deviceId=BOXA1001&from=${from7}&to=${toNow}&interval=2000`, { token: gw.token })).code === 40001)
    check('历史未知设备 → 40301', (await call('GET', `/env/history?deviceId=BOXA9999&from=${from7}&to=${toNow}`, { token: gw.token })).code === 40301)
    check('历史未登录 → 40101', (await call('GET', `/env/history?deviceId=BOXA1001&from=${from7}&to=${toNow}`)).code === 40101)

    console.log('== 记录（02 第 7 章） ==')
    const records = await call('GET', '/records?elderId=u-1001', { token: gw.token })
    check('u-1001 记录列表 4 条', records.code === 0 && records.data.total === 4 && records.data.list.length === 4)
    check('列表 join 计划名/药品名', records.data.list.every((r) => r.planName && r.medicineName))
    check('列表含按时布尔', records.data.list.every((r) => typeof r.onTime === 'boolean'))
    const recDetail = await call('GET', '/records/rec-9001', { token: gw.token })
    check('记录详情（关联计划）', recDetail.code === 0 && recDetail.data.recordId === 'rec-9001' && recDetail.data.planName === '早餐后')
    check('老人读他人记录 40301', (await call('GET', '/records/rec-9005', { token: elder.token })).code === 40301)
    check('PENDING 读记录 40302', (await call('GET', '/records/rec-9001', { token: pending.token })).code === 40302)
    const future = await call('GET', '/records?elderId=u-1001&from=2999-01-01', { token: gw.token })
    check('未来 from 过滤为空', future.code === 0 && future.data.total === 0)
    const wide = await call('GET', '/records?elderId=u-1001&from=2000-01-01&to=2999-12-31', { token: gw.token })
    check('宽区间仍 4 条', wide.code === 0 && wide.data.total === 4)
    const confirm = await call('POST', '/records/rec-9002/confirm', { token: gw.token, body: { note: '已核对' } })
    check('监护人补录/确认成功', confirm.code === 0 && confirm.data.confirmedBy === 'u-2001')
    check('老人补录 40301', (await call('POST', '/records/rec-9002/confirm', { token: elder.token, body: {} })).code === 40301)
    check('PENDING 补录 40302', (await call('POST', '/records/rec-9002/confirm', { token: pending.token, body: {} })).code === 40302)
    const confirmDetail = await call('GET', '/records/rec-9002', { token: gw.token })
    check('确认后详情带 confirmedAt', confirmDetail.code === 0 && confirmDetail.data.confirmedAt)
    check('记录不存在 40401', (await call('GET', '/records/rec-9999', { token: gw.token })).code === 40401)

    console.log('== 抓拍 / 待确认（02 §4.1 / §7.1；feat/mp-capture-event） ==')
    const recPending = await call('GET', '/records/rec-9004', { token: gw.token })
    check('待确认记录 pendingConfirm=true + faceResult=UNCERTAIN', recPending.code === 0 && recPending.data.pendingConfirm === true && recPending.data.faceResult === 'UNCERTAIN')
    const recListPending = await call('GET', '/records?elderId=u-1001&pendingConfirm=true', { token: gw.token })
    check('仅看待确认筛选返回待确认记录', recListPending.code === 0 && recListPending.data.total === 1 && recListPending.data.list[0].recordId === 'rec-9004')
    const cap = await call('GET', '/captures/cap-5001', { token: gw.token })
    check('抓拍详情 200 + fileToken', cap.code === 0 && cap.data.captureId === 'cap-5001' && typeof cap.data.fileToken === 'string' && cap.data.fileToken.length > 0)
    check('老人读他人抓拍 40301', (await call('GET', '/captures/cap-5001', { token: elder.token })).code === 40301)
    check('抓拍不存在 40401', (await call('GET', '/captures/cap-9999', { token: gw.token })).code === 40401)
    const fileRes = await fetch(BASE + `/captures/cap-5001/file?token=${encodeURIComponent(cap.data.fileToken)}`)
    check('原图流 200 + Content-Type image/png', fileRes.status === 200 && fileRes.headers.get('content-type') === 'image/png')
    check('原图流无统一响应体（非 JSON）', (await fileRes.json().catch(() => null)) === null)
    check('错误令牌 401', (await fetch(BASE + '/captures/cap-5001/file?token=bad')).status === 401)
    const conf = await call('POST', '/records/rec-9005/confirm', { token: gw.token, body: { faceResult: 'TAKEN', note: '已核对原图' } })
    check('确认后 pendingConfirm=false + faceResult=TAKEN + confidence 置空', conf.code === 0 && conf.data.pendingConfirm === false && conf.data.faceResult === 'TAKEN' && conf.data.confidence == null)
    const recAfter = await call('GET', '/records/rec-9005', { token: gw.token })
    check('详情刷新后待确认解除', recAfter.code === 0 && recAfter.data.pendingConfirm === false)
    check('老人确认他人记录 40301', (await call('POST', '/records/rec-9005/confirm', { token: elder.token, body: { faceResult: 'TAKEN' } })).code === 40301)

    console.log('== 依从性统计 / 导出（02 第 7 章 §7.0；feat/mp-report） ==')
    const adhWeek = await call('GET', '/adherence?elderId=u-1001&period=week', { token: gw.token })
    check('周依从性 200 + 4 条记录 + 按时率0.5', adhWeek.code === 0 && adhWeek.data.totalDoses === 4 && Math.abs(adhWeek.data.onTimeRate - 0.5) < 1e-6)
    check('周依从性含 dailyBreakdown 数组(非空、每日含 date/totalDoses)', adhWeek.code === 0 && Array.isArray(adhWeek.data.dailyBreakdown) && adhWeek.data.dailyBreakdown.length >= 1 && adhWeek.data.dailyBreakdown.every((d) => typeof d.date === 'string' && d.date.length === 10 && d.totalDoses > 0))
    check('周依从性漏服数=2（MISS 告警）', adhWeek.code === 0 && adhWeek.data.missCount === 2)
    const adhDay = await call('GET', '/adherence?elderId=u-1001&period=day', { token: gw.token })
    check('日窗口空 → totalDoses=0 且 onTimeRate=0 且 dailyBreakdown 为数组', adhDay.code === 0 && adhDay.data.totalDoses === 0 && adhDay.data.onTimeRate === 0 && Array.isArray(adhDay.data.dailyBreakdown))
    const adhMonth = await call('GET', '/adherence?elderId=u-1001&period=month', { token: gw.token })
    check('月窗口 200 + 4 条（覆盖 week）', adhMonth.code === 0 && adhMonth.data.totalDoses === 4)
    check('period 非法 40001', (await call('GET', '/adherence?elderId=u-1001&period=quarter', { token: gw.token })).code === 40001)
    check('缺 elderId 40001', (await call('GET', '/adherence?period=week', { token: gw.token })).code === 40001)
    check('老人读他人依从性 40301', (await call('GET', '/adherence?elderId=u-1002', { token: elder.token })).code === 40301)
    check('PENDING 监护人读依从性 40302', (await call('GET', '/adherence?elderId=u-1001', { token: pending.token })).code === 40302)

    // 导出 CSV 文件流：走原始 fetch（非 JSON 响应体）
    const exportRes = await fetch(BASE + '/records/export?elderId=u-1001', { headers: { Authorization: `Bearer ${gw.token}` } })
    const exportBuf = Buffer.from(await exportRes.arrayBuffer())
    const exportText = exportBuf.toString('utf8')
    const hasBom = exportBuf[0] === 0xef && exportBuf[1] === 0xbb && exportBuf[2] === 0xbf
    check('导出 200 + text/csv', exportRes.status === 200 && (exportRes.headers.get('content-type') || '').includes('text/csv'))
    check('导出带 UTF-8 BOM (EF BB BF)', hasBom)
    check('导出表头含 记录ID/是否按时/结论', exportText.includes('记录ID') && exportText.includes('是否按时') && exportText.includes('结论'))
    check('导出含 u-1001 记录（rec-9001）', exportText.includes('rec-9001'))
    check('导出 Content-Disposition 含文件名', (exportRes.headers.get('content-disposition') || '').includes('medication-records-u-1001'))
    const elderExport = await fetch(BASE + '/records/export?elderId=u-1001', { headers: { Authorization: `Bearer ${elder.token}` } })
    check('老人导出 40301', elderExport.status === 403 && (await elderExport.json()).code === 40301)
    const pendExport = await fetch(BASE + '/records/export?elderId=u-1001', { headers: { Authorization: `Bearer ${pending.token}` } })
    check('PENDING 监护人导出 40302', pendExport.status === 403 && (await pendExport.json()).code === 40302)

    console.log('== 抓拍列表 / 删除（02 §4.1；feat/mp-capture） ==')
    const listU1002 = await call('GET', '/captures?elderId=u-1002', { token: gw.token })
    check('监护人列 u-1002 抓拍 200 + total=2', listU1002.code === 0 && listU1002.data.total === 2 && listU1002.data.list.length === 2)
    const listManual = await call('GET', '/captures?elderId=u-1002&reason=MANUAL', { token: gw.token })
    check('reason=MANUAL 仅返回主动留证 1 条', listManual.code === 0 && listManual.data.total === 1 && listManual.data.list[0].captureId === 'cap-5002')
    const listU1001 = await call('GET', '/captures?elderId=u-1001', { token: elder.token })
    check('老人看自己抓拍 200 + 含过期 cap-5004', listU1001.code === 0 && listU1001.data.total === 2 && listU1001.data.list.some((c) => c.captureId === 'cap-5004'))
    check('列表每条带 fileToken', listU1001.code === 0 && listU1001.data.list.every((c) => typeof c.fileToken === 'string' && c.fileToken.length > 0))
    check('缺 elderId 40001', (await call('GET', '/captures', { token: gw.token })).code === 40001)
    check('老人看他人抓拍 40301', (await call('GET', '/captures?elderId=u-1002', { token: elder.token })).code === 40301)
    check('PENDING 监护人看抓拍 40302', (await call('GET', '/captures?elderId=u-1001', { token: pending.token })).code === 40302)
    check('老人删除抓拍 40301', (await call('DELETE', '/captures/cap-5002', { token: elder.token })).code === 40301)
    const delOk = await call('DELETE', '/captures/cap-5002', { token: gw.token })
    check('监护人删除抓拍 200', delOk.code === 0)
    check('删除后详情 40401', (await call('GET', '/captures/cap-5002', { token: gw.token })).code === 40401)
    check('删除不存在 40401', (await call('DELETE', '/captures/cap-9999', { token: gw.token })).code === 40401)
    const del5004 = await call('DELETE', '/captures/cap-5004', { token: gw.token })
    check('监护人删除过期抓拍 cap-5004 成功', del5004.code === 0)
    const listU1001After = await call('GET', '/captures?elderId=u-1001', { token: gw.token })
    check('删除后 u-1001 抓拍剩 1 条（cap-5003）', listU1001After.code === 0 && listU1001After.data.total === 1 && listU1001After.data.list[0].captureId === 'cap-5003')

    console.log('== 用户资料（02 第 3 章） ==')
    const elderProfile = await call('GET', '/users/u-1001/profile', { token: elder.token })
    check('老人看本人资料（全字段）', elderProfile.code === 0 && elderProfile.data.userId === 'u-1001' && elderProfile.data.role === 'ELDER' && elderProfile.data.name === '张桂英' && elderProfile.data.phone === '13900000001' && elderProfile.data.username === 'guiying')
    check('ACTIVE 监护人可看老人资料', (await call('GET', '/users/u-1001/profile', { token: gw.token })).code === 0)
    check('PENDING 监护看老人资料 40302', (await call('GET', '/users/u-1001/profile', { token: pending.token })).code === 40302)
    check('REVOKED 关系看老人资料 40301', (await call('GET', '/users/u-1001/profile', { token: revoked.token })).code === 40301)
    check('目标不是老人 40301', (await call('GET', '/users/u-2002/profile', { token: gw.token })).code === 40301)
    check('老人看他人资料 40301', (await call('GET', '/users/u-1002/profile', { token: elder.token })).code === 40301)
    check('目标不存在 40301（防枚举）', (await call('GET', '/users/u-9999/profile', { token: gw.token })).code === 40301)
    check('未登录看资料 40101', (await call('GET', '/users/u-1001/profile')).code === 40101)

    console.log('== 监护关系（02 的 3.2） ==')
    const elder2 = await login('13900000002')
    check('第二位老人登录', elder2 && elder2.role === 'ELDER' && elder2.userId === 'u-1002')
    // ① 申请：周杰（gr-4 REVOKED）对新老人 u-1002 走"无既有关系"新建分支
    const applied = await call('POST', '/users/u-1002/guardians', { token: revoked.token })
    check('申请新老人建 PENDING', applied.code === 0 && applied.data.status === 'PENDING' && applied.data.elderId === 'u-1002' && applied.data.guardianId === 'u-2003' && !!applied.data.relationId)
    check('申请响应省略 role（未指定）', applied.code === 0 && !('role' in applied.data))
    const newRelId = applied.data && applied.data.relationId
    // ⑤ 重复申请：PENDING / ACTIVE 均 40901
    check('重复申请（PENDING）40901', (await call('POST', '/users/u-1001/guardians', { token: pending.token })).code === 40901)
    check('重复申请（ACTIVE）40901', (await call('POST', '/users/u-1001/guardians', { token: gw.token })).code === 40901)
    // ⑥ REVOKED 复活为 PENDING（清空 role）
    const revived = await call('POST', '/users/u-1001/guardians', { token: revoked.token })
    check('REVOKED 复活为 PENDING', revived.code === 0 && revived.data.status === 'PENDING' && revived.data.relationId === 'gr-4' && !('role' in revived.data))
    // ②③ 非监护人 / 目标不是真实老人 → 40301（防枚举）
    check('老人发起申请 40301', (await call('POST', '/users/u-1002/guardians', { token: elder.token })).code === 40301)
    check('目标不是老人 40301', (await call('POST', '/users/u-2002/guardians', { token: gw.token })).code === 40301)
    check('目标不存在 40301', (await call('POST', '/users/u-9999/guardians', { token: gw.token })).code === 40301)
    // ② 待确认列表：张桂英有 gr-3（王芳）+ 复活的 gr-4（周杰）
    const pendList = await call('GET', '/users/guardians/pending', { token: elder.token })
    check('待确认列表 2 条', pendList.code === 0 && pendList.data.length === 2)
    check('按 createdAt 升序（gr-3 在前）', pendList.code === 0 && pendList.data[0].relationId === 'gr-3' && pendList.data[1].relationId === 'gr-4')
    check('join 监护人姓名+手机', pendList.code === 0 && pendList.data[0].guardianName === '王芳' && pendList.data[0].guardianPhone === '13800001235' && pendList.data[1].guardianName === '周杰')
    check('非老人看待确认 40301', (await call('GET', '/users/guardians/pending', { token: gw.token })).code === 40301)
    // ③ 确认：张桂英确认王芳的监护请求（监护关系无角色，确认即生效）
    const accepted = await call('PATCH', '/users/guardians/gr-3/accept', { token: elder.token, body: {} })
    check('确认置 ACTIVE（无 role）', accepted.code === 0 && accepted.data.status === 'ACTIVE' && accepted.data.role === undefined && accepted.data.elderId === 'u-1001')
    check('携带多余 role 字段仍生效（后端忽略）', (await call('PATCH', '/users/guardians/gr-4/accept', { token: elder.token, body: { role: 'DOCTOR' } })).code === 0)
    check('重复确认 40901', (await call('PATCH', '/users/guardians/gr-3/accept', { token: elder.token, body: {} })).code === 40901)
    check('确认他人的关系 40301', (await call('PATCH', '/users/guardians/gr-1/accept', { token: elder2.token, body: {} })).code === 40301)
    check('监护人点确认 40301', (await call('PATCH', '/users/guardians/gr-4/accept', { token: pending.token, body: {} })).code === 40301)
    // ④ 拒绝 / 解除：老人拒绝周杰（gr-4）；周杰撤销对 u-1002 的申请
    check('老人拒绝 PENDING', (await call('DELETE', '/users/guardians/gr-4', { token: elder.token })).code === 0)
    check('监护人撤销申请', (await call('DELETE', `/users/guardians/${newRelId}`, { token: revoked.token })).code === 0)
    check('重复解除幂等', (await call('DELETE', `/users/guardians/${newRelId}`, { token: revoked.token })).code === 0)
    check('陌生人解除 40301', (await call('DELETE', '/users/guardians/gr-1', { token: elder2.token })).code === 40301)
    check('解除不存在的关系 40301', (await call('DELETE', '/users/guardians/gr-9999', { token: gw.token })).code === 40301)
    // 解除后再申请：REVOKED → PENDING（同一 relationId 复活）
    const reapplied = await call('POST', '/users/u-1002/guardians', { token: revoked.token })
    check('解除后重新申请（同 relationId 复活）', reapplied.code === 0 && reapplied.data.status === 'PENDING' && reapplied.data.relationId === newRelId)
    check('重申请后 PENDING 访问老人数据 40302', (await call('GET', '/devices/BOXA1002', { token: revoked.token })).code === 40302)

    console.log('== WebSocket（04 最小实现：握手 + ping/pong） ==')
    const wsResult = await new Promise((resolve) => {
      const ws = new WebSocket(
        `ws://127.0.0.1:${PORT}/medbox/ws?token=mock.u-2001.1.x&topics=elder:u-1001`,
      )
      const timer = setTimeout(() => {
        try {
          ws.close()
        } catch (err) {
          /* 忽略 */
        }
        resolve({ ok: false, why: 'timeout' })
      }, 3000)
      ws.onopen = () => ws.send(JSON.stringify({ type: 'ping' }))
      ws.onmessage = (event) => {
        clearTimeout(timer)
        try {
          ws.close()
        } catch (err) {
          /* 忽略 */
        }
        let type = ''
        try {
          type = JSON.parse(event.data).type || ''
        } catch (err) {
          /* 忽略 */
        }
        resolve({ ok: type === 'pong', type })
      }
      ws.onerror = () => {
        clearTimeout(timer)
        resolve({ ok: false, why: 'error' })
      }
    })
    check('WS 握手 + ping 回 pong', wsResult.ok, JSON.stringify(wsResult))

    console.log('== 联调辅助 _debug（02 的 1.5） ==')
    // 免登录：与真后端 WebMvcConfig 把 seed / mqtt/** 排除在鉴权外的口径一致
    const seed = await call('POST', '/_debug/seed')
    check('seed 免登录成功', seed.code === 0 && !!seed.data)
    check('seed 回带双 token 与设备 ID', !!seed.data.guardianToken && !!seed.data.elderToken && seed.data.deviceId === 'BOXA1001')
    check('seed 幂等（created=false）', seed.data.created === false)
    check('seed 的 token 真能用', (await call('GET', '/users/me', { token: seed.data.guardianToken })).code === 0)
    const sim = await call('POST', '/_debug/mqtt/publish', {
      body: { deviceId: 'BOXA1001', subTopic: 'event/dispense', payload: '{"msgId":"BOXA1001-0000000001","ts":1}' },
    })
    check('模拟上报成功（payload 为字符串）', sim.code === 0)
    check('缺 deviceId 40001', (await call('POST', '/_debug/mqtt/publish', { body: { subTopic: 'telemetry' } })).code === 40001)
    check('缺 subTopic 40001', (await call('POST', '/_debug/mqtt/publish', { body: { deviceId: 'BOXA1001' } })).code === 40001)
    // 后端 DTO 把 payload 声明为 String，传对象会被 Jackson 拒掉 —— Mock 提前暴露这个错
    check('payload 传对象 40001', (await call('POST', '/_debug/mqtt/publish', { body: { deviceId: 'BOXA1001', subTopic: 'telemetry', payload: { a: 1 } } })).code === 40001)
    check('设备不存在 40001', (await call('POST', '/_debug/mqtt/publish', { body: { deviceId: 'BOXA9999', subTopic: 'telemetry', payload: '{}' } })).code === 40001)

    console.log('== 重置 ==')
    const reset = await call('POST', '/__mock__/reset')
    check('reset 恢复种子数据', reset.code === 0)
    const after = await call('GET', '/devices/BOXA1001/stocks', { token: gw.token })
    check('reset 后库存回到 4 行', after.data.length === 4)

    console.log(`\n结果: ${pass} 通过 / ${fail} 失败`)
  } finally {
    child.kill()
  }
  process.exit(fail ? 1 : 0)
})().catch((err) => {
  console.error('冒烟脚本异常', err)
  process.exit(1)
})
