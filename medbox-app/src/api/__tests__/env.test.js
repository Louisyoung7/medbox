import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const storage = {}
let sent = []
let scripts = []

beforeEach(() => {
  sent = []
  scripts = []
  Object.keys(storage).forEach((k) => delete storage[k])
  vi.stubGlobal('uni', {
    getStorageSync: (k) => (k in storage ? storage[k] : ''),
    setStorageSync: (k, v) => {
      storage[k] = v
    },
    removeStorageSync: (k) => {
      delete storage[k]
    },
    showLoading: () => {},
    hideLoading: () => {},
    showToast: () => {},
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
  const env = await import('@/api/env.js')
  return { env, CODE: request.CODE }
}

describe('env —— getEnvRealtime 请求层（feat/mp-env）', () => {
  it('GET /env/realtime 带 deviceId(必填) 与 sensorId(可选)、Bearer，无 X-Request-Id', async () => {
    const { env } = await loadApi()
    scripts = [{ body: { code: 0, data: { temperature: 22, humidity: 55, lux: 300, sampleTime: '2026-10-30T01:00:00Z' } } }]
    const data = await env.getEnvRealtime('BOXA1001', { sensorId: 'BOXA1001-S-TH01' })
    expect(data.temperature).toBe(22)
    expect(sent[0].method).toBe('GET')
    expect(sent[0].url).toContain('/medbox/api/v1/env/realtime')
    expect(sent[0].url).toContain('deviceId=BOXA1001')
    expect(sent[0].url).toContain('sensorId=BOXA1001-S-TH01')
    expect(sent[0].header.Authorization).toBe('Bearer TOKEN')
    expect(sent[0].header['X-Request-Id']).toBeUndefined()
  })

  it('设备缺 deviceId → 40001 / 越权 → 40301 兑现 ApiError', async () => {
    const { env, CODE } = await loadApi()
    scripts = [{ body: { code: 40001, message: 'deviceId 不能为空' } }]
    const e1 = await env.getEnvRealtime('').catch((e) => e)
    expect(e1.code).toBe(CODE.BAD_REQUEST)

    scripts = [{ body: { code: 40301, message: '无权限访问该资源' } }]
    const e2 = await env.getEnvRealtime('BOXA9999').catch((e) => e)
    expect(e2.code).toBe(CODE.FORBIDDEN)
  })
})

describe('env —— getEnvHistory 请求层（feat/mp-env）', () => {
  it('GET /env/history 带 deviceId/from/to/interval，Bearer，无 X-Request-Id', async () => {
    const { env } = await loadApi()
    scripts = [{ body: { code: 0, data: { points: [{ sampleTime: '2026-10-30T00:00:00Z', temperature: 22 }] } } }]
    const data = await env.getEnvHistory('BOXA1001', {
      from: '2026-10-23T00:00:00Z',
      to: '2026-10-30T00:00:00Z',
      interval: 60,
    })
    expect(data.points.length).toBe(1)
    expect(sent[0].method).toBe('GET')
    expect(sent[0].url).toContain('/medbox/api/v1/env/history')
    expect(sent[0].url).toContain('deviceId=BOXA1001')
    expect(sent[0].url).toContain('from=2026-10-23T00%3A00%3A00Z')
    expect(sent[0].url).toContain('to=2026-10-30T00%3A00%3A00Z')
    expect(sent[0].url).toContain('interval=60')
    expect(sent[0].header.Authorization).toBe('Bearer TOKEN')
    expect(sent[0].header['X-Request-Id']).toBeUndefined()
  })

  it('参数越界（from>to / 跨度过大）→ 40001 兑现', async () => {
    const { env, CODE } = await loadApi()
    scripts = [{ body: { code: 40001, message: 'from 不能晚于 to' } }]
    const err = await env
      .getEnvHistory('BOXA1001', { from: '2026-10-30T00:00:00Z', to: '2026-10-23T00:00:00Z' })
      .catch((e) => e)
    expect(err.code).toBe(CODE.BAD_REQUEST)
  })
})
