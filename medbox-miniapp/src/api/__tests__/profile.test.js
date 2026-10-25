/**
 * 用户资料接口的**请求层链路**测试（`feat/mp-profile`，对应 medbox-spec/02 第 3 章）。
 *
 * <p>覆盖本分支新增/改动的两个请求层行为：
 *
 * - `api/user.js` 的 {@link getElderProfile}：`GET /users/{elderId}/profile` 的路径、
 *   Bearer、错误码兑现（40302 PENDING / 40301 越权防枚举 / 网络 -1）；
 * - `api/auth.js` 的 `getMe(options)`：本分支给它加了可选 options，个人页"缺字段静默
 *   补齐"靠 `silent: true` —— 这里断言 silent 真的生效（失败不弹 toast），不传时保持
 *   原行为（照旧 toast）。
 *
 * <p>后端 `user-profile` 已落地，无前端先行契约；真机联调后如有偏差改契约处即可。
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const storage = {}
let sent = []
let scripts = []
let toastCalls = 0

beforeEach(() => {
  sent = []
  scripts = []
  toastCalls = 0
  Object.keys(storage).forEach((key) => delete storage[key])
  vi.stubGlobal('uni', {
    getStorageSync: (key) => (key in storage ? storage[key] : ''),
    setStorageSync: (key, value) => {
      storage[key] = value
    },
    removeStorageSync: (key) => {
      delete storage[key]
    },
    showLoading: () => {},
    hideLoading: () => {},
    showToast: () => {
      toastCalls += 1
    },
    reLaunch: () => {},
    request: (options) => {
      sent.push(options)
      const script = scripts.shift() || { body: { code: 0, data: null } }
      if (script.fail) {
        options.fail({ errMsg: 'request:fail' })
        return
      }
      options.success({ statusCode: script.status || 200, data: script.body })
    },
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

async function loadApi({ token = 'TOKEN' } = {}) {
  if (token) storage.accessToken = token
  vi.resetModules()
  const request = await import('@/api/request.js')
  const user = await import('@/api/user.js')
  const auth = await import('@/api/auth.js')
  return { user, auth, CODE: request.CODE }
}

describe('profile —— getElderProfile 请求层', () => {
  it('GET /users/{elderId}/profile 带 Bearer，兑现全字段', async () => {
    const { user } = await loadApi()
    scripts = [
      {
        body: {
          code: 0,
          data: { userId: 'u-1001', role: 'ELDER', name: '张桂英', phone: '13900000001', username: 'guiying' },
        },
      },
    ]

    const data = await user.getElderProfile('u-1001')

    expect(data).toEqual({ userId: 'u-1001', role: 'ELDER', name: '张桂英', phone: '13900000001', username: 'guiying' })
    expect(sent[0].method).toBe('GET')
    expect(sent[0].url).toContain('/medbox/api/v1/users/u-1001/profile')
    expect(sent[0].header.Authorization).toBe('Bearer TOKEN')
    // GET 不注入 X-Request-Id（只给写操作，见 request.js 的 WRITE_METHODS）
    expect(sent[0].header['X-Request-Id']).toBeUndefined()
  })

  it('username 省略（后端 non_null）时原样兑现，不补默认值', async () => {
    const { user } = await loadApi()
    scripts = [{ body: { code: 0, data: { userId: 'u-1002', role: 'ELDER', name: '李德海', phone: '13900000002' } } }]

    const data = await user.getElderProfile('u-1002')

    expect('username' in data).toBe(false)
    expect(data.name).toBe('李德海')
  })

  it('40302（PENDING 监护）兑现 ApiError（GUARDIAN_PENDING）', async () => {
    const { user, CODE } = await loadApi()
    scripts = [{ body: { code: 40302, message: '监护关系未生效，请等待老人确认' } }]

    const error = await user.getElderProfile('u-1001').catch((err) => err)

    expect(error.code).toBe(CODE.GUARDIAN_PENDING)
  })

  it('40301（无关系 / REVOKED / 目标非老人）兑现 ApiError（FORBIDDEN）', async () => {
    const { user, CODE } = await loadApi()
    scripts = [{ body: { code: 40301, message: '无权限访问该资源' } }]

    const error = await user.getElderProfile('u-1001').catch((err) => err)

    expect(error.code).toBe(CODE.FORBIDDEN)
    expect(storage.accessToken).toBe('TOKEN')
  })

  it('网络失败兑现 ApiError（-1）', async () => {
    const { user } = await loadApi()
    scripts = [{ fail: true }]

    const error = await user.getElderProfile('u-1001').catch((err) => err)

    expect(error.isNetworkError()).toBe(true)
  })
})

describe('profile —— getMe(options) 透传（feat/mp-profile 改动）', () => {
  it('getMe({silent:true})：失败不弹 toast（个人页背景补齐不打扰用户）', async () => {
    const { auth } = await loadApi()
    scripts = [{ body: { code: 50000, message: '服务端内部错误' } }]

    const error = await auth.getMe({ silent: true }).catch((err) => err)

    expect(error.code).toBe(50000)
    expect(toastCalls).toBe(0)
  })

  it('getMe() 不传 options：保持原行为，失败照旧 toast', async () => {
    const { auth } = await loadApi()
    scripts = [{ body: { code: 50000, message: '服务端内部错误' } }]

    const error = await auth.getMe().catch((err) => err)

    expect(error.code).toBe(50000)
    expect(toastCalls).toBe(1)
  })

  it('getMe()：GET /users/me 兑现资料', async () => {
    const { auth } = await loadApi()
    scripts = [{ body: { code: 0, data: { userId: 'u-1001', role: 'ELDER', name: '张桂英', phone: '13900000001' } } }]

    const data = await auth.getMe()

    expect(data.userId).toBe('u-1001')
    expect(sent[0].url).toContain('/medbox/api/v1/users/me')
  })
})
