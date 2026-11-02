import { describe, it, expect, vi, beforeEach } from 'vitest'

// 用 vi.hoisted 让 handlers 在 mock 工厂（会被提升）之前就绪，避免 TDZ。
const { handlers } = vi.hoisted(() => ({
  handlers: { onFulfilled: undefined, onRejected: undefined },
}))

vi.mock('axios', () => {
  const instance = {
    interceptors: {
      request: { use: vi.fn() },
      response: {
        use: vi.fn((onFulfilled, onRejected) => {
          handlers.onFulfilled = onFulfilled
          handlers.onRejected = onRejected
        }),
      },
    },
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn(),
  }
  return { default: { create: vi.fn(() => instance) } }
})

vi.mock('../../src/stores/token', () => ({
  getToken: vi.fn(() => 'tok'),
  clearAuth: vi.fn(),
}))

vi.mock('../../src/config', () => ({ BASE_URL: '/api' }))

// 副作用导入：触发 axios.create + 拦截器注册。
import { clearAuth } from '../../src/stores/token'
import '../../src/api/request'

describe('request 响应拦截 - 会话失效兜底', () => {
  beforeEach(() => {
    // 只清 mock 调用记录；拦截器函数在模块导入时注册一次，引用保持稳定，不可置空。
    vi.clearAllMocks()
  })

  function stubLocation(pathname) {
    vi.stubGlobal('location', { pathname, href: '' })
  }

  it('HTTP 401（无业务码）时清空凭据并跳转 /login', async () => {
    stubLocation('/test-data')
    const err = { response: { status: 401, data: null } }

    await expect(handlers.onRejected(err)).rejects.toBeDefined()

    expect(clearAuth).toHaveBeenCalledTimes(1)
    expect(location.href).toBe('/login')
  })

  it('HTTP 403 且业务码 40301 时清空凭据并跳转 /login', async () => {
    stubLocation('/test-data')
    const err = { response: { status: 403, data: { code: 40301, message: '无权限' } } }

    await expect(handlers.onRejected(err)).rejects.toBeDefined()

    expect(clearAuth).toHaveBeenCalledTimes(1)
    expect(location.href).toBe('/login')
  })

  it('成功分支业务码 40101 时也兜底清凭据', async () => {
    stubLocation('/test-data')
    const resp = { data: { code: 40101, message: '登录已过期', data: null } }

    await expect(handlers.onFulfilled(resp)).rejects.toMatchObject({ code: 40101 })
    expect(clearAuth).toHaveBeenCalledTimes(1)
    expect(location.href).toBe('/login')
  })

  it('40102（登录失败）不触发兜底，停留在登录页', async () => {
    stubLocation('/login')
    const err = { response: { status: 401, data: { code: 40102, message: '账号或密码错误' } } }

    await expect(handlers.onRejected(err)).rejects.toBeDefined()

    expect(clearAuth).not.toHaveBeenCalled()
  })

  it('网络层失败（无 response）不触发兜底，原样 reject', async () => {
    stubLocation('/test-data')
    const err = new Error('network error')

    await expect(handlers.onRejected(err)).rejects.toBe(err)
    expect(clearAuth).not.toHaveBeenCalled()
  })

  it('已在 /login 页时不再重复跳转', async () => {
    stubLocation('/login')
    const err = { response: { status: 401, data: null } }

    await expect(handlers.onRejected(err)).rejects.toBeDefined()

    expect(clearAuth).toHaveBeenCalledTimes(1)
    expect(location.href).toBe('') // 未设置跳转
  })
})
