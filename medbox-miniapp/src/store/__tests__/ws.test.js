import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { WS_EVENT } from '@/utils/ws-frame.js'

/** 与 `src/store/ws.js` 的 `WS_STATE` 同值（本文件只能动态导入 ws 模块，故这里留一份副本对照）。 */
const STATE = {
  IDLE: 'IDLE',
  CONNECTING: 'CONNECTING',
  OPEN: 'OPEN',
  RECONNECTING: 'RECONNECTING',
  CLOSED: 'CLOSED',
}

// ws.js 续期只依赖 auth.js 的 refreshAuth（编排层），这里替换掉真实 HTTP 续期
vi.mock('@/store/auth.js', () => ({
  refreshAuth: vi.fn(async () => true),
}))

/** 本地缓存：模拟 uni.getStorageSync / setStorageSync 背后的存储。 */
const storage = {}

/**
 * 造一个假 SocketTask：注册回调由 ws.js 调，测试用 open/message/close/error 驱动。
 *
 * <p>**`readyState` 必须跟着生命周期走**（CONNECTING→OPEN→CLOSED）：真实 SocketTask 有这个
 * 属性，而 ws.js 现在靠它判断"这条连接还活着吗"。桩要是永远停在某个值，守卫逻辑就测不出来。
 */
function createTask(url, fail) {
  const handlers = {}
  const task = {
    url,
    sent: [],
    closed: false,
    readyState: 0, // CONNECTING
    onOpen: (fn) => {
      handlers.open = fn
    },
    onMessage: (fn) => {
      handlers.message = fn
    },
    onClose: (fn) => {
      handlers.close = fn
    },
    onError: (fn) => {
      handlers.error = fn
    },
    send: (msg) => task.sent.push(msg && msg.data),
    close: (opt) => {
      task.closed = true
      task.readyState = 3 // CLOSED
      // 底层拒绝时（如连接早断了）会回调 fail —— 真实平台行为，桩里也要能触发
      if (opt && typeof opt.fail === 'function' && task.closeShouldFail) {
        opt.fail({ errMsg: 'closeSocket:fail WebSocket is not connected' })
      }
    },
    // ---- 测试驱动 ----
    open: () => {
      task.readyState = 1 // OPEN
      handlers.open && handlers.open()
    },
    message: (data) => handlers.message && handlers.message({ data }),
    closeByServer: () => {
      task.readyState = 3
      handlers.close && handlers.close()
    },
    failHandshake: (msg) => {
      task.readyState = 3
      fail && fail({ errMsg: msg || 'handshake failed' })
    },
    errorByServer: (msg) => {
      task.readyState = 3
      handlers.error && handlers.error({ errMsg: msg || 'socket error' })
    },
    shut: () => {
      // 仅把底层连接置死，**不回调**（模拟被系统回收但回调还没到）
      task.readyState = 3
    },
  }
  return task
}

let sockets = []

beforeEach(() => {
  vi.useFakeTimers()
  sockets = []
  vi.stubGlobal('uni', {
    getStorageSync: (key) => (key in storage ? storage[key] : ''),
    setStorageSync: (key, value) => {
      storage[key] = value
    },
    removeStorageSync: (key) => {
      delete storage[key]
    },
    connectSocket: ({ url, fail }) => {
      const task = createTask(url, fail)
      sockets.push(task)
      return task
    },
    sendSocketMessage: vi.fn(),
    closeSocket: vi.fn(),
    showToast: vi.fn(),
    reLaunch: vi.fn(),
    addInterceptor: vi.fn(),
  })
})

afterEach(() => {
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  Object.keys(storage).forEach((key) => delete storage[key])
})

/** 每次都拿一份全新的 ws 模块（单例状态不跨用例污染）。 */
async function loadWs({ token = 'TOKEN', user = { userId: 'u-1001', role: 'ELDER' } } = {}) {
  if (token) {
    storage.accessToken = token
  }
  if (user) {
    storage.userInfo = user
  }
  vi.resetModules()
  const ws = await import('@/store/ws.js')
  const auth = await import('@/store/auth.js')
  // vitest 的 vi.mock 模块不随 resetModules 重建，spy 的调用记录会跨用例累积，这里手动清
  auth.refreshAuth.mockClear()
  auth.refreshAuth.mockResolvedValue(true)
  return { ws, refreshAuth: auth.refreshAuth }
}

const frameOf = (over = {}) =>
  JSON.stringify({
    type: 'ALARM',
    id: 'evt-9a1c',
    topic: 'elder:u-1001',
    ts: 1759474520000,
    payload: { alarmId: 'a-5001', level: 'WARN', message: '08:00 计划服药未按时服用' },
    ...over,
  })

describe('connect：拼装握手 URL', () => {
  it('老人端默认订阅自己：token + topics=elder:{userId}，首次不带 lastEventId', async () => {
    const { ws } = await loadWs()
    expect(ws.connect()).toBe(true)
    expect(sockets).toHaveLength(1)
    // 主机来自 .env（各人不同），这里只断言协议与路径 / 参数
    expect(sockets[0].url.startsWith('ws://')).toBe(true)
    expect(sockets[0].url.endsWith('/medbox/ws?token=TOKEN&topics=elder%3Au-1001')).toBe(true)
    expect(sockets[0].url).not.toContain('lastEventId')
  })

  it('监护人未指定老人时不起连（可见老人列表接口属 P1，不能拿自己 ID 去越权订阅）', async () => {
    const { ws } = await loadWs({ user: { userId: 'u-2002', role: 'GUARDIAN' } })
    expect(ws.connect()).toBe(false)
    expect(sockets).toHaveLength(0)
  })

  it('监护人传入当前关注老人：多主题逗号分隔', async () => {
    const { ws } = await loadWs({ user: { userId: 'u-2002', role: 'GUARDIAN' } })
    expect(ws.connect({ elderIds: ['e-1001', 'e-1002', 'e-1001'] })).toBe(true)
    expect(decodeURIComponent(sockets[0].url)).toContain('topics=elder:e-1001,elder:e-1002')
  })

  it('未登录（无 token）不起连', async () => {
    const { ws } = await loadWs({ token: '' })
    storage.accessToken = ''
    expect(ws.connect()).toBe(false)
    expect(sockets).toHaveLength(0)
  })

  it('幂等：已连接且主题不变不重复建连', async () => {
    const { ws } = await loadWs()
    ws.connect()
    sockets[0].open()
    expect(ws.getState()).toBe(STATE.OPEN)
    ws.connect()
    ws.connect({ elderIds: ['u-1001'] })
    expect(sockets).toHaveLength(1)
  })
})

describe('心跳', () => {
  it('连接打开后每 30s 发一次 ping', async () => {
    const { ws } = await loadWs()
    ws.connect()
    sockets[0].open()

    expect(sockets[0].sent).toEqual([])
    vi.advanceTimersByTime(29999)
    expect(sockets[0].sent).toEqual([])
    vi.advanceTimersByTime(1)
    expect(sockets[0].sent).toEqual(['{"type":"ping"}'])
    vi.advanceTimersByTime(30000)
    expect(sockets[0].sent).toHaveLength(2)
  })

  it('连接已不是 OPEN 时不再发 ping（否则报 sendSocketMessage:fail）', async () => {
    const { ws } = await loadWs()
    ws.connect()
    sockets[0].open()
    vi.advanceTimersByTime(30000)
    expect(sockets[0].sent).toHaveLength(1)
    sockets[0].shut() // 底层断了、回调未到：心跳此刻必须哑火
    vi.advanceTimersByTime(30000)
    expect(sockets[0].sent).toHaveLength(1)
  })

  it('断开后不再发心跳（定时器已清理）', async () => {
    const { ws } = await loadWs()
    ws.connect()
    sockets[0].open()
    sockets[0].closeByServer()
    vi.advanceTimersByTime(60000)
    expect(sockets[0].sent).toEqual([])
  })
})

describe('断线重连', () => {
  it('指数退避：1s → 2s → 4s（未连上时失败）', async () => {
    const { ws } = await loadWs()
    ws.connect()
    // 第 1 次失败
    sockets[0].errorByServer()
    vi.advanceTimersByTime(999)
    expect(sockets).toHaveLength(1)
    vi.advanceTimersByTime(1)
    expect(sockets).toHaveLength(2)
    // 第 2 次失败：等 2s
    sockets[1].errorByServer()
    vi.advanceTimersByTime(1999)
    expect(sockets).toHaveLength(2)
    vi.advanceTimersByTime(1)
    expect(sockets).toHaveLength(3)
    // 第 3 次失败：等 4s
    sockets[2].errorByServer()
    vi.advanceTimersByTime(3999)
    expect(sockets).toHaveLength(3)
    vi.advanceTimersByTime(1)
    expect(sockets).toHaveLength(4)
  })

  it('退避上限 30s：不会无限翻倍', async () => {
    const { ws } = await loadWs()
    ws.connect()
    // 连续失败 5 次 → 下一次退避理论 32s，被压到 30s
    const delays = [1000, 2000, 4000, 8000, 16000]
    for (let i = 0; i < delays.length; i += 1) {
      sockets[sockets.length - 1].errorByServer()
      vi.advanceTimersByTime(delays[i])
    }
    expect(sockets).toHaveLength(6)
    sockets[5].errorByServer()
    vi.advanceTimersByTime(29999)
    expect(sockets).toHaveLength(6)
    vi.advanceTimersByTime(1)
    expect(sockets).toHaveLength(7)
  })

  it('重连带上 lastEventId：让后端补推断线期间的事件', async () => {
    const { ws } = await loadWs()
    ws.connect()
    sockets[0].open()
    sockets[0].message(frameOf({ id: 'evt-9a1c' }))
    sockets[0].closeByServer()
    vi.advanceTimersByTime(1000)
    expect(decodeURIComponent(sockets[1].url)).toContain('lastEventId=evt-9a1c')
  })

  it('PONG 不推进 lastEventId（控制帧不进补推窗口）', async () => {
    const { ws } = await loadWs()
    ws.connect()
    sockets[0].open()
    sockets[0].message(JSON.stringify({ type: 'PONG', id: 'evt-pong', ts: 1759474550000 }))
    expect(ws.getLastEventId()).toBe('')
    sockets[0].closeByServer()
    vi.advanceTimersByTime(1000)
    expect(sockets[1].url).not.toContain('lastEventId')
  })

  it('连接期间 state：OPEN → 断线 RECONNECTING', async () => {
    const { ws } = await loadWs()
    ws.connect()
    expect(ws.getState()).toBe(STATE.CONNECTING)
    sockets[0].open()
    expect(ws.getState()).toBe(STATE.OPEN)
    sockets[0].closeByServer()
    expect(ws.getState()).toBe(STATE.RECONNECTING)
  })

  it('disconnect 后停止重连', async () => {
    const { ws } = await loadWs()
    ws.connect()
    sockets[0].open()
    ws.disconnect()
    expect(ws.getState()).toBe(STATE.CLOSED)
    vi.advanceTimersByTime(120000)
    expect(sockets).toHaveLength(1)
  })

  it('reconnectNow：立即重连并重置退避', async () => {
    const { ws } = await loadWs()
    ws.connect()
    sockets[0].open()
    sockets[0].closeByServer()
    expect(ws.reconnectNow()).toBe(true)
    expect(sockets).toHaveLength(2)
  })
})

describe('关闭连接：不再对着没有连接的状态调关', () => {
  it('握手失败后重连：**不**调用全局 uni.closeSocket（旧实现每次重连刷一条 "' +
    'closeSocket:fail WebSocket is not connected"）', async () => {
    const { ws } = await loadWs()
    ws.connect()
    // 握手失败 → handleDisconnect 已把 socket 置 null；此刻底层根本不存在连接
    sockets[0].failHandshake()
    vi.advanceTimersByTime(1000)
    expect(sockets).toHaveLength(2)
    expect(uni.closeSocket).not.toHaveBeenCalled()
    // 再失败两次，仍然一次都不该调
    sockets[1].failHandshake()
    vi.advanceTimersByTime(2000)
    expect(uni.closeSocket).not.toHaveBeenCalled()
  })

  it('底层已关闭的连接不重复 close（连接可能已被回收，本地状态还没同步）', async () => {
    const { ws } = await loadWs()
    ws.connect()
    sockets[0].open()
    sockets[0].shut() // 只有底层断了，回调没到 —— 本地还以为连着
    ws.disconnect()
    expect(sockets[0].closed).toBe(false)
  })

  it('正常断开仍会真的关闭 socket（别为了消报错把关掉的步骤也丢了）', async () => {
    const { ws } = await loadWs()
    ws.connect()
    sockets[0].open()
    ws.disconnect()
    expect(sockets[0].closed).toBe(true)
  })

  it('底层拒绝关闭时不抛错：fail 回调兜住，不让它变成 MiniProgramError', async () => {
    const { ws } = await loadWs()
    ws.connect()
    sockets[0].open()
    sockets[0].closeShouldFail = true
    expect(() => ws.disconnect()).not.toThrow()
  })

  it('迟到的旧连接会自己关掉：不占小程序仅有的 5 条连接额度', async () => {
    const { ws } = await loadWs()
    ws.connect()
    const stale = sockets[0]
    ws.reconnectNow() // 世代号自增，旧连接迟到
    expect(sockets).toHaveLength(2)
    stale.open() // 迟到的 onOpen
    expect(stale.closed).toBe(true)
    expect(sockets[1].closed).toBe(false)
  })
})

describe('握手失败与续期', () => {
  it('从没连上过：只退避重连，不续期（后端没起不该清掉本地凭据）', async () => {
    const { ws, refreshAuth } = await loadWs()
    ws.connect()
    sockets[0].errorByServer()
    vi.advanceTimersByTime(1000)
    sockets[1].errorByServer()
    vi.advanceTimersByTime(2000)
    sockets[2].errorByServer()
    vi.advanceTimersByTime(4000)
    expect(sockets).toHaveLength(4)
    expect(refreshAuth).not.toHaveBeenCalled()
  })

  it('曾经连上过且连续失败 3 次：先续期一次，成功则立即重连', async () => {
    const { ws, refreshAuth } = await loadWs()
    refreshAuth.mockResolvedValue(true)
    ws.connect()
    sockets[0].open()
    sockets[0].closeByServer()
    vi.advanceTimersByTime(1000) // 失败 1
    sockets[1].closeByServer()
    vi.advanceTimersByTime(2000) // 失败 2
    sockets[2].closeByServer()
    vi.advanceTimersByTime(4000) // 失败 3 → 触发续期
    // 续期走的是 Promise 链，调用发生在微任务里，flush 后再断言
    await vi.advanceTimersByTimeAsync(0)
    expect(refreshAuth).toHaveBeenCalledTimes(1)
    expect(sockets).toHaveLength(4)
  })

  it('续期仍失败：停止重连且不自行清凭据（交给请求层的 40101 流程）', async () => {
    const { ws, refreshAuth } = await loadWs()
    refreshAuth.mockResolvedValue(false)
    ws.connect()
    sockets[0].open()
    sockets[0].closeByServer()
    vi.advanceTimersByTime(1000)
    sockets[1].closeByServer()
    vi.advanceTimersByTime(2000)
    sockets[2].closeByServer()
    vi.advanceTimersByTime(4000)
    await vi.advanceTimersByTimeAsync(0)
    expect(refreshAuth).toHaveBeenCalledTimes(1)
    expect(ws.getState()).toBe(STATE.CLOSED)
    vi.advanceTimersByTime(120000)
    expect(sockets).toHaveLength(3)
  })
})

describe('事件分发', () => {
  it('按 type 分发给订阅者，退订后不再收到', async () => {
    const { ws } = await loadWs()
    ws.connect()
    sockets[0].open()

    const alarmFrames = []
    const off = ws.onEvent(WS_EVENT.ALARM, (frame) => alarmFrames.push(frame))
    sockets[0].message(frameOf({ id: 'evt-1' }))
    expect(alarmFrames).toHaveLength(1)
    expect(alarmFrames[0].payload.message).toBe('08:00 计划服药未按时服用')

    off()
    sockets[0].message(frameOf({ id: 'evt-2' }))
    expect(alarmFrames).toHaveLength(1)
  })

  it('不同事件类型互不串台', async () => {
    const { ws } = await loadWs()
    ws.connect()
    sockets[0].open()
    const alarms = []
    const statuses = []
    ws.onEvent(WS_EVENT.ALARM, (f) => alarms.push(f))
    ws.onEvent(WS_EVENT.DEVICE_STATUS, (f) => statuses.push(f))
    sockets[0].message(frameOf({ type: 'ALARM', id: 'evt-1' }))
    sockets[0].message(
      frameOf({ type: 'DEVICE_STATUS', id: 'evt-2', payload: { deviceId: 'd-1', online: false } }),
    )
    expect(alarms).toHaveLength(1)
    expect(statuses).toHaveLength(1)
  })

  it('RESYNC 派发并把服务端给的 latestEventId 记为新起点', async () => {
    const { ws } = await loadWs()
    ws.connect()
    sockets[0].open()
    const resyncs = []
    ws.onResync((frame) => resyncs.push(frame))
    sockets[0].message(
      JSON.stringify({
        type: 'RESYNC',
        id: 'evt-3',
        topic: 'elder:u-1001',
        ts: 1759474600000,
        payload: { reason: 'WINDOW_EXPIRED', latestEventId: 'evt-2f' },
      }),
    )
    expect(resyncs).toHaveLength(1)
    expect(resyncs[0].payload.reason).toBe('WINDOW_EXPIRED')
    expect(ws.getLastEventId()).toBe('evt-2f')
  })

  it('非法帧不分发、不影响连接', async () => {
    const { ws } = await loadWs()
    ws.connect()
    sockets[0].open()
    const seen = []
    ws.onEvent(WS_EVENT.ALARM, (f) => seen.push(f))
    sockets[0].message('{ not json')
    sockets[0].message(JSON.stringify({ id: 'evt-x' }))
    expect(seen).toHaveLength(0)
    expect(ws.getState()).toBe(STATE.OPEN)
  })

  it('订阅者抛异常不影响其它订阅者', async () => {
    const { ws } = await loadWs()
    ws.connect()
    sockets[0].open()
    const ok = []
    ws.onEvent(WS_EVENT.ALARM, () => {
      throw new Error('页面回调挂了')
    })
    ws.onEvent(WS_EVENT.ALARM, (f) => ok.push(f))
    sockets[0].message(frameOf({ id: 'evt-1' }))
    expect(ok).toHaveLength(1)
  })

  it('getLastEvent 返回最近一帧（状态条初始化用）', async () => {
    const { ws } = await loadWs()
    ws.connect()
    sockets[0].open()
    expect(ws.getLastEvent()).toBeNull()
    sockets[0].message(frameOf({ id: 'evt-1' }))
    expect(ws.getLastEvent().id).toBe('evt-1')
  })
})

describe('onStateChange', () => {
  it('状态变化通知订阅者，退订后停止', async () => {
    const { ws } = await loadWs()
    const states = []
    const off = ws.onStateChange((s) => states.push(s))
    ws.connect()
    sockets[0].open()
    sockets[0].closeByServer()
    expect(states).toEqual([STATE.CONNECTING, STATE.OPEN, STATE.RECONNECTING])
    off()
    vi.advanceTimersByTime(1000)
    expect(states).toHaveLength(3)
  })
})
