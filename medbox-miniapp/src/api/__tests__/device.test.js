/**
 * 设备接口的**请求层链路**测试（`feat/mp-device`，对应 medbox-spec/02 第 4 章）。
 *
 * <p>与 `user-delete.test.js` 同一套打桩方式：stub `uni.request`，请求 / 响应解析 / 错误码
 * 分流走 `src/api/request.js` 真实实现。这里只验证 device 两个读取接口的 URL / 方法 / Bearer
 * 与错误码兑现，不做重复的 40101 续期 / 网络兜底断言（那部分 request.js 已由 user-delete 覆盖）。
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const storage = {}
let sent = []
let scripts = []

beforeEach(() => {
  sent = []
  scripts = []
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

async function loadDeviceApi({ token = 'TOKEN' } = {}) {
  if (token) storage.accessToken = token
  vi.resetModules()
  const request = await import('@/api/request.js')
  const device = await import('@/api/device.js')
  return { request, device, CODE: request.CODE }
}

describe('device —— 请求层', () => {
  it('listDevices：GET /devices 带 Bearer，兑现设备数组', async () => {
    const { device } = await loadDeviceApi()
    scripts = [{ body: { code: 0, data: [{ deviceId: 'BOXA1001', onlineState: 'ONLINE' }] } }]

    const data = await device.listDevices()

    expect(data).toEqual([{ deviceId: 'BOXA1001', onlineState: 'ONLINE' }])
    expect(sent).toHaveLength(1)
    expect(sent[0].method).toBe('GET')
    expect(sent[0].url).toContain('/medbox/api/v1/devices')
    expect(sent[0].header.Authorization).toBe('Bearer TOKEN')
  })

  it('getDevice：URL 拼入 deviceId，兑现详情（含 sensors）', async () => {
    const { device } = await loadDeviceApi()
    scripts = [{ body: { code: 0, data: { deviceId: 'BOXA1001', sensors: [] } } }]

    const data = await device.getDevice('BOXA1001')

    expect(data.deviceId).toBe('BOXA1001')
    expect(sent).toHaveLength(1)
    expect(sent[0].url).toContain('/medbox/api/v1/devices/BOXA1001')
  })

  it('40301（他人设备 / 不存在）：兑现 ApiError(code 40301)，凭据保留', async () => {
    const { device, CODE } = await loadDeviceApi()
    scripts = [{ body: { code: 40301, message: '无权限访问该资源' } }]

    const error = await device.getDevice('OTHER').catch((err) => err)

    expect(error.code).toBe(CODE.FORBIDDEN)
    expect(storage.accessToken).toBe('TOKEN')
  })
})
