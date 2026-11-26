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
  const device = await import('@/api/device.js')
  return { device, CODE: request.CODE }
}

describe('device —— getDeviceEvents 请求层', () => {
  it('GET /devices/{id}/events 带 Bearer 与分页参数，兑现 {list,total}', async () => {
    const { device } = await loadApi()
    scripts = [{ body: { code: 0, data: { list: [{ eventId: 'ev-1', eventType: 'ONLINE' }], total: 1 } } }]
    const data = await device.getDeviceEvents('BOXA1001', { page: 1, size: 20 })
    expect(data.list.length).toBe(1)
    expect(data.total).toBe(1)
    expect(sent[0].method).toBe('GET')
    expect(sent[0].url).toContain('/medbox/api/v1/devices/BOXA1001/events')
    expect(sent[0].header.Authorization).toBe('Bearer TOKEN')
    expect(sent[0].header['X-Request-Id']).toBeUndefined()
    expect(sent[0].url).toContain('page=1')
    expect(sent[0].url).toContain('size=20')
  })

  it('事件流水错误码兑现（40301 越权）', async () => {
    const { device, CODE } = await loadApi()
    scripts = [{ body: { code: 40301, message: '无权限访问该资源' } }]
    const err = await device.getDeviceEvents('BOXA1001', {}).catch((e) => e)
    expect(err.code).toBe(CODE.FORBIDDEN)
  })
})

describe('device —— sendDeviceCommand 请求层', () => {
  it('POST /devices/{id}/commands 带 cmd/params/operator 与 X-Request-Id，兑现 {commandId,state}', async () => {
    const { device } = await loadApi()
    scripts = [{ body: { code: 0, data: { commandId: 'c-1', state: 'PENDING' } } }]
    const data = await device.sendDeviceCommand('BOXA1001', 'BUZZ', { durationSec: 5 }, 'u-2001')
    expect(data.commandId).toBe('c-1')
    expect(data.state).toBe('PENDING')
    expect(sent[0].method).toBe('POST')
    expect(sent[0].url).toContain('/medbox/api/v1/devices/BOXA1001/commands')
    expect(sent[0].header.Authorization).toBe('Bearer TOKEN')
    expect(sent[0].header['X-Request-Id']).toBeDefined()
    expect(sent[0].data.cmd).toBe('BUZZ')
    expect(sent[0].data.params).toEqual({ durationSec: 5 })
    expect(sent[0].data.operator).toBe('u-2001')
  })

  it('设备离线下发 40901 兑现 CONFLICT', async () => {
    const { device, CODE } = await loadApi()
    scripts = [{ body: { code: 40901, message: '设备离线，无法下发控制命令' } }]
    const err = await device.sendDeviceCommand('BOXA1002', 'BUZZ', {}, 'u-2001').catch((e) => e)
    expect(err.code).toBe(CODE.CONFLICT)
  })
})

describe('device —— registerDevice 请求层', () => {
  it('POST /devices/register 带 deviceId/elderId、Bearer 与 X-Request-Id，兑现响应', async () => {
    const { device } = await loadApi()
    scripts = [{
      body: {
        code: 0,
        data: { deviceId: 'BOXA-NEW01', elderId: 'u-1001', onlineState: 'OFFLINE' },
      },
    }]

    const data = await device.registerDevice({ deviceId: 'BOXA-NEW01', elderId: 'u-1001' })

    expect(data.deviceId).toBe('BOXA-NEW01')
    expect(data.onlineState).toBe('OFFLINE')
    expect(sent[0].method).toBe('POST')
    expect(sent[0].url).toContain('/medbox/api/v1/devices/register')
    expect(sent[0].header.Authorization).toBe('Bearer TOKEN')
    expect(sent[0].header['X-Request-Id']).toBeDefined()
    expect(sent[0].data).toEqual({ deviceId: 'BOXA-NEW01', elderId: 'u-1001' })
  })

  it('重复登记 40901 兑现 CONFLICT', async () => {
    const { device, CODE } = await loadApi()
    scripts = [{ body: { code: 40901, message: '该设备编号已登记' } }]
    const err = await device.registerDevice({ deviceId: 'BOXA-NEW01', elderId: 'u-1001' }).catch((e) => e)
    expect(err.code).toBe(CODE.CONFLICT)
  })

  it('监护关系未生效 40302 兑现 GUARDIAN_PENDING', async () => {
    const { device, CODE } = await loadApi()
    scripts = [{ body: { code: 40302, message: '监护关系未生效' } }]
    const err = await device.registerDevice({ deviceId: 'BOXA-NEW01', elderId: 'u-1001' }).catch((e) => e)
    expect(err.code).toBe(CODE.GUARDIAN_PENDING)
  })
})
