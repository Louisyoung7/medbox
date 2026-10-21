/**
 * Mock 后端端到端冒烟测试（medbox-miniapp/tools/mock/test）。
 *
 * 自包含：自动在 18099 端口拉起 Mock 服务、跑完全部检查（当前 78 项）、跑完即关，
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
    check('过期药品加入 40901', (await call('POST', '/plans', { token: gw.token, body: { elderId: 'u-1001', name: 'x', times: ['08:00'], items: [{ medicineId: 'm-203' }] } })).code === 40901)
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

    console.log('== 记录（02 第 7 章） ==')
    const records = await call('GET', '/records?elderId=u-1001', { token: gw.token })
    check('u-1001 记录列表 4 条', records.code === 0 && records.data.total === 4 && records.data.list.length === 4)
    check('列表 join 计划名/药品名', records.data.list.every((r) => r.planName && r.medicineName))
    check('列表含按时/错服布尔', records.data.list.every((r) => typeof r.onTime === 'boolean' && typeof r.wrongDrug === 'boolean'))
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
