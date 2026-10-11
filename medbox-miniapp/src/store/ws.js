/**
 * WebSocket 连接与分发单例（`feat/mp-ws`，对应 medbox-spec/08 地基、协议见 04）。
 *
 * <p>**分层**：`store/token.js`（凭据）← 本文件（连接 / 心跳 / 重连 / 分发）→ 页面。
 * 页面**只调本文件的函数**，不要自己 `uni.connectSocket` —— 小程序同时最多 5 条连接，
 * 各页面各建一条会互相挤掉，且 `lastEventId` 补推是**按连接**的，多连接必然丢事件。
 *
 * <p>**三条核心行为（04 第 1 章）**：
 * ① 每 **30s** 发 `{"type":"ping"}`，服务端 90s 无帧即关连接；
 * ② 断线**指数退避**重连（1s→2s→…→30s 上限），重连把 **`lastEventId` 放在握手 query** 上，
 * 服务端建连即补推增量；③ 补推窗口过期时服务端回 `RESYNC`，客户端改走 REST 拉全量。
 *
 * <p>**订阅主题由调用方决定**：后端尚未暴露"我监护的老人列表"接口（属 P1
 * `feat/guardian-relation`），因此本层不猜订阅范围 —— 老人端默认订阅自己
 * （`store/user.js` 的 `getUserId()`），监护人端由页面传入当前关注的 `elderId`。
 * 内部按 `elder:{id}` 逗号分隔拼装，天然支持将来的多老人扩展。
 *
 * ```js
 * import { connect, disconnect, onEvent, WS_EVENT, WS_STATE } from '@/store/ws.js'
 *
 * connect({ elderIds: ['e-1001'] })             // 老人端可省略，默认自己
 * const off = onEvent(WS_EVENT.ALARM, (frame) => { /* 弹窗 / 刷新列表 *\/ })
 * // 页面卸载：off()
 * ```
 */

import { WS_URL } from '@/config/index.js'
import { refreshAuth } from '@/store/auth.js'
import { getAccessToken, hasToken } from '@/store/token.js'
import { ROLE, getRole, getUserId } from '@/store/user.js'
import { WS_EVENT, isBusinessFrame, isResyncFrame, parseFrame } from '@/utils/ws-frame.js'

export { WS_EVENT } from '@/utils/ws-frame.js'

/**
 * 连接状态（页面展示与重连判断共用）。
 *
 * <p>`IDLE` 未起连（初始）/`CONNECTING` 连接中/`OPEN` 已连接/`RECONNECTING` 退避重连中/
 * `CLOSED` 已停止（`disconnect()` 或凭据失效）。
 */
export const WS_STATE = {
  IDLE: 'IDLE',
  CONNECTING: 'CONNECTING',
  OPEN: 'OPEN',
  RECONNECTING: 'RECONNECTING',
  CLOSED: 'CLOSED',
}

/** 心跳间隔（毫秒）：04 第 1 章要求客户端每 30s 发一次 ping。 */
const HEARTBEAT_INTERVAL = 30000

/** 心跳帧（04 第 1 章：服务端只认 `type=ping`，回 PONG）。 */
const PING_FRAME = JSON.stringify({ type: 'ping' })

/** 退避初始值与上限：1s 起、翻倍、30s 封顶（04 第 1 章）。 */
const BASE_DELAY = 1000
const MAX_DELAY = 30000

/**
 * 连续失败多少次后**先续期一次再重连**。
 *
 * <p>只有"曾经连上过"（说明网络与后端都通、握手凭据曾经有效）才会续期：后端没起 /
 * 网段不对时握手也会失败，此时去 refresh 只会被网络错误清掉本地凭据，把用户踢成未登录。
 */
const REFRESH_AFTER_FAILS = 3

/** 当前连接状态。 */
let state = WS_STATE.IDLE

/** 是否维持连接（`disconnect()` 置 false：退出登录 / 手动停止后不再自动重连）。 */
let active = false

/** 订阅主题串（`elder:e-1001,elder:e-1002`）。 */
let desiredTopics = ''

/** SocketTask（`uni.connectSocket` 返回）；全局事件兜底时为 null。 */
let socket = null

/**
 * 世代号：每次建连 / 断开自增，旧连接的回调凭它失效。
 *
 * <p>没有它，页面反复 `onShow` 起连会留下多条心跳定时器与多份"上一个连接"的关闭回调。
 */
let generation = 0

let heartbeatTimer = null
let reconnectTimer = null

/** 已重连次数（决定退避时长）；成功打开后归零。 */
let retryCount = 0

/** 连续失败次数（决定要不要先续期）；成功打开后归零。 */
let failStreak = 0

/** 本轮是否已续期过（每轮只刷一次，refresh 是轮换制，并发会互相作废）。 */
let renewed = false

/** 本轮是否曾成功打开过（区分"断线"与"从没连上"）。 */
let openedOnce = false

/** 最近收到的事件 ID：重连时作为 `lastEventId` 带上，让后端补推断线期间的事件。 */
let lastEventId = ''

/** 最近一帧业务事件（状态条展示用）。 */
let lastEvent = null

/** 按 type 的事件订阅者：`type → Set<handler>`。 */
const eventHandlers = new Map()

/** 状态订阅者（状态条用）。 */
const stateHandlers = new Set()

/**
 * 起连（**幂等**）：已连接且订阅主题不变则直接返回。
 *
 * @param {Object} [options]
 * @param {string[]} [options.elderIds] 要订阅的老人 ID（监护人端传当前关注的老人）
 * @param {string} [options.elderId] 单个老人 ID 的简便写法
 * @returns {boolean} 是否发起 / 维持了连接（未登录或拿不到老人 ID 时返回 false）
 */
export function connect(options = {}) {
  const topics = normalizeTopics(options)
  if (!topics.length) {
    return false
  }
  const next = topics.join(',')
  if (active && next === desiredTopics && state === WS_STATE.OPEN) {
    return true
  }
  if (!hasToken()) {
    // 未登录不起连：App.vue 会在登录成功后补连
    setState(state === WS_STATE.IDLE ? WS_STATE.IDLE : WS_STATE.CLOSED)
    return false
  }
  desiredTopics = next
  active = true
  retryCount = 0
  failStreak = 0
  renewed = false
  openedOnce = false
  openSocket()
  return true
}

/**
 * 断开并停止重连（退出登录 / 切账号 / 手动停止）。
 *
 * <p>断开后 {@link connect} 仍可重新起连（重新登录时调用）。
 */
export function disconnect() {
  active = false
  clearTimers()
  closeSocket()
  generation += 1
  setState(WS_STATE.CLOSED)
}

/**
 * 立即重连一次（状态条"重连"按钮）：重置退避与失败计数，不等退避计时器。
 *
 * @returns {boolean} 是否发起（未登录 / 无订阅主题时返回 false）
 */
export function reconnectNow() {
  if (!desiredTopics) {
    const topics = normalizeTopics({})
    if (!topics.length) {
      return false
    }
    desiredTopics = topics.join(',')
  }
  if (!hasToken()) {
    return false
  }
  active = true
  retryCount = 0
  failStreak = 0
  renewed = false
  openSocket()
  return true
}

/**
 * 订阅某一类事件。
 *
 * @param {string} type {@link WS_EVENT} 之一
 * @param {(frame: {type:string,id:string,topic:string,ts:?number,payload:Object}) => void} handler
 * @returns {Function} 退订函数（页面 `onHide` / `beforeUnmount` 里调用）
 */
export function onEvent(type, handler) {
  if (typeof handler !== 'function' || !type) {
    return () => {}
  }
  let set = eventHandlers.get(type)
  if (!set) {
    set = new Set()
    eventHandlers.set(type, set)
  }
  set.add(handler)
  return () => {
    const current = eventHandlers.get(type)
    if (current) {
      current.delete(handler)
    }
  }
}

/**
 * 订阅连接状态变化。
 * @param {(state: string) => void} handler
 * @returns {Function} 退订函数
 */
export function onStateChange(handler) {
  if (typeof handler !== 'function') {
    return () => {}
  }
  stateHandlers.add(handler)
  return () => stateHandlers.delete(handler)
}

/**
 * 订阅 `RESYNC`（补推窗口过期，应改走 REST 拉全量）—— {@link onEvent}('RESYNC') 的同义写法。
 * @param {(frame: Object) => void} handler
 * @returns {Function} 退订函数
 */
export function onResync(handler) {
  return onEvent(WS_EVENT.RESYNC, handler)
}

/** @returns {string} 当前状态（{@link WS_STATE} 之一） */
export function getState() {
  return state
}

/** @returns {boolean} 是否已连接 */
export function isConnected() {
  return state === WS_STATE.OPEN
}

/**
 * 最近一帧业务事件（状态条 / 角标初始化用）。
 * @returns {?{type:string,id:string,topic:string,ts:?number,payload:Object}}
 */
export function getLastEvent() {
  return lastEvent
}

/** @returns {string} 当前用于补推的事件 ID（排障用） */
export function getLastEventId() {
  return lastEventId
}

// ---------------------------------------------------------------------------
// 内部实现
// ---------------------------------------------------------------------------

/**
 * 归一化订阅主题：`elderIds` / `elderId` → `elder:{id}` 列表。
 *
 * <p>**都为空时只有老人端能兜底**（默认订阅自己）；监护人端返回空数组 —— 他的可见老人是
 * **一批**而不是自己，而"我监护的老人列表"接口属 P1 `feat/guardian-relation`，本期拿不到，
 * 拿自己的 ID 去订阅只会被握手鉴权判越权（40301）然后空转重连。
 *
 * @returns {string[]}
 */
function normalizeTopics(options) {
  const raw = Array.isArray(options && options.elderIds)
    ? options.elderIds
    : options && options.elderId
      ? [options.elderId]
      : []
  const topics = []
  raw.forEach((id) => {
    if (typeof id !== 'string') {
      return
    }
    const elderId = id.trim()
    if (!elderId) {
      return
    }
    const topic = `elder:${elderId}`
    if (topics.indexOf(topic) === -1) {
      topics.push(topic)
    }
  })
  if (topics.length === 0 && getRole() === ROLE.ELDER) {
    const me = getUserId()
    if (me) {
      topics.push(`elder:${me}`)
    }
  }
  return topics
}

/** 建连：拼 URL → 起 SocketTask → 绑回调。 */
function openSocket() {
  if (!active || !desiredTopics) {
    return
  }
  if (!hasToken()) {
    stop()
    return
  }
  const gen = (generation += 1)
  clearTimers()
  closeSocket()
  setState(retryCount > 0 ? WS_STATE.RECONNECTING : WS_STATE.CONNECTING)
  bindSocket(buildUrl(), gen)
}

/** 拼握手 URL：`{WS_URL}?token=&topics=` + 重连时的 `&lastEventId=`。 */
function buildUrl() {
  const params = [
    `token=${encodeURIComponent(getAccessToken())}`,
    `topics=${encodeURIComponent(desiredTopics)}`,
  ]
  if (lastEventId) {
    params.push(`lastEventId=${encodeURIComponent(lastEventId)}`)
  }
  return `${WS_URL}?${params.join('&')}`
}

/**
 * 起连接并绑定回调；**优先 SocketTask**（`uni.connectSocket` 在微信小程序 / App / H5
 * 都返回 SocketTask），个别平台返回空时回退到全局事件（同一时刻只有一条连接，与单例模型一致）。
 *
 * @param {string} url
 * @param {number} gen 本次建连的世代号
 * @returns {?Object} SocketTask（回退时为 null）
 */
function bindSocket(url, gen) {
  if (typeof uni === 'undefined' || typeof uni.connectSocket !== 'function') {
    console.warn('[ws] 当前环境不支持 uni.connectSocket，实时推送不可用')
    handleDisconnect(gen, '环境不支持 WebSocket')
    return null
  }
  const task =
    uni.connectSocket({
      url,
      fail: (err) => handleFailure(gen, err && err.errMsg),
    }) || null

  if (task && typeof task.onOpen === 'function') {
    task.onOpen(() => handleOpen(gen))
    task.onMessage((res) => handleMessage(gen, res && res.data))
    task.onClose(() => handleFailure(gen, '连接已关闭'))
    task.onError((err) => handleFailure(gen, err && err.errMsg))
    socket = task
    return task
  }
  bindGlobalSocket()
  return null
}

/** 全局事件是否已绑过（避免每次重连重复注册）。 */
let globalBound = false

/** 全局事件兜底：只在拿不到 SocketTask 时绑一次，回调里取当前世代号。 */
function bindGlobalSocket() {
  if (globalBound || typeof uni.onSocketOpen !== 'function') {
    return
  }
  globalBound = true
  uni.onSocketOpen(() => handleOpen(generation))
  uni.onSocketMessage((res) => handleMessage(generation, res && res.data))
  uni.onSocketClose(() => handleFailure(generation, '连接已关闭'))
  uni.onSocketError((err) => handleFailure(generation, err && err.errMsg))
}

/** 连接成功打开：重置退避 → 起心跳。 */
function handleOpen(gen) {
  if (gen !== generation) {
    return
  }
  openedOnce = true
  retryCount = 0
  failStreak = 0
  renewed = false
  setState(WS_STATE.OPEN)
  startHeartbeat(gen)
}

/**
 * 收到一帧。
 *
 * <p>`PONG` 只刷新活跃时间、**不推进 `lastEventId`**（它是控制帧，不进后端补推窗口）；
 * 业务帧才推进；`RESYNC` 表示窗口已过期，把服务端给的 `latestEventId` 记下来
 * （后续重连从它之后补，中间那段由页面走 REST 全量补齐）。
 */
function handleMessage(gen, raw) {
  if (gen !== generation) {
    return
  }
  const frame = parseFrame(raw)
  if (!frame) {
    return
  }
  if (frame.type === WS_EVENT.PONG) {
    emit(frame)
    return
  }
  if (isResyncFrame(frame)) {
    // 只打 type / id / topic，不打 payload（与 request.js 同一条日志纪律）
    console.warn(`[ws] 补推窗口已过期 type=RESYNC topic=${frame.topic} id=${frame.id}`)
    if (frame.payload.latestEventId) {
      lastEventId = frame.payload.latestEventId
    }
    lastEvent = frame
    emit(frame)
    return
  }
  if (isBusinessFrame(frame) && frame.id) {
    lastEventId = frame.id
  }
  lastEvent = frame
  emit(frame)
}

/** 连接失败 / 关闭：清理后安排重连（世代号自增，旧回调自然失效）。 */
function handleFailure(gen, reason) {
  handleDisconnect(gen, reason)
}

function handleDisconnect(gen, reason) {
  if (gen !== generation) {
    return
  }
  generation += 1
  clearHeartbeat()
  socket = null
  if (reason) {
    console.warn(`[ws] 连接断开：${reason}`)
  }
  scheduleReconnect()
}

/**
 * 安排重连：**指数退避**；连续失败且曾经连上过 → 先续期一次再重连，续期失败则停止。
 *
 * <p>停止重连后登录态**不在这里清**（清凭据是请求层 / `store/auth.js` 的事），
 * 免得"后端没起"被当成"登录失效"把用户踢下线。
 */
function scheduleReconnect() {
  if (!active) {
    setState(WS_STATE.CLOSED)
    return
  }
  if (!hasToken()) {
    stop()
    return
  }
  failStreak += 1
  if (!renewed && openedOnce && failStreak >= REFRESH_AFTER_FAILS) {
    renewed = true
    Promise.resolve()
      .then(() => refreshAuth())
      .then((ok) => {
        if (!active) {
          return
        }
        if (!ok) {
          stop()
          return
        }
        retryCount = 0
        failStreak = 0
        openSocket()
      })
      .catch(() => stop())
    return
  }
  const delay = Math.min(BASE_DELAY * Math.pow(2, retryCount), MAX_DELAY)
  retryCount += 1
  setState(WS_STATE.RECONNECTING)
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    openSocket()
  }, delay)
}

/** 停止重连（凭据失效 / 手动停止）：保留 `active=false`，等下次 {@link connect}。 */
function stop() {
  clearTimers()
  closeSocket()
  generation += 1
  setState(WS_STATE.CLOSED)
  active = false
}

/** 起心跳：发帧前检查世代号与登录态（退登录后凭据被清，这里兜底断开）。 */
function startHeartbeat(gen) {
  clearHeartbeat()
  heartbeatTimer = setInterval(() => {
    if (gen !== generation) {
      return
    }
    if (!hasToken()) {
      disconnect()
      return
    }
    sendPing()
  }, HEARTBEAT_INTERVAL)
}

function sendPing() {
  try {
    if (socket && typeof socket.send === 'function') {
      socket.send({ data: PING_FRAME })
      return
    }
    if (typeof uni !== 'undefined' && typeof uni.sendSocketMessage === 'function') {
      uni.sendSocketMessage({ data: PING_FRAME })
    }
  } catch (err) {
    console.warn('[ws] 心跳发送失败', err)
  }
}

function closeSocket() {
  try {
    if (socket && typeof socket.close === 'function') {
      socket.close()
    } else if (typeof uni !== 'undefined' && typeof uni.closeSocket === 'function') {
      uni.closeSocket()
    }
  } catch (err) {
    console.warn('[ws] 关闭连接失败', err)
  }
  socket = null
}

function clearHeartbeat() {
  if (heartbeatTimer !== null) {
    clearInterval(heartbeatTimer)
    heartbeatTimer = null
  }
}

function clearTimers() {
  clearHeartbeat()
  if (reconnectTimer !== null) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
}

function setState(next) {
  if (state === next) {
    return
  }
  state = next
  stateHandlers.forEach((handler) => {
    try {
      handler(state)
    } catch (err) {
      console.warn('[ws] 状态订阅者抛异常', err)
    }
  })
}

function emit(frame) {
  const set = eventHandlers.get(frame.type)
  if (!set) {
    return
  }
  Array.from(set).forEach((handler) => {
    try {
      handler(frame)
    } catch (err) {
      console.warn(`[ws] 事件订阅者抛异常 type=${frame.type}`, err)
    }
  })
}
