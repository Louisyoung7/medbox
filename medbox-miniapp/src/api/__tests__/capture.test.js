/**
 * 抓拍接口的**请求层链路**测试（`feat/mp-capture-event`，对应 medbox-spec/02 设备管理.md §4.1）。
 *
 * <p>与 `record.test.js` 同一套打桩方式：stub `uni.request`，断言 URL / 方法 / Bearer /
 * 错误码分流走 `src/api/request.js` 真实实现。
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

async function loadCaptureApi({ token = 'TOKEN' } = {}) {
  if (token) storage.accessToken = token
  vi.resetModules()
  const request = await import('@/api/request.js')
  const capture = await import('@/api/capture.js')
  return { capture, CODE: request.CODE }
}

describe('capture —— 请求层', () => {
  it('getCapture：GET /captures/{id}，带 Bearer，兑现元数据与 fileToken', async () => {
    const { capture } = await loadCaptureApi()
    scripts = [{ body: { code: 0, data: { captureId: 'cap-5001', fileToken: 'tok-xxx' } } }]

    const data = await capture.getCapture('cap-5001')

    expect(data.captureId).toBe('cap-5001')
    expect(data.fileToken).toBe('tok-xxx')
    expect(sent[0].method).toBe('GET')
    expect(sent[0].url).toContain('/medbox/api/v1/captures/cap-5001')
    expect(sent[0].header.Authorization).toBe('Bearer TOKEN')
  })

  it('listCaptures：GET /captures，拼 elderId + reason query，带 Bearer', async () => {
    const { capture } = await loadCaptureApi()
    scripts = [{ body: { code: 0, data: { list: [{ captureId: 'cap-1' }], total: 1, page: 1, size: 1 } } }]

    const data = await capture.listCaptures('u-1001', { reason: 'MANUAL' })

    expect(data.total).toBe(1)
    expect(sent[0].method).toBe('GET')
    expect(sent[0].url).toContain('/medbox/api/v1/captures')
    expect(sent[0].url).toContain('elderId=u-1001')
    expect(sent[0].url).toContain('reason=MANUAL')
    expect(sent[0].header.Authorization).toBe('Bearer TOKEN')
  })

  it('deleteCapture：DELETE /captures/{id}，带 Bearer', async () => {
    const { capture } = await loadCaptureApi()
    scripts = [{ body: { code: 0, data: null } }]

    const data = await capture.deleteCapture('cap-1')

    expect(data).toBe(null)
    expect(sent[0].method).toBe('DELETE')
    expect(sent[0].url).toContain('/medbox/api/v1/captures/cap-1')
    expect(sent[0].header.Authorization).toBe('Bearer TOKEN')
  })
})
