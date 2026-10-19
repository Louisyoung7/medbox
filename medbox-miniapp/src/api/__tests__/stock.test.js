/**
 * 库存接口的**请求层链路**测试（`feat/mp-stock`，对应 medbox-spec/02 第 5 章库存四条）。
 *
 * <p>与 `medicine.test.js` / `device.test.js` 同一套打桩方式：stub `uni.request`，
 * URL / 方法 / Bearer / 请求体 / 错误码分流走 `src/api/request.js` 真实实现。
 *
 * <p>⚠️ 后端 `medicine-core` 未落地：这里断言的是**前端契约**（02 第 5 章的路径与方法、
 * PUT / POST-in 的请求体透传、设备维度 40301 惯例），真机联调后如有偏差改契约处即可。
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

async function loadStockApi({ token = 'TOKEN' } = {}) {
  if (token) storage.accessToken = token
  vi.resetModules()
  const request = await import('@/api/request.js')
  const stock = await import('@/api/stock.js')
  return { stock, CODE: request.CODE }
}

describe('stock —— 请求层', () => {
  it('listStocks：GET /devices/{id}/stocks 带 Bearer，兑现库存数组', async () => {
    const { stock } = await loadStockApi()
    const rows = [{ stockId: 's-1', medicineId: 'm-205', quantity: 24 }]
    scripts = [{ body: { code: 0, data: rows } }]

    const data = await stock.listStocks('BOXA1001')

    expect(data).toEqual(rows)
    expect(sent).toHaveLength(1)
    expect(sent[0].method).toBe('GET')
    expect(sent[0].url).toContain('/medbox/api/v1/devices/BOXA1001/stocks')
    expect(sent[0].header.Authorization).toBe('Bearer TOKEN')
  })

  it('saveStock：PUT /devices/{id}/stocks/{medicineId}，请求体透传，写操作注入 X-Request-Id', async () => {
    const { stock } = await loadStockApi()
    scripts = [{ body: { code: 0, data: { stockId: 's-1', quantity: 24 } } }]

    const payload = { quantity: 24, productionDate: '2026-01-01', expiryDate: '2026-12-31' }
    const data = await stock.saveStock('BOXA1001', 'm-205', payload)

    expect(data.quantity).toBe(24)
    expect(sent[0].method).toBe('PUT')
    expect(sent[0].url).toContain('/medbox/api/v1/devices/BOXA1001/stocks/m-205')
    expect(sent[0].data).toEqual(payload)
    expect(sent[0].header['X-Request-Id']).toMatch(/^[0-9a-f]{32}$/)
  })

  it('restockStock：POST /devices/{id}/stocks/{medicineId}/in，增量请求体透传', async () => {
    const { stock } = await loadStockApi()
    scripts = [{ body: { code: 0, data: { stockId: 's-1', quantity: 34 } } }]

    const payload = { quantity: 10, expiryDate: '2027-06-30' }
    const data = await stock.restockStock('BOXA1001', 'm-205', payload)

    expect(data.quantity).toBe(34)
    expect(sent[0].method).toBe('POST')
    expect(sent[0].url).toContain('/medbox/api/v1/devices/BOXA1001/stocks/m-205/in')
    expect(sent[0].data).toEqual(payload)
  })

  it('deleteStock：DELETE /devices/{id}/stocks/{medicineId}', async () => {
    const { stock } = await loadStockApi()
    scripts = [{ body: { code: 0 } }]

    await stock.deleteStock('BOXA1001', 'm-205')

    expect(sent[0].method).toBe('DELETE')
    expect(sent[0].url).toContain('/medbox/api/v1/devices/BOXA1001/stocks/m-205')
  })

  it('listStocks：40301（他人设备 / 设备不存在）兑现 ApiError，凭据保留', async () => {
    const { stock, CODE } = await loadStockApi()
    scripts = [{ body: { code: 40301, message: '无权限访问该资源' } }]

    const error = await stock.listStocks('OTHER').catch((err) => err)

    expect(error.code).toBe(CODE.FORBIDDEN)
    expect(storage.accessToken).toBe('TOKEN')
  })

  it('listStocks：40302（监护未生效）兑现 ApiError，不清凭据', async () => {
    const { stock, CODE } = await loadStockApi()
    scripts = [{ body: { code: 40302, message: '监护关系未生效' } }]

    const error = await stock.listStocks('BOXA1001').catch((err) => err)

    expect(error.code).toBe(CODE.GUARDIAN_PENDING)
    expect(storage.accessToken).toBe('TOKEN')
  })

  it('saveStock：40001（数量非法等参数错误）兑现 ApiError', async () => {
    const { stock, CODE } = await loadStockApi()
    scripts = [{ body: { code: 40001, message: 'quantity 必须为正整数' } }]

    const error = await stock
      .saveStock('BOXA1001', 'm-205', { quantity: -1 })
      .catch((err) => err)

    expect(error.code).toBe(CODE.BAD_REQUEST)
  })
})
