/**
 * 注销接口的**请求层链路**测试（`feat/mp-user-delete`，对应 medbox-spec/02 的 3.1）。
 *
 * <p>这里不打真实网络：stub 掉 `uni.request`，但**请求、响应解析、错误码分流、40101 续期重发、
 * loading 计数**全部走 `src/api/request.js` 的真实实现 —— 页面看到的行为就是这些代码决定的。
 *
 * <p>toast / loading / reLaunch 都用记录型假函数（不像页面那样依赖它们的副作用），
 * 这样才能断言"清凭据只发生一次""没重复提示"。
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/** 假的本机存储（`uni.getStorageSync` 背后的一层）。 */
const storage = {}

/** 已发出的请求（断言 URL / 方法 / 体 / 头都从这里取）。 */
let sent = []

/**
 * 应答脚本队列：`{ body, status }` 走 success，`{ fail: true }` 走网络失败。
 * 队列空了就不再消费（用 `sent.length` 断言请求次数）。
 */
let scripts = []

/** 宿主 API 调用记录。 */
let calls = null

beforeEach(() => {
  sent = []
  scripts = []
  calls = { showLoading: [], hideLoading: [], showToast: [], reLaunch: [] }
  Object.keys(storage).forEach((key) => delete storage[key])
  vi.stubGlobal('uni', {
    getStorageSync: (key) => (key in storage ? storage[key] : ''),
    setStorageSync: (key, value) => {
      storage[key] = value
    },
    removeStorageSync: (key) => {
      delete storage[key]
    },
    showLoading: (opt) => calls.showLoading.push(opt || {}),
    hideLoading: () => calls.hideLoading.push(true),
    showToast: (opt) => calls.showToast.push(opt || {}),
    reLaunch: (opt) => calls.reLaunch.push(opt || {}),
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

/** 每次都取一份全新的模块（request.js 的 unauthorizedHandler 是模块级单例，不能跨用例污染）。 */
async function loadModules({ token = 'TOKEN' } = {}) {
  if (token) {
    storage.accessToken = token
  }
  vi.resetModules()
  const request = await import('@/api/request.js')
  const user = await import('@/api/user.js')
  return { request, user, CODE: request.CODE, deleteAccount: user.deleteAccount }
}

describe('deleteAccount —— 请求层', () => {
  it('打出完整请求：URL / POST / 密码体 / Bearer / X-Request-Id，并带 loading 文案', async () => {
    const { deleteAccount } = await loadModules()
    scripts = [{ body: { code: 0, data: { userId: 'u-1001', deletedAt: '2026-10-15T02:00:00Z' } } }]

    const data = await deleteAccount('pwd-123')

    expect(data).toEqual({ userId: 'u-1001', deletedAt: '2026-10-15T02:00:00Z' })
    expect(sent).toHaveLength(1)
    const req = sent[0]
    expect(req.method).toBe('POST')
    expect(req.url).toContain('/medbox/api/v1/users/me/delete')
    expect(req.data).toEqual({ password: 'pwd-123' })
    expect(req.header.Authorization).toBe('Bearer TOKEN')
    expect(req.header['X-Request-Id']).toBeTruthy()
    // silent：错误/成功都不由请求层 toast
    expect(calls.showLoading[0].title).toBe('注销中')
    expect(calls.hideLoading).toHaveLength(1)
    expect(calls.showToast).toHaveLength(0)
  })

  it('40102 密码错误：兑现 ApiError(code 40102)，凭据保留、不 toast（页面的弹窗负责提示）', async () => {
    const { deleteAccount, CODE } = await loadModules()
    scripts = [{ body: { code: 40102, message: '密码错误', traceId: 't-1' } }]

    const error = await deleteAccount('wrong').catch((err) => err)

    expect(error.code).toBe(CODE.BAD_CREDENTIALS)
    expect(error.message).toBe('密码错误')
    expect(error.traceId).toBe('t-1')
    // 密码错≠没登录：这台设备还能继续用
    expect(storage.accessToken).toBe('TOKEN')
    expect(calls.showToast).toHaveLength(0)
    expect(calls.reLaunch).toHaveLength(0)
  })

  it('40001 密码为空：错误提示取后端 message', async () => {
    const { deleteAccount, CODE } = await loadModules()
    scripts = [{ body: { code: 40001, message: 'password 不能为空' } }]

    const error = await deleteAccount('').catch((err) => err)

    expect(error.code).toBe(CODE.BAD_REQUEST)
    expect(error.message).toBe('password 不能为空')
    expect(storage.accessToken).toBe('TOKEN')
  })

  it('40101 且未接管（mp-auth 未接入）：清本地凭据并跳登录页一次', async () => {
    const { deleteAccount, CODE } = await loadModules()
    scripts = [{ body: { code: 40101, message: '登录已失效，请重新登录' } }]

    const error = await deleteAccount('pwd-123').catch((err) => err)

    expect(error.code).toBe(CODE.UNAUTHORIZED)
    expect(storage.accessToken).toBeUndefined()
    // request 层的 reLaunch 带 complete（失败时复位重定向标记），这里只认目标与次数
    expect(calls.reLaunch).toHaveLength(1)
    expect(calls.reLaunch[0].url).toBe('/pages/auth/login')
  })

  it('40101 且接管后续期成功：自动重发一次（说明没写 noAuthRetry），新 token 带上', async () => {
    const { request, deleteAccount } = await loadModules()
    request.setUnauthorizedHandler(async () => {
      storage.accessToken = 'TOKEN-2'
      return true
    })
    scripts = [
      { body: { code: 40101, message: '登录已失效，请重新登录' } },
      { body: { code: 0, data: { userId: 'u-1001', deletedAt: '2026-10-15T02:00:00Z' } } },
    ]

    const data = await deleteAccount('pwd-123')

    expect(sent).toHaveLength(2)
    expect(sent[0].header.Authorization).toBe('Bearer TOKEN')
    expect(sent[1].header.Authorization).toBe('Bearer TOKEN-2')
    expect(data.userId).toBe('u-1001')
    expect(calls.reLaunch).toHaveLength(0)
  })

  it('网络失败：reject -1 并给出局域网排查文案，凭据保留', async () => {
    const { request, deleteAccount } = await loadModules()
    scripts = [{ fail: true }]

    const error = await deleteAccount('pwd-123').catch((err) => err)

    expect(error.code).toBe(request.NETWORK_ERROR_CODE)
    expect(error.isNetworkError()).toBe(true)
    expect(error.message).toContain('局域网')
    expect(storage.accessToken).toBe('TOKEN')
    expect(calls.reLaunch).toHaveLength(0)
  })

  it('data 缺失时兑现 null（后端 non_null 会省略空 data，别把它当异常）', async () => {
    const { deleteAccount } = await loadModules()
    scripts = [{ body: { code: 0 } }]

    await expect(deleteAccount('pwd-123')).resolves.toBeNull()
  })
})
