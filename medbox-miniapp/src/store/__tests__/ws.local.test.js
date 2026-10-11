import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'

/**
 * 真链路联调（`feat/mp-ws`，**默认跳过**）。
 *
 * <p>与后端 `WsLocalIntegrationTest` 同一套路：需要**本机真的起着后端**（含 PostgreSQL / EMQX），
 * CI 与日常 `npm test` 不跑；要跑就显式开：
 *
 * ```bash
 * cd medbox-server && ./gradlew bootRun --args='--medbox.ws.debug-endpoint-enabled=true'
 * cd medbox-miniapp && MEDBOX_WS_LOCAL_TEST=true npm test -- ws.local
 * ```
 *
 * <p>覆盖的是 `src/store/ws.js` 这条**客户端链路**：注册拿真 JWT → 用真 WebSocket 连
 * `/medbox/ws` → 后端调试端点推一帧 → 断言客户端收到并分发。
 *
 * <p>`uni` 用 Node 内置 `WebSocket` 打桩（`uni.connectSocket` 的 SocketTask 形态与之一致），
 * 因此这里跑的是**真实的帧协议**，不是 mock 出来的帧。
 */

const ENABLED = process.env.MEDBOX_WS_LOCAL_TEST === 'true'

/** 本地缓存：模拟 uni.getStorageSync / setStorageSync。 */
const storage = {}

/** 用 Node 内置 WebSocket 打桩 `uni.connectSocket`（SocketTask 形态）。 */
function installUni() {
  const Socket = globalThis.WebSocket
  vi.stubGlobal('uni', {
    getStorageSync: (key) => (key in storage ? storage[key] : ''),
    setStorageSync: (key, value) => {
      storage[key] = value
    },
    removeStorageSync: (key) => {
      delete storage[key]
    },
    connectSocket: ({ url }) => {
      const socket = new Socket(url)
      return {
        onOpen: (fn) => socket.addEventListener('open', fn),
        onMessage: (fn) => socket.addEventListener('message', (event) => fn({ data: event.data })),
        onClose: (fn) => socket.addEventListener('close', fn),
        onError: (fn) => socket.addEventListener('error', fn),
        send: ({ data }) => socket.send(data),
        close: () => socket.close(),
      }
    },
  })
}

/** 注册一个老人账号，拿真 JWT（调试推送端点要鉴权）。 */
async function registerElder() {
  const phone = `139${String(Date.now()).slice(-8)}`
  const res = await fetch('http://127.0.0.1:8080/medbox/api/v1/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone, password: 'test1234', role: 'ELDER', name: '联调老人' }),
  })
  const body = await res.json()
  if (!body || body.code !== 0) {
    throw new Error(`注册失败：${JSON.stringify(body)}`)
  }
  return body.data
}

/** 等一帧业务事件（最多 timeoutMs）。 */
function waitFor(frames, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('等待推送超时')), timeoutMs)
    const check = setInterval(() => {
      if (frames.length > 0) {
        clearTimeout(timer)
        clearInterval(check)
        resolve(frames[0])
      }
    }, 50)
  })
}

describe.skipIf(!ENABLED)('ws 真链路（需本机后端）', () => {
  let ws
  let session

  beforeAll(async () => {
    installUni()
    session = await registerElder()
    storage.accessToken = session.token
    storage.userInfo = { userId: session.userId, role: session.role }
    vi.resetModules()
    ws = await import('@/store/ws.js')
  })

  afterAll(() => {
    if (ws) {
      ws.disconnect()
    }
    vi.unstubAllGlobals()
  })

  it('连上后端 → 调试端点推 ALARM → 客户端收到并分发', async () => {
    const alarms = []
    ws.connect()
    ws.onEvent('ALARM', (frame) => alarms.push(frame))

    // 等握手成功
    await waitFor(
      (() => {
        const holder = []
        const off = ws.onStateChange((state) => {
          if (state === 'OPEN') {
            holder.push(state)
          }
        })
        setTimeout(off, 6000)
        return holder
      })(),
      6000,
    )
    expect(ws.getState()).toBe('OPEN')

    const res = await fetch('http://127.0.0.1:8080/medbox/api/v1/_debug/ws/publish', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${session.token}`,
      },
      body: JSON.stringify({ elderId: session.userId, type: 'ALARM' }),
    })
    const body = await res.json()
    expect(body.code).toBe(0)

    const frame = await waitFor(alarms)
    expect(frame.type).toBe('ALARM')
    expect(frame.topic).toBe(`elder:${session.userId}`)
    expect(typeof frame.ts).toBe('number')
    expect(ws.getLastEventId()).toBe(frame.id)
  })
})
