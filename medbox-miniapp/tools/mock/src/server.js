/**
 * medbox 临时 Mock 后端（medbox-miniapp/tools/mock，**前端测试专用，不替代 medbox-server**）。
 *
 * 用途：后端接口未落地期间，用假数据把小程序（H5 / 微信开发者工具）跑通：
 * 登录注册、设备、药品、库存四条全部按 medbox-spec/02 的形状返回，错误码与权限口径
 * （02 的 1.2 / 1.3）也按文档模拟，方便前端验证各分支的提示与拦截。
 *
 * 运行：
 *   npm run mock               # 或 node tools/mock/src/server.js
 *   # 默认监听 8080（src/config/index.js 的 BASE_URL 端口固定 8080，勿改）
 *   # MOCK_ACCESS_TTL_MS=15000 npm run mock   # access token 15s 过期，验证 40101 自动续期
 *
 * 端到端测试（AI 完工时自动跑，见 AGENTS.md 3.1）：
 *   npm run test:e2e           # 自包含：自动在 18099 端口起服务、跑 46 项检查、跑完即关
 *
 * 用法：把 .env.local 的 VITE_SERVER_HOST 指向运行本脚本的机器 IP（127.0.0.1 亦可），
 * 小程序侧无需任何改动；H5 联调走既有的 Vite 反代（/medbox → 127.0.0.1:8080）。
 *
 * 辅助端点：POST /medbox/api/v1/__mock__/reset 重置内存数据（注销账号测试后用它恢复）。
 *
 * ⚠️ 纯内存、无持久化：重启即还原种子数据。
 * ⚠️ WebSocket 为**最小实现**：`/medbox/ws` 只完成握手 + `ping`/`pong`（让首页状态条变绿），
 * **不推业务帧**（ALARM / DEVICE_STATUS / REMINDER 留待 `medbox-server/` 真后端联调）。
 */

const http = require('node:http')
const crypto = require('node:crypto')
const { createSeed, DEMO_PASSWORD } = require('./data.js')

const PORT = Number(process.env.MOCK_PORT || 8080)
const API_PREFIX = '/medbox/api/v1'
const ACCESS_TTL_MS = Number(process.env.MOCK_ACCESS_TTL_MS || 2 * 60 * 60 * 1000)
const REFRESH_TTL_MS = 7 * 24 * 60 * 60 * 1000

let state = createSeed()

// ---------------------------------------------------------------- 基础工具

/** 业务错误（携带 02 的 1.2 封闭集合内的 code 与 HTTP 状态）。 */
class BizError extends Error {
  constructor(code, message) {
    super(message)
    this.code = code
    this.httpStatus = HTTP_STATUS[code] || 500
  }
}

const HTTP_STATUS = {
  0: 200,
  40001: 400,
  40101: 401,
  40102: 401,
  40301: 403,
  40302: 403,
  40401: 404,
  40901: 409,
  50000: 500,
}

const rand = () => Math.random().toString(36).slice(2, 10)
const traceId = () => Array.from({ length: 4 }, () => Math.random().toString(16).slice(2, 10)).join('')

function ok(res, data) {
  send(res, 200, { code: 0, message: 'success', data: data === undefined ? null : data, traceId: traceId(), timestamp: Math.floor(Date.now() / 1000) })
}

function fail(res, err) {
  const code = err instanceof BizError ? err.code : 50000
  const message = err instanceof BizError ? err.message : '服务端内部错误'
  const status = err instanceof BizError ? err.httpStatus : 500
  if (!(err instanceof BizError)) console.error('[mock] 未处理异常', err)
  send(res, status, { code, message, traceId: traceId(), timestamp: Math.floor(Date.now() / 1000) })
}

function send(res, status, body) {
  const payload = JSON.stringify(body)
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET,POST,PUT,PATCH,DELETE,OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type,Authorization,X-Request-Id',
  })
  res.end(payload)
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let raw = ''
    req.on('data', (chunk) => {
      raw += chunk
      if (raw.length > 1e6) req.destroy()
    })
    req.on('end', () => {
      if (!raw) return resolve({})
      try {
        resolve(JSON.parse(raw))
      } catch (err) {
        reject(new BizError(40001, '请求体不是合法 JSON'))
      }
    })
    req.on('error', reject)
  })
}

const isoNow = () => new Date().toISOString().replace(/\.\d{3}Z$/, 'Z')
const isValidDate = (v) => /^\d{4}-\d{2}-\d{2}$/.test(v)

// ---------------------------------------------------------------- 鉴权

function currentUser(req) {
  const auth = req.headers.authorization || ''
  const matched = /^Bearer mock\.([^.]+)\.(\d+)\./.exec(auth)
  if (!matched) return null
  const exp = Number(matched[2])
  if (!Number.isFinite(exp) || exp < Date.now()) return null
  const user = state.users.find((u) => u.userId === matched[1])
  if (!user || user.status !== 'ACTIVE') return null
  return user
}

/** 必须登录；40101（未登录 / token 过期，前端会走自动续期）。 */
function requireUser(req) {
  const user = currentUser(req)
  if (!user) throw new BizError(40101, '登录已失效，请重新登录')
  return user
}

function issueTokens(user) {
  const token = `mock.${user.userId}.${Date.now() + ACCESS_TTL_MS}.${rand()}`
  const refreshToken = `mref.${user.userId}.${Date.now() + REFRESH_TTL_MS}.${rand()}`
  state.refreshTokens.add(refreshToken)
  return { token, refreshToken, expiresIn: Math.floor(ACCESS_TTL_MS / 1000) }
}

// ---------------------------------------------------------------- 权限（02 的 1.3.1）

/**
 * 对目标老人的访问身份：
 * 'ok' 本人或 ACTIVE 监护 / 'pending' 关系 PENDING / 'none' 无关系或 REVOKED。
 */
function elderAccess(user, elderId) {
  if (!elderId) return 'none'
  if (user.role === 'ELDER') return elderId === user.userId ? 'ok' : 'none'
  const relation = state.relations.find((r) => r.guardianId === user.userId && r.elderId === elderId)
  if (!relation || relation.status === 'REVOKED') return 'none'
  if (relation.status === 'PENDING') return 'pending'
  return 'ok'
}

/** 我可见（ACTIVE）的老人 ID 集合 —— 列表行级过滤用。 */
function visibleElderIds(user) {
  if (user.role === 'ELDER') return [user.userId]
  return state.relations.filter((r) => r.guardianId === user.userId && r.status === 'ACTIVE').map((r) => r.elderId)
}

/** 设备维度：不存在 / 他人设备 / 传子设备编号一律 40301（02 第 4 章 device-core 口径）。 */
function requireDevice(user, deviceId) {
  const device = state.devices.find((d) => d.deviceId === deviceId)
  if (!device || device.deviceType !== 'MAIN') throw new BizError(40301, '无权限访问该资源')
  const access = elderAccess(user, device.elderId)
  if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
  if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
  return device
}

/** 写操作（药品档案与库存、补药入库 = WRITE_MEDICINE，仅 FAMILY；前端同口径只给监护人显入口）。
 *  elderId 为空 = 公共字典：只校验角色，不校验关系。 */
function requireWrite(user, elderId) {
  if (user.role !== 'GUARDIAN') throw new BizError(40301, '无权限执行该操作')
  if (!elderId) return
  const access = elderAccess(user, elderId)
  if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
  if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
}

function medicineAccess(user, medicine) {
  if (!medicine.ownerElderId) return 'ok'
  return elderAccess(user, medicine.ownerElderId)
}

function toDeviceSummary(device) {
  const summary = {
    deviceId: device.deviceId,
    deviceType: device.deviceType,
    elderId: device.elderId,
    productKey: device.productKey,
    timezone: device.timezone,
    onlineState: device.onlineState,
  }
  // 从未上线则整个字段缺失（mp-device 的 JSDoc 口径）
  if (device.lastHeartbeat) summary.lastHeartbeat = device.lastHeartbeat
  summary.sensorCount = state.devices.filter((d) => d.parentDeviceId === device.deviceId).length
  return summary
}

function toStockRow(stock) {
  const row = {
    stockId: stock.stockId,
    deviceId: stock.deviceId,
    elderId: stock.elderId,
    medicineId: stock.medicineId,
    quantity: stock.quantity,
  }
  // 后端全局 non_null：空日期字段直接省略
  if (stock.productionDate) row.productionDate = stock.productionDate
  if (stock.expiryDate) row.expiryDate = stock.expiryDate
  row.updatedAt = stock.updatedAt
  return row
}

// ---------------------------------------------------------------- 处理器

async function handleLogin(req, res, body) {
  const account = String(body.account || '').trim()
  const password = String(body.password || '')
  if (!account || !password) throw new BizError(40001, 'account / password 不能为空')
  const user = state.users.find((u) => u.status === 'ACTIVE' && (u.phone === account || u.username === account))
  // 账号不存在 / 已注销 / 密码错误，统一 40102（不区分"账号是否存在"）
  if (!user || user.password !== password) throw new BizError(40102, '账号或密码错误')
  const tokens = issueTokens(user)
  ok(res, { ...tokens, userId: user.userId, role: user.role, name: user.name })
}

async function handleRegister(req, res, body) {
  const phone = String(body.phone || '').trim()
  const password = String(body.password || '')
  const role = String(body.role || '').trim()
  const username = body.username ? String(body.username).trim() : ''
  const name = body.name ? String(body.name).trim() : ''
  if (!phone || !password) throw new BizError(40001, 'phone / password 不能为空')
  if (role !== 'ELDER' && role !== 'GUARDIAN') throw new BizError(40001, 'role 只能是 ELDER / GUARDIAN')
  if (state.users.some((u) => u.phone === phone)) throw new BizError(40901, '手机号已注册')
  if (username && state.users.some((u) => u.username === username)) throw new BizError(40901, '用户名已存在')
  const user = {
    userId: `u-${Date.now().toString(36)}`,
    role,
    name: name || (role === 'ELDER' ? '新老人' : '新监护人'),
    phone,
    username: username || '',
    password,
    status: 'ACTIVE',
  }
  state.users.push(user)
  const tokens = issueTokens(user)
  ok(res, { ...tokens, userId: user.userId, role: user.role, name: user.name })
}

async function handleRefresh(req, res, body) {
  const refreshToken = String(body.refreshToken || '')
  if (!state.refreshTokens.has(refreshToken)) throw new BizError(40101, '登录已失效，请重新登录')
  const matched = /^mref\.([^.]+)\./.exec(refreshToken)
  const user = matched && state.users.find((u) => u.userId === matched[1])
  if (!user || user.status !== 'ACTIVE') throw new BizError(40101, '登录已失效，请重新登录')
  // 轮换：旧 refresh 作废，发一对新的
  state.refreshTokens.delete(refreshToken)
  ok(res, issueTokens(user))
}

async function handleMe(req, res) {
  const user = requireUser(req)
  ok(res, { userId: user.userId, role: user.role, name: user.name, phone: user.phone, username: user.username })
}

async function handleDeleteAccount(req, res, body) {
  const user = requireUser(req)
  const password = String(body.password || '')
  if (!password) throw new BizError(40001, 'password 不能为空')
  if (user.password !== password) throw new BizError(40102, '账号或密码错误')
  // 软删除：释放手机号 / 用户名唯一键，撤销 refresh token（业务数据不级联删，02 的 3.1）
  user.status = 'DELETED'
  user.deletedAt = isoNow()
  user.phone = null
  user.username = null
  state.refreshTokens.forEach((token) => {
    if (token.startsWith(`mref.${user.userId}.`)) state.refreshTokens.delete(token)
  })
  ok(res, { userId: user.userId, deletedAt: user.deletedAt })
}

async function handleGetUserProfile(req, res, elderId) {
  const user = requireUser(req)
  // 02 第 3 章：老人本人或 ACTIVE 监护人可见；PENDING→40302；无关系/REVOKED/目标非老人→40301（防枚举）
  const access = elderAccess(user, elderId)
  if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
  if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
  const elder = state.users.find((u) => u.userId === elderId && u.role === 'ELDER')
  if (!elder) throw new BizError(40301, '无权限访问该资源')
  // 响应同 /users/me；null 字段按全局 non_null 省略（username 选填）
  const profile = { userId: elder.userId, role: elder.role, name: elder.name, phone: elder.phone }
  if (elder.username) profile.username = elder.username
  ok(res, profile)
}

async function handleListDevices(req, res) {
  const user = requireUser(req)
  const visible = visibleElderIds(user)
  const list = state.devices
    .filter((d) => d.deviceType === 'MAIN' && visible.indexOf(d.elderId) !== -1)
    .map(toDeviceSummary)
  ok(res, list)
}

async function handleGetDevice(req, res, deviceId) {
  const user = requireUser(req)
  const device = requireDevice(user, deviceId)
  const detail = { ...toDeviceSummary(device), createdAt: device.createdAt }
  detail.sensors = state.devices
    .filter((d) => d.parentDeviceId === device.deviceId)
    .map((s) => {
      const sensor = { deviceId: s.deviceId, deviceType: s.deviceType, onlineState: s.onlineState }
      if (s.lastHeartbeat) sensor.lastHeartbeat = s.lastHeartbeat
      return sensor
    })
  ok(res, detail)
}

// ---------------------------------------------------------------- 设备事件 / 命令（02 第 4 章；feat/device-command / device-fault 后端未落地，Mock 先行）

/**
 * 设备事件流水（`GET /devices/{deviceId}/events`）：分页、按 created_at 倒序（最新在前）。
 * deviceId 只接受 MAIN（requireDevice 已校验），子设备编号 → 40301。
 */
async function handleListEvents(req, res, deviceId, query) {
  const user = requireUser(req)
  requireDevice(user, deviceId) // 40301 / 40302 / 子设备 40301
  const rawPage = Number(query.page)
  const rawSize = Number(query.size)
  if (
    (query.page !== undefined && (Number.isNaN(rawPage) || rawPage < 1)) ||
    (query.size !== undefined && (Number.isNaN(rawSize) || rawSize < 1))
  ) {
    throw new BizError(40001, 'page / size 不能小于 1')
  }
  const page = Math.max(1, Number.isNaN(rawPage) ? 1 : rawPage)
  const size = Math.min(100, Math.max(1, Number.isNaN(rawSize) ? 20 : rawSize))
  const list = state.events
    .filter((e) => e.deviceId === deviceId)
    .sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)))
  const total = list.length
  ok(res, { list: list.slice((page - 1) * size, page * size), total })
}

/**
 * 下发控制命令（`POST /devices/{deviceId}/commands`）：异步受理，返回 {commandId, state:PENDING}；
 * Mock 同步落一条 COMMAND 事件（state=SUCCESS）模拟设备 ack（真实后端经 up/ack 异步驱动，见 02 第 4 章）。
 * 设备离线 → 40901；cmd 非法 → 40001；仅监护人（WRITE_DEVICE 仅 FAMILY）可下发。
 */
async function handleCommand(req, res, deviceId, body) {
  const user = requireUser(req)
  const device = requireDevice(user, deviceId) // 40301 / 40302 / 子设备 40301
  if (user.role !== 'GUARDIAN') throw new BizError(40301, '无权限执行该操作')
  if (device.onlineState === 'OFFLINE') throw new BizError(40901, '设备离线，无法下发控制命令')
  const cmd = String((body && body.cmd) || '').trim()
  if (!['BUZZ', 'UNLOCK', 'CALIBRATE', 'REBOOT'].includes(cmd)) {
    throw new BizError(40001, 'cmd 只能是 BUZZ / UNLOCK / CALIBRATE / REBOOT')
  }
  const commandId = `c-${rand()}`
  state.events.push({
    eventId: `ev-${rand()}`,
    deviceId,
    eventType: 'COMMAND',
    detail: { commandId, cmd, state: 'SUCCESS' },
    createdAt: isoNow(),
  })
  ok(res, { commandId, state: 'PENDING' })
}

async function handleListMedicines(req, res, query) {
  const user = requireUser(req)
  const visible = visibleElderIds(user)
  const keyword = String(query.keyword || '').trim().toLowerCase()
  let list = state.medicines.filter((m) => !m.ownerElderId || visible.indexOf(m.ownerElderId) !== -1)
  if (keyword) list = list.filter((m) => m.name.toLowerCase().indexOf(keyword) !== -1)
  const page = Math.max(1, Number(query.page) || 1)
  const size = Math.min(100, Math.max(1, Number(query.size) || 20))
  const total = list.length
  // 分页响应形状：02 第 5 章口径"暂定 list"（前端 parseMedicinePage 容错 records/items）
  ok(res, { list: list.slice((page - 1) * size, page * size), total })
}

async function handleGetMedicine(req, res, medicineId) {
  const user = requireUser(req)
  const medicine = state.medicines.find((m) => m.medicineId === medicineId)
  if (!medicine) throw new BizError(40401, '资源不存在')
  const access = medicineAccess(user, medicine)
  if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
  if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
  ok(res, { ...medicine, ownerElderId: medicine.ownerElderId || undefined })
}

async function handleSaveMedicine(req, res, body) {
  const user = requireUser(req)
  const name = String(body.name || '').trim()
  if (!name) throw new BizError(40001, 'name 不能为空')
  const ownerElderId = body.ownerElderId ? String(body.ownerElderId) : ''
  if (ownerElderId) requireWrite(user, ownerElderId)
  else requireWrite(user, null) // 公共字典同样只有监护人能建（role 校验）

  const medicineId = body.medicineId ? String(body.medicineId) : ''
  const existing = medicineId ? state.medicines.find((m) => m.medicineId === medicineId) : null
  if (existing) {
    const access = medicineAccess(user, existing)
    if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
    if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
    existing.name = name
    existing.spec = body.spec ? String(body.spec) : ''
    existing.dosePerTime = body.dosePerTime ? String(body.dosePerTime) : ''
    existing.unit = body.unit ? String(body.unit) : ''
    existing.contraindication = body.contraindication ? String(body.contraindication) : ''
    existing.storageCond = body.storageCond ? String(body.storageCond) : ''
    existing.ownerElderId = ownerElderId
    ok(res, { ...existing })
    return
  }
  const medicine = {
    medicineId: medicineId || `m-${Date.now().toString(36)}`,
    ownerElderId,
    name,
    spec: body.spec ? String(body.spec) : '',
    dosePerTime: body.dosePerTime ? String(body.dosePerTime) : '',
    unit: body.unit ? String(body.unit) : '',
    contraindication: body.contraindication ? String(body.contraindication) : '',
    storageCond: body.storageCond ? String(body.storageCond) : '',
    createdAt: isoNow(),
  }
  state.medicines.push(medicine)
  ok(res, { ...medicine })
}

async function handleDeleteMedicine(req, res, medicineId) {
  const user = requireUser(req)
  const medicine = state.medicines.find((m) => m.medicineId === medicineId)
  if (!medicine) throw new BizError(40401, '资源不存在')
  const access = medicineAccess(user, medicine)
  if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
  if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
  if (user.role !== 'GUARDIAN') throw new BizError(40301, '无权限执行该操作')
  // 无任何药箱库存引用时才允许删除（02 第 5 章）
  if (state.stocks.some((s) => s.medicineId === medicineId)) {
    throw new BizError(40901, '药品仍被库存引用，无法删除')
  }
  state.medicines = state.medicines.filter((m) => m.medicineId !== medicineId)
  ok(res, null)
}

async function handleListStocks(req, res, deviceId) {
  const user = requireUser(req)
  requireDevice(user, deviceId)
  // 响应形状：02 第 5 章口径"暂按裸数组"（前端 parseStocks 容错 {list} 等）
  const list = state.stocks.filter((s) => s.deviceId === deviceId).map(toStockRow)
  ok(res, list)
}

/** 录入/更新与补药入库共用的参数校验（PUT 绝对值 / POST-in 增量在调用方区分）。 */
function validateStockBody(body) {
  const quantity = Number(body.quantity)
  if (!Number.isInteger(quantity) || quantity <= 0) {
    throw new BizError(40001, 'quantity 必须为正整数')
  }
  const productionDate = body.productionDate ? String(body.productionDate) : ''
  const expiryDate = body.expiryDate ? String(body.expiryDate) : ''
  if (productionDate && !isValidDate(productionDate)) {
    throw new BizError(40001, 'productionDate 格式应为 YYYY-MM-DD')
  }
  if (expiryDate && !isValidDate(expiryDate)) {
    throw new BizError(40001, 'expiryDate 格式应为 YYYY-MM-DD')
  }
  return { quantity, productionDate, expiryDate }
}

/** 定位库存行并做设备 + 药品 + 写权限校验，返回 { device, medicine, stock }。 */
function resolveStockContext(user, deviceId, medicineId) {
  const device = requireDevice(user, deviceId)
  const medicine = state.medicines.find((m) => m.medicineId === medicineId)
  if (!medicine) throw new BizError(40401, '资源不存在')
  const access = medicineAccess(user, medicine)
  if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
  if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
  requireWrite(user, device.elderId)
  const stock = state.stocks.find((s) => s.deviceId === deviceId && s.medicineId === medicineId) || null
  return { device, medicine, stock }
}

async function handleSaveStock(req, res, deviceId, medicineId, body) {
  const user = requireUser(req)
  const { device } = resolveStockContext(user, deviceId, medicineId)
  const { quantity, productionDate, expiryDate } = validateStockBody(body)
  const existing = state.stocks.find((s) => s.deviceId === deviceId && s.medicineId === medicineId)
  if (existing) {
    existing.quantity = quantity // PUT：绝对值覆盖
    if (productionDate) existing.productionDate = productionDate
    if (expiryDate) existing.expiryDate = expiryDate
    existing.updatedAt = isoNow()
    ok(res, toStockRow(existing))
    return
  }
  const stock = {
    stockId: `st-${Date.now().toString(36)}`,
    deviceId,
    elderId: device.elderId,
    medicineId,
    quantity,
    productionDate,
    expiryDate,
    updatedAt: isoNow(),
  }
  state.stocks.push(stock)
  ok(res, toStockRow(stock))
}

async function handleRestockStock(req, res, deviceId, medicineId, body) {
  const user = requireUser(req)
  const { device } = resolveStockContext(user, deviceId, medicineId)
  const { quantity, productionDate, expiryDate } = validateStockBody(body)
  const existing = state.stocks.find((s) => s.deviceId === deviceId && s.medicineId === medicineId)
  if (existing) {
    existing.quantity += quantity // POST /in：增量
    if (productionDate) existing.productionDate = productionDate
    if (expiryDate) existing.expiryDate = expiryDate
    existing.updatedAt = isoNow()
    ok(res, toStockRow(existing))
    return
  }
  // 该药箱还没有此药品库存： Mock 从宽按"首录"处理（真实后端行为待定，联调时对齐）
  const stock = {
    stockId: `st-${Date.now().toString(36)}`,
    deviceId,
    elderId: device.elderId,
    medicineId,
    quantity,
    productionDate,
    expiryDate,
    updatedAt: isoNow(),
  }
  state.stocks.push(stock)
  ok(res, toStockRow(stock))
}

async function handleDeleteStock(req, res, deviceId, medicineId) {
  const user = requireUser(req)
  resolveStockContext(user, deviceId, medicineId)
  const index = state.stocks.findIndex((s) => s.deviceId === deviceId && s.medicineId === medicineId)
  if (index === -1) throw new BizError(40401, '库存记录不存在')
  state.stocks.splice(index, 1)
  ok(res, null)
}

// ---------------------------------------------------------------- 计划（02 第 6 章）

const TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/

/** 药品在该老人药箱的库存状态：ok（有库存）/ no-stock（无任何库存行）。 */
function medicineStockState(elderId, medicineId) {
  const deviceIds = state.devices
    .filter((d) => d.deviceType === 'MAIN' && d.elderId === elderId)
    .map((d) => d.deviceId)
  const rows = state.stocks.filter(
    (s) => deviceIds.indexOf(s.deviceId) !== -1 && s.medicineId === medicineId,
  )
  if (!rows.length) return 'no-stock'
  return rows.some((s) => s.quantity != null && s.quantity > 0) ? 'ok' : 'no-stock'
}

function planAccess(user, plan) {
  if (!plan) return 'none'
  return elderAccess(user, plan.elderId)
}

/** 计划头 + items 校验（06 的 2.6.1 约束①：同计划药品不重复）；返回归一化结构。 */
function validatePlanBody(body) {
  const elderId = String(body.elderId || '').trim()
  if (!elderId) throw new BizError(40001, 'elderId 不能为空')
  const name = String(body.name || '').trim()
  if (!name) throw new BizError(40001, 'name 不能为空')
  const times = []
  ;(Array.isArray(body.times) ? body.times : []).forEach((item) => {
    const value = String(item || '').trim()
    if (!TIME_PATTERN.test(value)) throw new BizError(40001, `times 格式不正确: ${value || '(空)'}`)
    if (times.indexOf(value) === -1) times.push(value)
  })
  if (!times.length) throw new BizError(40001, 'times 至少需要一个')
  const repeatRule = body.repeatRule ? String(body.repeatRule).trim() : ''
  const rawItems = Array.isArray(body.items) ? body.items : []
  if (!rawItems.length) throw new BizError(40001, 'items 至少需要一种药品')
  const items = []
  rawItems.forEach((item) => {
    const medicineId = String((item && item.medicineId) || '').trim()
    if (!medicineId) throw new BizError(40001, 'items[].medicineId 不能为空')
    if (items.some((exist) => exist.medicineId === medicineId)) {
      throw new BizError(40001, `同一计划内药品不能重复: ${medicineId}`)
    }
    items.push({
      medicineId,
      dose: item.dose ? String(item.dose) : '',
      unit: item.unit ? String(item.unit) : '',
      note: item.note ? String(item.note) : '',
    })
  })
  return { elderId, name, times, repeatRule, items }
}

/** 创建 / 更新共用业务校验：老人可见 + 每项药品可见且有未过期库存（06 的 2.6.1 约束②）。 */
function requirePlanWrite(user, normalized) {
  requireWrite(user, normalized.elderId)
  normalized.items.forEach((item) => {
    const medicine = state.medicines.find((m) => m.medicineId === item.medicineId)
    if (!medicine) throw new BizError(40401, `药品不存在: ${item.medicineId}`)
    const access = medicineAccess(user, medicine)
    if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
    if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
    const stockState = medicineStockState(normalized.elderId, item.medicineId)
    if (stockState !== 'ok') {
      throw new BizError(40901, `药箱暂无库存: ${item.medicineId}`)
    }
  })
}

function planItemsOf(planId) {
  return state.planItems
    .filter((item) => item.planId === planId)
    .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
}

function toPlanDetail(plan) {
  return {
    planId: plan.planId,
    elderId: plan.elderId,
    name: plan.name,
    times: plan.times.slice(),
    repeatRule: plan.repeatRule,
    status: plan.status,
    syncState: plan.syncState,
    items: planItemsOf(plan.planId).map((item) => ({ ...item })),
    createdAt: plan.createdAt,
  }
}

async function handleListPlans(req, res, query) {
  const user = requireUser(req)
  const elderId = String(query.elderId || '').trim()
  if (!elderId) throw new BizError(40001, 'elderId 不能为空')
  const access = elderAccess(user, elderId)
  if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
  if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
  const list = state.plans.filter((plan) => plan.elderId === elderId)
  const page = Math.max(1, Number(query.page) || 1)
  const size = Math.min(100, Math.max(1, Number(query.size) || 20))
  const total = list.length
  // 列表项含 items 概览（02 第 6 章；药品名由小程序 join 药品字典）
  ok(res, { list: list.slice((page - 1) * size, page * size).map(toPlanDetail), total })
}

async function handleGetPlan(req, res, planId) {
  const user = requireUser(req)
  const plan = state.plans.find((p) => p.planId === planId)
  if (!plan) throw new BizError(40401, '资源不存在')
  const access = planAccess(user, plan)
  if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
  if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
  ok(res, toPlanDetail(plan))
}

async function handleGetNextDoses(req, res, planId, query) {
  const user = requireUser(req)
  const plan = state.plans.find((p) => p.planId === planId)
  if (!plan) throw new BizError(40401, '资源不存在')
  const access = planAccess(user, plan)
  if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
  if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
  // 停用计划：02/07 约定返回空（feat/mp-plan-timeline 空态之一）
  if (plan.status === 'DISABLED') {
    return ok(res, { doses: [] })
  }
  // days：默认 7、上限 60（与后端一致）；越界 40001
  let days = 7
  if (query.days !== undefined && String(query.days).trim() !== '') {
    days = Number(query.days)
    if (!Number.isInteger(days) || days < 1 || days > 60) {
      throw new BizError(40001, 'days 超出范围(1~60)')
    }
  }
  const items = planItemsOf(plan.planId).map((item) => {
    const medicine = state.medicines.find((m) => m.medicineId === item.medicineId)
    return {
      medicineId: item.medicineId,
      medicineName: medicine ? medicine.name : item.medicineId,
      dose: item.dose,
      unit: item.unit,
    }
  })
  // 按设备本地墙钟展开展望窗口（Mock 用本地日期；WEEKLY 仅演示照展，真后端本期仅 DAILY）
  const base = new Date()
  base.setHours(0, 0, 0, 0)
  const doses = []
  for (let offset = 0; offset < days; offset += 1) {
    const d = new Date(base)
    d.setDate(base.getDate() + offset)
    const dateStr = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    plan.times.forEach((time) => {
      const local = `${dateStr}T${time}:00`
      const utc = new Date(local).toISOString()
      doses.push({ scheduledTimeLocal: local, scheduledTimeUtc: utc, items: items.map((it) => ({ ...it })) })
    })
  }
  ok(res, { doses })
}

async function handleCreatePlan(req, res, body) {
  const user = requireUser(req)
  const normalized = validatePlanBody(body)
  requirePlanWrite(user, normalized)
  const plan = {
    planId: `p-${Date.now().toString(36)}`,
    elderId: normalized.elderId,
    name: normalized.name,
    times: normalized.times,
    repeatRule: normalized.repeatRule,
    status: 'ENABLED',
    syncState: 'SYNCED_TO_DEVICE',
    createdAt: isoNow(),
  }
  state.plans.push(plan)
  normalized.items.forEach((item, index) => {
    state.planItems.push({
      itemId: `pi-${Date.now().toString(36)}-${index}`,
      planId: plan.planId,
      medicineId: item.medicineId,
      dose: item.dose,
      unit: item.unit,
      note: item.note,
      sortOrder: index,
    })
  })
  ok(res, toPlanDetail(plan))
}

async function handleUpdatePlan(req, res, planId, body) {
  const user = requireUser(req)
  const plan = state.plans.find((p) => p.planId === planId)
  if (!plan) throw new BizError(40401, '资源不存在')
  const access = planAccess(user, plan)
  if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
  if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
  const normalized = validatePlanBody(body)
  requirePlanWrite(user, normalized)
  plan.name = normalized.name
  plan.times = normalized.times
  plan.repeatRule = normalized.repeatRule
  // 整体覆盖（02 第 6 章）：旧明细全删，按新 items 重建
  state.planItems = state.planItems.filter((item) => item.planId !== plan.planId)
  normalized.items.forEach((item, index) => {
    state.planItems.push({
      itemId: `pi-${Date.now().toString(36)}-${index}`,
      planId: plan.planId,
      medicineId: item.medicineId,
      dose: item.dose,
      unit: item.unit,
      note: item.note,
      sortOrder: index,
    })
  })
  ok(res, toPlanDetail(plan))
}

async function handleUpdatePlanStatus(req, res, planId, body) {
  const user = requireUser(req)
  const plan = state.plans.find((p) => p.planId === planId)
  if (!plan) throw new BizError(40401, '资源不存在')
  const access = planAccess(user, plan)
  if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
  if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
  if (user.role !== 'GUARDIAN') throw new BizError(40301, '无权限执行该操作')
  // 前端先行契约：{status: 'ENABLED' | 'DISABLED'}（02 未定义 body）
  const status = String(body.status || '').trim()
  if (status !== 'ENABLED' && status !== 'DISABLED') {
    throw new BizError(40001, 'status 只能是 ENABLED / DISABLED')
  }
  plan.status = status
  ok(res, toPlanDetail(plan))
}

async function handleDeletePlan(req, res, planId) {
  const user = requireUser(req)
  const plan = state.plans.find((p) => p.planId === planId)
  if (!plan) throw new BizError(40401, '资源不存在')
  const access = planAccess(user, plan)
  if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
  if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
  if (user.role !== 'GUARDIAN') throw new BizError(40301, '无权限执行该操作')
  state.plans = state.plans.filter((p) => p.planId !== planId)
  // 级联删除明细（06 的 2.6.1 约束③）
  state.planItems = state.planItems.filter((item) => item.planId !== planId)
  ok(res, null)
}

// ---------------------------------------------------------------- 告警（02 第 8 章；feat/alarm-core 已落地，Mock 先行）

/**
 * 告警分页（`GET /alarms`）：按 `created_at` 倒序（最新在前）、分页；`type` / `readState` 过滤。
 * 属 `READ_ALARM`（四角色可读），按 elderId 行级鉴权（pending→40302 / 无关系→40301）。
 */
async function handleListAlarms(req, res, query) {
  const user = requireUser(req)
  const elderId = String(query.elderId || '').trim()
  if (!elderId) throw new BizError(40001, 'elderId 不能为空')
  const access = elderAccess(user, elderId)
  if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
  if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
  const rawPage = Number(query.page)
  const rawSize = Number(query.size)
  if (
    (query.page !== undefined && (Number.isNaN(rawPage) || rawPage < 1)) ||
    (query.size !== undefined && (Number.isNaN(rawSize) || rawSize < 1))
  ) {
    throw new BizError(40001, 'page / size 不能小于 1')
  }
  const page = Math.max(1, Number.isNaN(rawPage) ? 1 : rawPage)
  const size = Math.min(100, Math.max(1, Number.isNaN(rawSize) ? 20 : rawSize))
  let list = state.alarms.filter((a) => a.elderId === elderId)
  if (query.type && query.type !== 'ALL') list = list.filter((a) => a.type === query.type)
  if (query.readState && query.readState !== 'ALL') list = list.filter((a) => a.readState === query.readState)
  list = list.sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)))
  const total = list.length
  ok(res, { list: list.slice((page - 1) * size, page * size), total })
}

/**
 * 未读告警数（`GET /alarms/unread-count`）：驱动角标；按 elderId 行级鉴权。
 */
async function handleUnreadCount(req, res, query) {
  const user = requireUser(req)
  const elderId = String(query.elderId || '').trim()
  if (!elderId) throw new BizError(40001, 'elderId 不能为空')
  const access = elderAccess(user, elderId)
  if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
  if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
  const count = state.alarms.filter((a) => a.elderId === elderId && a.readState === 'UNREAD').length
  ok(res, { count })
}

/**
 * 标记已读（`PATCH /alarms/{alarmId}/read`）：四角色（READ_ALARM）；告警不存在 → 40401；无关系 → 40301。
 */
async function handleMarkAlarmRead(req, res, alarmId) {
  const user = requireUser(req)
  const alarm = state.alarms.find((a) => a.alarmId === alarmId)
  if (!alarm) throw new BizError(40401, '资源不存在')
  const access = elderAccess(user, alarm.elderId)
  if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
  if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
  alarm.readState = 'READ'
  ok(res, null)
}

/**
 * 处理/确认告警（`PATCH /alarms/{alarmId}/handle`）：`HANDLE_ALARM` = FAMILY + NURSE；小程序注册角色
 * 仅 GUARDIAN，故此处只放行 GUARDIAN（DOCTOR/NURSE/ELDER 写返回 40301，真后端仍按 HANDLE_ALARM 强校验）。
 */
async function handleHandleAlarm(req, res, alarmId) {
  const user = requireUser(req)
  if (user.role !== 'GUARDIAN') throw new BizError(40301, '无权限执行该操作')
  const alarm = state.alarms.find((a) => a.alarmId === alarmId)
  if (!alarm) throw new BizError(40401, '资源不存在')
  const access = elderAccess(user, alarm.elderId)
  if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
  if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
  alarm.handled = true
  alarm.readState = 'READ'
  ok(res, null)
}

/**
 * 查询告警配置（`GET /alarm-settings`）：四角色（READ_ALARM）；按 elderId 行级鉴权；缺则回默认。
 */
async function handleGetAlarmSettings(req, res, query) {
  const user = requireUser(req)
  const elderId = String(query.elderId || '').trim()
  if (!elderId) throw new BizError(40001, 'elderId 不能为空')
  const access = elderAccess(user, elderId)
  if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
  if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
  ok(res, state.alarmSettings[elderId] || defaultAlarmSettings(elderId))
}

/**
 * 配置告警阈值（`PUT /alarm-settings`）：`WRITE_ALARM_SETTINGS` 仅 FAMILY（DOCTOR/NURSE/ELDER → 40301）。
 * 字段可选、仅提供项覆盖原值（elderId 走 query）。
 */
async function handleSaveAlarmSettings(req, res, query, body) {
  const user = requireUser(req)
  if (user.role !== 'GUARDIAN') throw new BizError(40301, '无权限执行该操作')
  const elderId = String(query.elderId || '').trim()
  if (!elderId) throw new BizError(40001, 'elderId 不能为空')
  const access = elderAccess(user, elderId)
  if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
  if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
  const cur = state.alarmSettings[elderId] || defaultAlarmSettings(elderId)
  if (body && typeof body === 'object') {
    if (Number.isFinite(Number(body.advanceMin))) cur.advanceMin = Number(body.advanceMin)
    if (Number.isFinite(Number(body.missTolerateMin))) cur.missTolerateMin = Number(body.missTolerateMin)
    if (body.envThreshold && typeof body.envThreshold === 'object') {
      ;['temp', 'humidity', 'light'].forEach((k) => {
        const src = body.envThreshold[k]
        if (src && typeof src === 'object') {
          cur.envThreshold[k] = {
            min: Number.isFinite(Number(src.min)) ? Number(src.min) : cur.envThreshold[k].min,
            max: Number.isFinite(Number(src.max)) ? Number(src.max) : cur.envThreshold[k].max,
          }
        }
      })
    }
  }
  cur.elderId = elderId
  state.alarmSettings[elderId] = cur
  ok(res, null)
}

/** 告警设置默认值（老人级；字段对齐 02 第 8/9 章 + 06 §2.6.1）。 */
function defaultAlarmSettings(elderId) {
  return {
    elderId,
    advanceMin: 10,
    missTolerateMin: 15,
    envThreshold: {
      temp: { min: 15, max: 30 },
      humidity: { min: 30, max: 70 },
      light: { min: 50, max: 500 },
    },
  }
}

// ---------------------------------------------------------------- 记录（02 第 7 章；capture-core 属 P1，缩略图前端留空占位）

/** 取 ISO 字符串的日期部分（YYYY-MM-DD），用于 from/to 日粒度过滤。 */
function dateOf(iso) {
  return iso ? String(iso).slice(0, 10) : ''
}

/** 记录 → 视图对象：join 计划名 / 药品名（与计划详情同样从 state 派生，保持单一数据源）。 */
function buildRecordView(record) {
  const plan = state.plans.find((p) => p.planId === record.planId) || null
  const item = state.planItems.find((it) => it.itemId === record.planItemId) || null
  const medicine = state.medicines.find((m) => m.medicineId === record.medicineId) || null
  const view = {
    recordId: record.recordId,
    elderId: record.elderId,
    planId: record.planId,
    planItemId: record.planItemId,
    medicineId: record.medicineId,
    deviceId: record.deviceId,
    captureId: record.captureId || undefined,
    actualDose: record.actualDose,
    planTime: record.planTime,
    actualTime: record.actualTime,
    onTime: !!record.onTime,
    confirmedBy: record.confirmedBy || undefined,
    confirmedAt: record.confirmedAt || undefined,
    // 待确认态（feat/mp-capture-event）：后端为派生态，Mock 直接存派生标志；
    // confidence / faceResult 一并回传供前端兜底展示。
    pendingConfirm: !!record.pendingConfirm,
    faceResult: record.faceResult || undefined,
    confidence: typeof record.confidence === 'number' ? record.confidence : undefined,
    planName: (plan && plan.name) || '未命名计划',
    medicineName: (medicine && medicine.name) || record.medicineId || '未知药品',
    medicineDose: (item && item.dose) || (medicine && medicine.dosePerTime) || '',
    medicineUnit: (item && item.unit) || (medicine && medicine.unit) || '',
  }
  return view
}

async function handleListRecords(req, res, query) {
  const user = requireUser(req)
  const elderId = String(query.elderId || '').trim()
  if (!elderId) throw new BizError(40001, 'elderId 不能为空')
  const access = elderAccess(user, elderId)
  if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
  if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
  const from = String(query.from || '').trim()
  const to = String(query.to || '').trim()
  let list = state.records.filter((r) => r.elderId === elderId)
  if (from && isValidDate(from)) list = list.filter((r) => dateOf(r.planTime) >= from)
  if (to && isValidDate(to)) list = list.filter((r) => dateOf(r.planTime) <= to)
  // 待确认筛选（feat/mp-capture-event）：监护人"仅看待确认"
  if (query.pendingConfirm === 'true' || query.pendingConfirm === true) {
    list = list.filter((r) => r.pendingConfirm)
  }
  const page = Math.max(1, Number(query.page) || 1)
  const size = Math.min(100, Math.max(1, Number(query.size) || 20))
  const total = list.length
  // 分页响应形状：02 第 7 章口径暂定 {list,total}（前端 parseRecordsPage 容错 records/items）
  ok(res, { list: list.slice((page - 1) * size, page * size).map(buildRecordView), total })
}

async function handleGetRecord(req, res, recordId) {
  const user = requireUser(req)
  const record = state.records.find((r) => r.recordId === recordId)
  if (!record) throw new BizError(40401, '资源不存在')
  const access = elderAccess(user, record.elderId)
  if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
  if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
  ok(res, buildRecordView(record))
}

async function handleConfirmRecord(req, res, recordId, body) {
  const user = requireUser(req)
  const record = state.records.find((r) => r.recordId === recordId)
  if (!record) throw new BizError(40401, '资源不存在')
  // 写权限：仅 FAMILY（GUARDIAN）且关系 ACTIVE（requireWrite 内部已校验 40301 / 40302）
  requireWrite(user, record.elderId)
  record.confirmedBy = user.userId
  record.confirmedAt = isoNow()
  if (body && body.note) record.note = String(body.note)
  // 待确认人工确认（feat/mp-capture-event，02 §7.1）：监护人看图后改写 faceResult，
  // confidence 置空、pendingConfirm 解除；确认 TAKEN 触发库存扣减（Mock 不落库存）。
  if (body && (body.faceResult === 'TAKEN' || body.faceResult === 'ABSENT')) {
    record.faceResult = body.faceResult
    record.confidence = null
    record.pendingConfirm = false
  }
  ok(res, buildRecordView(record))
}

// ---------------------------------------------------------------- 抓拍（02 设备管理.md §4.1；feat/mp-capture-event）

/** 1x1 透明 PNG（占位原图；Mock 不存真图）。 */
const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAMBAQDJ/pLvAAAAAElFTkSuQmCC',
  'base64',
)

async function handleGetCapture(req, res, captureId) {
  const user = requireUser(req)
  const capture = state.captures.find((c) => c.captureId === captureId)
  if (!capture) throw new BizError(40401, '抓拍不存在')
  const access = elderAccess(user, capture.elderId)
  if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
  if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
  // fileToken：5 分钟有效（Mock 仅作格式校验，前缀含 captureId）
  const fileToken = `tok-${captureId}-${Date.now() + 5 * 60 * 1000}-${rand()}`
  ok(res, {
    captureId: capture.captureId,
    deviceId: capture.deviceId,
    elderId: capture.elderId,
    medicineId: capture.medicineId || undefined,
    planId: capture.planId || undefined,
    reason: capture.reason || 'NORMAL',
    mime: capture.mime || 'image/png',
    sizeBytes: capture.sizeBytes || 0,
    confidence: typeof capture.confidence === 'number' ? capture.confidence : undefined,
    faceResult: capture.faceResult || undefined,
    capturedAt: capture.capturedAt,
    viewedAt: capture.viewedAt || undefined,
    expireAt: capture.expireAt || undefined,
    fileToken,
  })
}

async function handleGetCaptureFile(req, res, captureId, query) {
  const token = String(query.token || '').trim()
  // 令牌校验（02 §4.1）：格式非法 / 不匹配 captureId → 40101；二次复核监护关系。
  if (!token || !token.startsWith(`tok-${captureId}-`)) throw new BizError(40101, '令牌无效或已过期')
  const capture = state.captures.find((c) => c.captureId === captureId)
  if (!capture) throw new BizError(40401, '抓拍不存在或已清理')
  // 文件流不走统一响应体（02 §4.1）：直接返回二进制，Cache-Control: no-store
  res.writeHead(200, {
    'Content-Type': capture.mime || 'image/png',
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Authorization',
  })
  res.end(PNG_1X1)
}

// ---------------------------------------------------------------- 抓拍列表 / 删除（02 设备管理.md §4.1；feat/mp-capture）
async function handleListCaptures(req, res, query) {
  const user = requireUser(req)
  const elderId = String(query.elderId || '').trim()
  if (!elderId) throw new BizError(40001, '缺少 elderId')
  const access = elderAccess(user, elderId)
  if (access === 'pending') throw new BizError(40302, '监护关系未生效，请等待老人确认')
  if (access !== 'ok') throw new BizError(40301, '无权限访问该资源')
  let list = state.captures.filter((c) => c.elderId === elderId)
  const reason = String(query.reason || '').trim()
  if (reason) list = list.filter((c) => (c.reason || 'NORMAL') === reason)
  const from = String(query.from || '').trim()
  const to = String(query.to || '').trim()
  if (from && isValidDate(from)) list = list.filter((c) => dateOf(c.capturedAt) >= from)
  if (to && isValidDate(to)) list = list.filter((c) => dateOf(c.capturedAt) <= to)
  const page = Math.max(1, Number(query.page) || 1)
  const size = Math.min(200, Math.max(1, Number(query.size) || 20))
  const total = list.length
  const start = (page - 1) * size
  const pageItems = list.slice(start, start + size).map((c) => ({
    captureId: c.captureId,
    deviceId: c.deviceId,
    elderId: c.elderId,
    medicineId: c.medicineId || undefined,
    planId: c.planId || undefined,
    reason: c.reason || 'NORMAL',
    mime: c.mime || 'image/png',
    sizeBytes: c.sizeBytes || 0,
    confidence: typeof c.confidence === 'number' ? c.confidence : undefined,
    faceResult: c.faceResult || undefined,
    capturedAt: c.capturedAt,
    viewedAt: c.viewedAt || undefined,
    expireAt: c.expireAt || undefined,
    fileToken: `tok-${c.captureId}-${Date.now() + 5 * 60 * 1000}-${rand()}`,
  }))
  ok(res, { list: pageItems, total, page, size })
}

async function handleDeleteCapture(req, res, captureId) {
  const user = requireUser(req)
  const capture = state.captures.find((c) => c.captureId === captureId)
  if (!capture) throw new BizError(40401, '抓拍不存在或已清理')
  // WRITE_CAPTURE 仅 GUARDIAN（老人本人 / 护理 / 医生 40301；监护关系 PENDING 40302）
  requireWrite(user, capture.elderId)
  state.captures = state.captures.filter((c) => c.captureId !== captureId)
  ok(res, null)
}

// ---------------------------------------------------------------- 监护关系四条（02 的 3.2）

/** 关系响应形状：role 未指定时省略（02 的 3.2 示例口径）。 */
function toRelationView(relation) {
  const view = {
    relationId: relation.relationId,
    elderId: relation.elderId,
    guardianId: relation.guardianId,
    status: relation.status,
    createdAt: relation.createdAt,
  }
  if (relation.role) view.role = relation.role
  return view
}

/** ① 申请绑定（监护人发起，无请求体）：无关系新建 / REVOKED 复活 / PENDING·ACTIVE 40901。 */
async function handleApplyGuardian(req, res, elderId) {
  const user = requireUser(req)
  if (user.role !== 'GUARDIAN') throw new BizError(40301, '只有监护人账号可以发起监护申请')
  // 目标必须是真实老人（防枚举，与"无权限"同码，不回 40401）
  const elder = state.users.find((u) => u.userId === elderId && u.role === 'ELDER' && u.status === 'ACTIVE')
  if (!elder) throw new BizError(40301, '目标用户不存在或不是老人账号')
  const existing = state.relations.find((r) => r.guardianId === user.userId && r.elderId === elderId)
  if (existing && existing.status !== 'REVOKED') throw new BizError(40901, '已存在进行中或已生效的监护关系')
  if (existing) {
    // REVOKED → 复活为 PENDING，并清空 role（后端 reactivatePending 显式置 NULL）
    existing.status = 'PENDING'
    existing.role = null
    existing.createdAt = isoNow()
    return ok(res, toRelationView(existing))
  }
  const relation = {
    relationId: `gr-${rand()}`,
    guardianId: user.userId,
    elderId,
    role: null,
    status: 'PENDING',
    createdAt: isoNow(),
  }
  state.relations.push(relation)
  ok(res, toRelationView(relation))
}

/** ② 待确认列表（老人端）：join 监护人姓名 + 手机，按 createdAt 升序。 */
async function handlePendingGuardians(req, res) {
  const user = requireUser(req)
  if (user.role !== 'ELDER') throw new BizError(40301, '只有老人账号可以查看待确认列表')
  const list = state.relations
    .filter((r) => r.elderId === user.userId && r.status === 'PENDING')
    .sort((a, b) => String(a.createdAt).localeCompare(String(b.createdAt)))
    .map((r) => {
      const guardian = state.users.find((u) => u.userId === r.guardianId)
      return {
        relationId: r.relationId,
        elderId: r.elderId,
        guardianId: r.guardianId,
        guardianName: (guardian && guardian.name) || '',
        guardianPhone: (guardian && guardian.phone) || '',
        createdAt: r.createdAt,
      }
    })
  ok(res, list)
}

/** ③ 确认（老人本人）：role 必填（FAMILY / DOCTOR / NURSE），仅 PENDING → ACTIVE。 */
async function handleAcceptGuardian(req, res, relationId, body) {
  const user = requireUser(req)
  if (user.role !== 'ELDER') throw new BizError(40301, '只有老人账号可以确认监护请求')
  const role = String((body && body.role) || '').trim()
  if (!role) throw new BizError(40001, 'role 不能为空')
  if (role !== 'FAMILY' && role !== 'DOCTOR' && role !== 'NURSE') {
    throw new BizError(40001, 'role 只能是 FAMILY / DOCTOR / NURSE')
  }
  // 查不到 → 40301（防枚举，不回 40401）
  const relation = state.relations.find((r) => r.relationId === relationId)
  if (!relation) throw new BizError(40301, '监护关系不存在')
  if (relation.elderId !== user.userId) throw new BizError(40301, '只能确认发给自己的监护请求')
  if (relation.status !== 'PENDING') throw new BizError(40901, '该监护请求已被处理')
  relation.role = role
  relation.status = 'ACTIVE'
  ok(res, toRelationView(relation))
}

/** ④ 拒绝 / 解除（关系任一方）：软删置 REVOKED，幂等。 */
async function handleRevokeGuardian(req, res, relationId) {
  const user = requireUser(req)
  const relation = state.relations.find((r) => r.relationId === relationId)
  if (!relation) throw new BizError(40301, '监护关系不存在')
  if (relation.elderId !== user.userId && relation.guardianId !== user.userId) {
    throw new BizError(40301, '只有关系双方可以解除监护关系')
  }
  if (relation.status !== 'REVOKED') relation.status = 'REVOKED'
  ok(res, null)
}

function handleReset(req, res) {
  state = createSeed()
  ok(res, { reset: true, accounts: DEMO_ACCOUNTS })
}

// ---------------------------------------------------------------- 联调辅助（02 的 1.5）

/**
 * `POST /_debug/seed`：一键播种最小链路数据集。
 *
 * <p>形状对齐后端 `DevtoolsSeedService` / `SeedResult`（含两个 access token），
 * 使 02 §1.5 与 03 §5 的 curl 示例在本 Mock 上同样跑得通。
 * **免登录**（真后端把 seed / mqtt/** 排除在 AuthInterceptor 之外，见 `WebMvcConfig`）。
 *
 * <p>⚠️ 返回的是 **Mock 自己的种子 ID**（`u-2001` / `m-201` / `p-3301` ...），与后端
 * devtools 种子的 `u-1001` / `m-205` 并不相同；页面只用它回填 `deviceId`，不依赖其它 ID。
 */
function handleSeed(req, res, query) {
  // reset 先清后种：必须在取用户 / 签发 token **之前**，否则 token 指向被清掉的用户
  if (query.reset === 'true') {
    state = createSeed()
  }
  const guardian = state.users.find((u) => u.userId === 'u-2001') || state.users[0]
  const elder = state.users.find((u) => u.role === 'ELDER') || state.users[0]
  const device = state.devices.find((d) => d.deviceType === 'MAIN')
  const stock = state.stocks[0]
  const plan = state.plans[0]
  const planItem = plan ? state.planItems.find((pi) => pi.planId === plan.planId) : null
  const guardianTokens = issueTokens(guardian)
  const elderTokens = issueTokens(elder)
  ok(res, {
    // 内存态下"是否新建"不可考，固定报 false（已存在、跳过写入）—— 与后端幂等语义一致
    created: false,
    guardianUserId: guardian.userId,
    guardianPhone: guardian.phone,
    guardianToken: guardianTokens.token,
    elderUserId: elder.userId,
    elderPhone: elder.phone,
    elderToken: elderTokens.token,
    deviceId: device ? device.deviceId : 'BOXA1001',
    medicineIds: state.medicines.map((m) => m.medicineId),
    stockId: stock ? stock.stockId : 's-1001',
    planId: plan ? plan.planId : 'p-3301',
    planItemId: planItem ? planItem.itemId : 'pi-9001',
  })
}

/**
 * `POST /_debug/mqtt/publish`：服务端模拟设备上报。
 *
 * <p>**Mock 不接真 EMQX**：只做参数校验（deviceId / subTopic 必填、payload 必须是字符串、
 * deviceId 必须是 Mock 里存在的设备 —— 与后端 `DevtoolsMqttSimService` 的 BizAssert 对齐）
 * 并回显收到的 payload，**不落库、不推 WS**，也不校验 payload 里的业务 ID
 * （Mock 种子 ID 与后端 devtools 种子不同，如光照子设备这里是 `-S-LGT01`）。
 * 要在 Mock 上验证"落库 → WS 推送"请连真后端。
 */
function handleMqttPublish(req, res, body) {
  if (!body || !body.deviceId) throw new BizError(40001, 'deviceId 不能为空')
  if (!body.subTopic) throw new BizError(40001, 'subTopic 不能为空')
  if (body.payload !== undefined && body.payload !== null && typeof body.payload !== 'string') {
    // 后端 DTO 把 payload 声明为 String，传对象会被 Jackson 拒掉 —— Mock 提前暴露这个错
    throw new BizError(40001, 'payload 必须是 JSON 字符串')
  }
  const device = state.devices.find((d) => d.deviceId === body.deviceId)
  if (!device) throw new BizError(40001, `设备不存在: ${body.deviceId}`)
  console.log(
    `[mock] 模拟上报 -> medbox/${body.productKey || device.productKey}/${body.deviceId}/up/${body.subTopic}`,
  )
  ok(res, null)
}

// ---------------------------------------------------------------- 路由

const DEMO_ACCOUNTS = [
  { account: '13800001234', password: DEMO_PASSWORD, role: 'GUARDIAN', name: '张伟', note: '监护 2 位老人（ACTIVE），可读写' },
  { account: '13800001235', password: DEMO_PASSWORD, role: 'GUARDIAN', name: '王芳', note: '对 u-1001 关系 PENDING（验证 40302）' },
  { account: '13800001236', password: DEMO_PASSWORD, role: 'GUARDIAN', name: '周杰', note: '关系 REVOKED，看不到任何数据' },
  { account: '13900000001', password: DEMO_PASSWORD, role: 'ELDER', name: '张桂英', note: '只读本人数据（写操作 40301）' },
  { account: '13900000002', password: DEMO_PASSWORD, role: 'ELDER', name: '李德海', note: '只读本人数据' },
]

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`)
  const path = url.pathname
  const query = Object.fromEntries(url.searchParams.entries())
  const route = `${req.method} ${path}`

  try {
    if (req.method === 'OPTIONS') {
      send(res, 204, null)
      return
    }
    if (!path.startsWith(API_PREFIX)) {
      throw new BizError(40401, '接口不存在')
    }
    const sub = path.slice(API_PREFIX.length)

    // 免鉴权：凭证接口
    if (req.method === 'POST' && sub === '/auth/login') return await handleLogin(req, res, await readBody(req))
    if (req.method === 'POST' && sub === '/auth/register') return await handleRegister(req, res, await readBody(req))
    if (req.method === 'POST' && sub === '/auth/refresh') return await handleRefresh(req, res, await readBody(req))

    // 鉴权：用户
    if (req.method === 'GET' && sub === '/users/me') return await handleMe(req, res)
    if (req.method === 'POST' && sub === '/users/me/delete') return await handleDeleteAccount(req, res, await readBody(req))
    // 注意用局部变量：下方设备段才声明 `let matched`（TDZ），这里不能提前用它
    const profileMatch = /^\/users\/([^/]+)\/profile$/.exec(sub)
    if (req.method === 'GET' && profileMatch) {
      return await handleGetUserProfile(req, res, decodeURIComponent(profileMatch[1]))
    }

    // 鉴权：设备
    if (req.method === 'GET' && sub === '/devices') return await handleListDevices(req, res)
    let matched = /^\/devices\/([^/]+)$/.exec(sub)
    if (req.method === 'GET' && matched) return await handleGetDevice(req, res, decodeURIComponent(matched[1]))

    // 鉴权：设备事件流水 / 命令下发（02 第 4 章；feat/device-command / device-fault 后端未落地）
    matched = /^\/devices\/([^/]+)\/events$/.exec(sub)
    if (req.method === 'GET' && matched) return await handleListEvents(req, res, decodeURIComponent(matched[1]), query)
    matched = /^\/devices\/([^/]+)\/commands$/.exec(sub)
    if (req.method === 'POST' && matched) return await handleCommand(req, res, decodeURIComponent(matched[1]), await readBody(req))

    // 鉴权：药品
    if (req.method === 'GET' && sub === '/medicines') return await handleListMedicines(req, res, query)
    if (req.method === 'POST' && sub === '/medicines') return await handleSaveMedicine(req, res, await readBody(req))
    matched = /^\/medicines\/([^/]+)$/.exec(sub)
    if (req.method === 'GET' && matched) return await handleGetMedicine(req, res, decodeURIComponent(matched[1]))
    if (req.method === 'DELETE' && matched) return await handleDeleteMedicine(req, res, decodeURIComponent(matched[1]))

    // 鉴权：库存四条
    matched = /^\/devices\/([^/]+)\/stocks$/.exec(sub)
    if (req.method === 'GET' && matched) return await handleListStocks(req, res, decodeURIComponent(matched[1]))
    matched = /^\/devices\/([^/]+)\/stocks\/([^/]+)\/in$/.exec(sub)
    if (req.method === 'POST' && matched) {
      return await handleRestockStock(req, res, decodeURIComponent(matched[1]), decodeURIComponent(matched[2]), await readBody(req))
    }
    matched = /^\/devices\/([^/]+)\/stocks\/([^/]+)$/.exec(sub)
    if (req.method === 'PUT' && matched) {
      return await handleSaveStock(req, res, decodeURIComponent(matched[1]), decodeURIComponent(matched[2]), await readBody(req))
    }
    if (req.method === 'DELETE' && matched) {
      return await handleDeleteStock(req, res, decodeURIComponent(matched[1]), decodeURIComponent(matched[2]))
    }

    // 鉴权：计划（02 第 6 章）
    if (req.method === 'GET' && sub === '/plans') return await handleListPlans(req, res, query)
    if (req.method === 'POST' && sub === '/plans') return await handleCreatePlan(req, res, await readBody(req))
    matched = /^\/plans\/([^/]+)\/next-doses$/.exec(sub)
    if (req.method === 'GET' && matched) {
      return await handleGetNextDoses(req, res, decodeURIComponent(matched[1]), query)
    }
    matched = /^\/plans\/([^/]+)\/status$/.exec(sub)
    if (req.method === 'PATCH' && matched) {
      return await handleUpdatePlanStatus(req, res, decodeURIComponent(matched[1]), await readBody(req))
    }
    matched = /^\/plans\/([^/]+)$/.exec(sub)
    if (req.method === 'GET' && matched) return await handleGetPlan(req, res, decodeURIComponent(matched[1]))
    if (req.method === 'PUT' && matched) return await handleUpdatePlan(req, res, decodeURIComponent(matched[1]), await readBody(req))
    if (req.method === 'DELETE' && matched) return await handleDeletePlan(req, res, decodeURIComponent(matched[1]))

    // 鉴权：记录（02 第 7 章；capture-core 属 P1，缩略图由前端留空占位）
    if (req.method === 'GET' && sub === '/records') return await handleListRecords(req, res, query)
    matched = /^\/records\/([^/]+)\/confirm$/.exec(sub)
    if (req.method === 'POST' && matched) {
      return await handleConfirmRecord(req, res, decodeURIComponent(matched[1]), await readBody(req))
    }
    matched = /^\/records\/([^/]+)$/.exec(sub)
    if (req.method === 'GET' && matched) return await handleGetRecord(req, res, decodeURIComponent(matched[1]))

    // 鉴权：抓拍（02 设备管理.md §4.1；capture-core 已落地，feat/mp-capture 联调）
    if (req.method === 'GET' && sub === '/captures') return await handleListCaptures(req, res, query)
    matched = /^\/captures\/([^/]+)\/file$/.exec(sub)
    if (req.method === 'GET' && matched) {
      return await handleGetCaptureFile(req, res, decodeURIComponent(matched[1]), query)
    }
    matched = /^\/captures\/([^/]+)$/.exec(sub)
    if (req.method === 'GET' && matched) return await handleGetCapture(req, res, decodeURIComponent(matched[1]))
    if (req.method === 'DELETE' && matched) return await handleDeleteCapture(req, res, decodeURIComponent(matched[1]))

    // 鉴权：告警（02 第 8 章；feat/alarm-core 已落地，Mock 先行）
    if (req.method === 'GET' && sub === '/alarms') return await handleListAlarms(req, res, query)
    if (req.method === 'GET' && sub === '/alarms/unread-count') return await handleUnreadCount(req, res, query)
    matched = /^\/alarms\/([^/]+)\/read$/.exec(sub)
    if (req.method === 'PATCH' && matched) return await handleMarkAlarmRead(req, res, decodeURIComponent(matched[1]))
    matched = /^\/alarms\/([^/]+)\/handle$/.exec(sub)
    if (req.method === 'PATCH' && matched) return await handleHandleAlarm(req, res, decodeURIComponent(matched[1]))
    if (req.method === 'GET' && sub === '/alarm-settings') return await handleGetAlarmSettings(req, res, query)
    matched = /^\/alarm-settings$/.exec(sub)
    if (req.method === 'PUT' && matched) return await handleSaveAlarmSettings(req, res, query, await readBody(req))

    // 鉴权：监护关系四条（02 的 3.2）
    matched = /^\/users\/([^/]+)\/guardians$/.exec(sub)
    if (req.method === 'POST' && matched) {
      return await handleApplyGuardian(req, res, decodeURIComponent(matched[1]))
    }
    if (req.method === 'GET' && sub === '/users/guardians/pending') return await handlePendingGuardians(req, res)
    matched = /^\/users\/guardians\/([^/]+)\/accept$/.exec(sub)
    if (req.method === 'PATCH' && matched) {
      return await handleAcceptGuardian(req, res, decodeURIComponent(matched[1]), await readBody(req))
    }
    matched = /^\/users\/guardians\/([^/]+)$/.exec(sub)
    if (req.method === 'DELETE' && matched) return await handleRevokeGuardian(req, res, decodeURIComponent(matched[1]))

    // 测试辅助
    if (req.method === 'POST' && sub === '/__mock__/reset') return handleReset(req, res)

    // 联调辅助（02 的 1.5）：免登录，与真后端 WebMvcConfig 的排除口径一致
    if (req.method === 'POST' && sub === '/_debug/seed') return handleSeed(req, res, query)
    if (req.method === 'POST' && sub === '/_debug/mqtt/publish') {
      return handleMqttPublish(req, res, await readBody(req))
    }

    // 未知路径 / 方法用错：统一 40401 + HTTP 404（02 的 1.2 补充口径）
    throw new BizError(40401, '接口不存在')
  } catch (err) {
    fail(res, err)
    console.log(`[mock] ${route} -> code=${err instanceof BizError ? err.code : 500}`)
  }
})

// ---------------------------------------------------------------- WebSocket（04 的最小实现）

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'
const PONG_FRAME = JSON.stringify({ type: 'pong' })

/** 服务端帧编码（不掩码）：文本 / 控制帧通用。 */
function wsEncodeFrame(opcode, payload) {
  const data = Buffer.from(payload || '')
  if (data.length < 126) {
    return Buffer.concat([Buffer.from([0x80 | opcode, data.length]), data])
  }
  if (data.length < 65536) {
    const header = Buffer.alloc(4)
    header[0] = 0x80 | opcode
    header[1] = 126
    header.writeUInt16BE(data.length, 2)
    return Buffer.concat([header, data])
  }
  const header = Buffer.alloc(10)
  header[0] = 0x80 | opcode
  header[1] = 127
  header.writeBigUInt64BE(BigInt(data.length), 2)
  return Buffer.concat([header, data])
}

/** 客户端帧解析（带掩码、可处理粘包 / 半包）：返回完整帧数组，残余留在闭包。 */
function createFrameParser() {
  let buffer = Buffer.alloc(0)
  return (chunk) => {
    buffer = Buffer.concat([buffer, chunk])
    const frames = []
    while (buffer.length >= 2) {
      const opcode = buffer[0] & 0x0f
      const masked = (buffer[1] & 0x80) !== 0
      let length = buffer[1] & 0x7f
      let offset = 2
      if (length === 126) {
        if (buffer.length < 4) break
        length = buffer.readUInt16BE(2)
        offset = 4
      } else if (length === 127) {
        if (buffer.length < 10) break
        length = Number(buffer.readBigUInt64BE(2))
        offset = 10
      }
      let maskKey = null
      if (masked) {
        if (buffer.length < offset + 4) break
        maskKey = buffer.subarray(offset, offset + 4)
        offset += 4
      }
      if (buffer.length < offset + length) break
      const payload = Buffer.from(buffer.subarray(offset, offset + length))
      if (maskKey) {
        for (let i = 0; i < payload.length; i += 1) payload[i] ^= maskKey[i % 4]
      }
      frames.push({ opcode, payload })
      buffer = buffer.subarray(offset + length)
    }
    return frames
  }
}

/**
 * 最小 WebSocket（04 第 1 章）：完成握手 + ping/pong 心跳，**不推业务帧**
 * （让首页 WS 状态条变绿即够用；ALARM / DEVICE_STATUS / REMINDER 推送留待真后端联调）。
 */
server.on('upgrade', (req, socket) => {
  let pathname = ''
  try {
    pathname = new URL(req.url, `http://${req.headers.host || 'localhost'}`).pathname
  } catch (err) {
    pathname = ''
  }
  const key = req.headers['sec-websocket-key']
  if (pathname !== '/medbox/ws' || !key) {
    socket.destroy()
    return
  }
  const accept = crypto.createHash('sha1').update(key + WS_GUID).digest('base64')
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
      'Upgrade: websocket\r\n' +
      'Connection: Upgrade\r\n' +
      `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
  )
  const parse = createFrameParser()
  socket.on('data', (chunk) => {
    parse(chunk).forEach((frame) => {
      if (frame.opcode === 0x8) {
        socket.end()
        return
      }
      if (frame.opcode === 0x9) {
        socket.write(wsEncodeFrame(0xa, frame.payload))
        return
      }
      if (frame.opcode === 0x1) {
        let type = ''
        try {
          type = JSON.parse(frame.payload.toString('utf8')).type || ''
        } catch (err) {
          type = ''
        }
        if (type === 'ping') socket.write(wsEncodeFrame(0x1, PONG_FRAME))
      }
    })
  })
  socket.on('error', () => socket.destroy())
})

// 端口占用要**说人话**：默认 8080 与 medbox-server 同端口，最容易遇到"真后端还开着"
server.on('error', (err) => {
  if (err && err.code === 'EADDRINUSE') {
    console.error(`[mock] 端口 ${PORT} 已被占用（多半是 medbox-server 还在跑）`)
    console.error('[mock] 停掉占用方即可。别改端口 —— 小程序 BASE_URL 的端口固定 8080，'
      + '换端口后小程序连不上（端到端测试的 18099 是自起自连，不走小程序配置）')
    process.exit(1)
  }
  throw err
})

server.listen(PORT, () => {
  console.log(`[mock] medbox 临时 Mock 后端已启动: http://127.0.0.1:${PORT}${API_PREFIX}`)
  console.log('[mock] 小程序把 .env.local 的 VITE_SERVER_HOST 指向本机 IP 即可，无需改代码')
  console.log('[mock] 演示账号（密码统一 %s）：', DEMO_PASSWORD)
  DEMO_ACCOUNTS.forEach((a) => console.log(`         ${a.account}  ${a.role}  ${a.name}  ${a.note}`))
  console.log('[mock] 重置数据: curl -X POST http://127.0.0.1:%d%s/__mock__/reset', PORT, API_PREFIX)
  console.log('[mock] 模拟 token 过期: MOCK_ACCESS_TTL_MS=15000 npm run mock（验证 40101 自动续期）')
  console.log('[mock] WebSocket: /medbox/ws 最小实现（握手 + ping/pong，不推业务帧）')
})
