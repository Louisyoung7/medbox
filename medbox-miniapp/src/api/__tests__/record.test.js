/**
 * 记录接口的**请求层链路**测试（`feat/mp-record`，对应 medbox-spec/02 第 7 章）。
 *
 * <p>与 `plan.test.js` / `stock.test.js` 同一套打桩方式：stub `uni.request`，
 * URL / 方法 / Bearer / 请求体 / 错误码分流走 `src/api/request.js` 真实实现。
 *
 * <p>⚠️ 后端 `record-core` 未落地：这里断言的是**前端契约**（02 第 7 章的路径与方法、
 * 分页 query 透传、confirm 写操作注入 X-Request-Id、40301/40302/40401 兑现），
 * 真机联调后如有偏差改契约处即可。
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

async function loadRecordApi({ token = 'TOKEN' } = {}) {
  if (token) storage.accessToken = token
  vi.resetModules()
  const request = await import('@/api/request.js')
  const record = await import('@/api/record.js')
  return { record, CODE: request.CODE }
}

describe('record —— 请求层', () => {
  it('listRecords：GET /records 带 Bearer，elderId/from/to 进 query（空值省略）', async () => {
    const { record } = await loadRecordApi()
    scripts = [{ body: { code: 0, data: { list: [], total: 0 } } }]

    const data = await record.listRecords({ elderId: 'u-1001', page: 1, size: 20 })

    expect(data).toEqual({ list: [], total: 0 })
    expect(sent[0].method).toBe('GET')
    expect(sent[0].url).toContain('/medbox/api/v1/records')
    expect(sent[0].url).toContain('elderId=u-1001')
    expect(sent[0].url).toContain('page=1')
    expect(sent[0].url).toContain('size=20')
    expect(sent[0].header.Authorization).toBe('Bearer TOKEN')
  })

  it('listRecords：from/to 为空字符串时省略（避免后端误判）', async () => {
    const { record } = await loadRecordApi()
    scripts = [{ body: { code: 0, data: { list: [], total: 0 } } }]

    await record.listRecords({ elderId: 'u-1001', from: '', to: '', page: 1, size: 20 })

    expect(sent[0].url).not.toContain('from=')
    expect(sent[0].url).not.toContain('to=')
  })

  it('getRecord：URL 拼入 recordId，兑现详情', async () => {
    const { record } = await loadRecordApi()
    scripts = [{ body: { code: 0, data: { recordId: 'rec-9001', planName: '早餐后' } } }]

    const data = await record.getRecord('rec-9001')

    expect(data.recordId).toBe('rec-9001')
    expect(sent[0].method).toBe('GET')
    expect(sent[0].url).toContain('/medbox/api/v1/records/rec-9001')
  })

  it('confirmRecord：POST /records/{id}/confirm，请求体透传，写操作注入 X-Request-Id', async () => {
    const { record } = await loadRecordApi()
    scripts = [{ body: { code: 0, data: { recordId: 'rec-9001', confirmedBy: 'u-2001' } } }]

    const data = await record.confirmRecord('rec-9001', { note: '已核对' })

    expect(data.confirmedBy).toBe('u-2001')
    expect(sent[0].method).toBe('POST')
    expect(sent[0].url).toContain('/medbox/api/v1/records/rec-9001/confirm')
    expect(sent[0].data).toEqual({ note: '已核对' })
    expect(sent[0].header['X-Request-Id']).toMatch(/^[0-9a-f]{32}$/)
  })

  it('getRecord：40401（记录不存在）兑现 ApiError（NOT_FOUND）', async () => {
    const { record, CODE } = await loadRecordApi()
    scripts = [{ body: { code: 40401, message: '资源不存在' } }]

    const error = await record.getRecord('rec-9999').catch((err) => err)

    expect(error.code).toBe(CODE.NOT_FOUND)
    expect(storage.accessToken).toBe('TOKEN')
  })

  it('confirmRecord：40301（老人 / 越权写）兑现 ApiError（FORBIDDEN）', async () => {
    const { record, CODE } = await loadRecordApi()
    scripts = [{ body: { code: 40301, message: '无权限执行该操作' } }]

    const error = await record.confirmRecord('rec-9001', {}).catch((err) => err)

    expect(error.code).toBe(CODE.FORBIDDEN)
  })

  it('listRecords：40302（监护未生效）兑现 ApiError（GUARDIAN_PENDING）', async () => {
    const { record, CODE } = await loadRecordApi()
    scripts = [{ body: { code: 40302, message: '监护关系未生效' } }]

    const error = await record.listRecords({ elderId: 'u-1001' }).catch((err) => err)

    expect(error.code).toBe(CODE.GUARDIAN_PENDING)
  })
})
