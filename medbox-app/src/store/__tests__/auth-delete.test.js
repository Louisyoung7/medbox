/**
 * 注销的**编排层**测试（`feat/mp-user-delete`）。
 *
 * <p>`src/store/auth.js` 的 `deleteAccount` 是整个注销流程里唯一被页面调用的函数：它决定
 * "什么时候清凭据、要不要断 WS、什么时候跳登录"。这几件事**错了不会报错、只会悄悄错**，
 * 所以这里把四种结果分别钉死：
 *
 * - 成功：凭据清空 + WS 断开 + 跳登录页（各一次）；
 * - 密码错误 / 网络失败：**保留**凭据（用户还得继续用这台设备）、不跳；
 * - 密码原样传透给接口层（编排层不许自己加工 or 吞异常）。
 *
 * <p>`@/api/user.js` 用 `vi.mock` 替换成 spy；WS 用假的 SocketTask 驱动，验证完真的会 `close()`。
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/utils/error.js'

vi.mock('@/api/user.js', () => ({
  deleteAccount: vi.fn(async () => ({ userId: 'u-1001', deletedAt: '2026-10-15T02:00:00Z' })),
}))

/** 假的本机存储。 */
const storage = {}

/** `uni.reLaunch` 调用记录。 */
let launches = []

/** 最近一次 `uni.connectSocket` 造出来的假 SocketTask。 */
let tasks = []

beforeEach(() => {
  launches = []
  tasks = []
  Object.keys(storage).forEach((key) => delete storage[key])
  // 先放一份"已登录"的凭据与资料，注销成功后应该全部消失
  storage.accessToken = 'TOKEN'
  storage.refreshToken = 'REFRESH'
  storage.userInfo = { userId: 'u-1001', role: 'ELDER', name: '张三' }
  vi.stubGlobal('uni', {
    getStorageSync: (key) => (key in storage ? storage[key] : ''),
    setStorageSync: (key, value) => {
      storage[key] = value
    },
    removeStorageSync: (key) => {
      delete storage[key]
    },
    showToast: vi.fn(),
    showLoading: vi.fn(),
    hideLoading: vi.fn(),
    reLaunch: (opt) => launches.push(opt || {}),
    connectSocket: () => {
      const handlers = {}
      const task = {
        closed: false,
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
        readyState: 0, // CONNECTING；与真实 SocketTask 一致
        send: vi.fn(),
        close: () => {
          task.closed = true
          task.readyState = 3 // CLOSED
        },
        open: () => {
          task.readyState = 1 // OPEN
          handlers.open && handlers.open()
        },
      }
      tasks.push(task)
      return task
    },
    sendSocketMessage: vi.fn(),
    closeSocket: vi.fn(),
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

/** 取全新的 auth / ws / api 模块（三者都是模块级单例，不能跨用例共享状态）。 */
async function loadModules() {
  vi.resetModules()
  const api = await import('@/api/user.js')
  const ws = await import('@/store/ws.js')
  const auth = await import('@/store/auth.js')
  // vi.mock 的 spy 不随 resetModules 重建，调用记录会跨用例累积
  api.deleteAccount.mockClear()
  return { api, ws, auth }
}

/** 先把 WS 连上（open 成功），验证注销会把它断开。 */
async function connectWs(ws) {
  expect(ws.connect({ elderId: 'u-1001' })).toBe(true)
  tasks[tasks.length - 1].open()
  expect(ws.getState()).toBe(ws.WS_STATE.OPEN)
}

describe('deleteAccount —— 编排层', () => {
  it('成功：清凭据（token + 用户资料成对）→ 断 WS → 跳登录页一次', async () => {
    const { api, ws, auth } = await loadModules()
    await connectWs(ws)

    const data = await auth.deleteAccount('pwd-123')

    expect(data).toEqual({ userId: 'u-1001', deletedAt: '2026-10-15T02:00:00Z' })
    expect(api.deleteAccount).toHaveBeenCalledWith('pwd-123')
    // 半登录态检查：token 与用户信息必须一起没
    expect(storage.accessToken).toBeUndefined()
    expect(storage.refreshToken).toBeUndefined()
    expect(storage.userInfo).toBeUndefined()
    // WS 不再以已注销的身份订阅 elder:{id}
    expect(ws.getState()).toBe(ws.WS_STATE.CLOSED)
    expect(tasks[0].closed).toBe(true)
    expect(launches).toEqual([{ url: '/pages/auth/login' }])
  })

  it('密码错误（40102）：凭据与 WS 都保留，不跳登录页', async () => {
    const { api, ws, auth } = await loadModules()
    await connectWs(ws)
    api.deleteAccount.mockRejectedValueOnce(new ApiError({ code: 40102, message: '密码错误' }))

    const error = await auth.deleteAccount('wrong').catch((err) => err)

    expect(error.code).toBe(40102)
    expect(storage.accessToken).toBe('TOKEN')
    expect(storage.userInfo).toEqual({ userId: 'u-1001', role: 'ELDER', name: '张三' })
    expect(ws.getState()).toBe(ws.WS_STATE.OPEN)
    expect(launches).toHaveLength(0)
  })

  it('网络失败（-1）：凭据保留，不跳登录页（只是没连上局域网，别把用户踢下线）', async () => {
    const { api, ws, auth } = await loadModules()
    await connectWs(ws)
    api.deleteAccount.mockRejectedValueOnce(
      new ApiError({ code: -1, message: '网络异常，请检查手机与电脑是否在同一局域网' }),
    )

    const error = await auth.deleteAccount('pwd-123').catch((err) => err)

    expect(error.isNetworkError()).toBe(true)
    expect(storage.accessToken).toBe('TOKEN')
    expect(ws.getState()).toBe(ws.WS_STATE.OPEN)
    expect(launches).toHaveLength(0)
  })

  it('未起连 WS 时注销也不报错（多数用户不是从首页发起的）', async () => {
    const { auth } = await loadModules()

    await expect(auth.deleteAccount('pwd-123')).resolves.toMatchObject({ userId: 'u-1001' })
    expect(storage.accessToken).toBeUndefined()
    expect(launches).toEqual([{ url: '/pages/auth/login' }])
  })
})
