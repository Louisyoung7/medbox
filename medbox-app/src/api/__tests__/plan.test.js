/**
 * 计划接口的**请求层链路**测试（固定 3 药格模型，对应 medbox-spec/02d）。
 *
 * <p>与 `medicine.test.js` 同一套打桩方式：stub `uni.request`，
 * URL / 方法 / Bearer / 请求体 / 错误码分流走 `src/api/request.js` 真实实现。
 *
 * <p>请求体只含 `slots`（格号 → 开格时间）；40901 = 该老人已有计划，
 * 40001 = slots 为空 / 格号越界或重复 / 时间格式非法。
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

async function loadPlanApi({ token = 'TOKEN' } = {}) {
  if (token) storage.accessToken = token
  vi.resetModules()
  const request = await import('@/api/request.js')
  const plan = await import('@/api/plan.js')
  return { plan, CODE: request.CODE }
}

const PLAN_PAYLOAD = {
  elderId: 'u-1001',
  name: '日常服药',
  slots: [
    { cellNo: 1, time: '08:00' },
    { cellNo: 2, time: '12:30' },
    { cellNo: 3, time: '18:00' },
  ],
  repeatRule: 'DAILY',
}

describe('plan —— 请求层', () => {
  it('listPlans：GET /plans 带 Bearer，elderId 进 query', async () => {
    const { plan } = await loadPlanApi()
    scripts = [{ body: { code: 0, data: { list: [], total: 0 } } }]

    const data = await plan.listPlans({ elderId: 'u-1001', page: 1, size: 20 })

    expect(data).toEqual({ list: [], total: 0 })
    expect(sent[0].method).toBe('GET')
    expect(sent[0].url).toContain('/medbox/api/v1/plans')
    expect(sent[0].url).toContain('elderId=u-1001')
    expect(sent[0].header.Authorization).toBe('Bearer TOKEN')
  })

  it('getPlan：URL 拼入 planId，兑现详情（含 slots，不含 items）', async () => {
    const { plan } = await loadPlanApi()
    scripts = [{ body: { code: 0, data: { planId: 'p-3301', slots: [{ cellNo: 1, time: '08:00' }] } } }]

    const data = await plan.getPlan('p-3301')

    expect(data.planId).toBe('p-3301')
    expect(data.slots[0].cellNo).toBe(1)
    expect(data.items).toBeUndefined()
    expect(sent[0].url).toContain('/medbox/api/v1/plans/p-3301')
  })

  it('createPlan：POST /plans，请求体透传 slots，写操作注入 X-Request-Id', async () => {
    const { plan } = await loadPlanApi()
    scripts = [{ body: { code: 0, data: { planId: 'p-3301', syncState: 'PENDING', slots: PLAN_PAYLOAD.slots } } }]

    const data = await plan.createPlan(PLAN_PAYLOAD)

    expect(data.syncState).toBe('PENDING')
    expect(sent[0].method).toBe('POST')
    expect(sent[0].url).toContain('/medbox/api/v1/plans')
    expect(sent[0].data).toEqual(PLAN_PAYLOAD)
    expect(sent[0].header['X-Request-Id']).toMatch(/^[0-9a-f]{32}$/)
  })

  it('updatePlan：PUT /plans/{id}，整体覆盖请求体透传', async () => {
    const { plan } = await loadPlanApi()
    scripts = [{ body: { code: 0, data: { planId: 'p-3301' } } }]

    await plan.updatePlan('p-3301', PLAN_PAYLOAD)

    expect(sent[0].method).toBe('PUT')
    expect(sent[0].url).toContain('/medbox/api/v1/plans/p-3301')
    expect(sent[0].data).toEqual(PLAN_PAYLOAD)
  })

  it('updatePlanStatus：PATCH /plans/{id}/status，请求体契约为 {status}', async () => {
    const { plan } = await loadPlanApi()
    scripts = [{ body: { code: 0, data: { planId: 'p-3301', status: 'DISABLED' } } }]

    const data = await plan.updatePlanStatus('p-3301', 'DISABLED')

    expect(data.status).toBe('DISABLED')
    expect(sent[0].method).toBe('PATCH')
    expect(sent[0].url).toContain('/medbox/api/v1/plans/p-3301/status')
    expect(sent[0].data).toEqual({ status: 'DISABLED' })
  })

  it('deletePlan：DELETE /plans/{id}', async () => {
    const { plan } = await loadPlanApi()
    scripts = [{ body: { code: 0 } }]

    await plan.deletePlan('p-3301')

    expect(sent[0].method).toBe('DELETE')
    expect(sent[0].url).toContain('/medbox/api/v1/plans/p-3301')
  })

  it('createPlan：40901（该老人已有计划）兑现 ApiError', async () => {
    const { plan, CODE } = await loadPlanApi()
    scripts = [{ body: { code: 40901, message: '该老人已有服药计划' } }]

    const error = await plan.createPlan(PLAN_PAYLOAD).catch((err) => err)

    expect(error.code).toBe(CODE.CONFLICT)
    expect(storage.accessToken).toBe('TOKEN')
  })

  it('listPlans：40302（监护未生效）兑现 ApiError，不清凭据', async () => {
    const { plan, CODE } = await loadPlanApi()
    scripts = [{ body: { code: 40302, message: '监护关系未生效' } }]

    const error = await plan.listPlans({ elderId: 'u-1001' }).catch((err) => err)

    expect(error.code).toBe(CODE.GUARDIAN_PENDING)
    expect(storage.accessToken).toBe('TOKEN')
  })

  it('createPlan：40001（格号越界 / slots 为空）兑现 ApiError', async () => {
    const { plan, CODE } = await loadPlanApi()
    scripts = [{ body: { code: 40001, message: 'cellNo 只能为 1/2/3' } }]

    const error = await plan.createPlan({ ...PLAN_PAYLOAD, slots: [{ cellNo: 4, time: '08:00' }] }).catch((err) => err)

    expect(error.code).toBe(CODE.BAD_REQUEST)
  })
})

describe('plan —— getNextDoses 请求层（按格时间线）', () => {
  it('GET /plans/{id}/next-doses 带 days(默认 7) 与 Bearer，回带 cellNo', async () => {
    const { plan } = await loadPlanApi()
    scripts = [{ body: { code: 0, data: { doses: [{ cellNo: 1, scheduledTimeLocal: '2026-10-27T08:00:00', scheduledTimeUtc: '2026-10-27T00:00:00Z' }] } } }]

    const data = await plan.getNextDoses('p-3301', { days: 7 })

    expect(data.doses[0].cellNo).toBe(1)
    expect(data.doses[0].items).toBeUndefined()
    expect(sent[0].method).toBe('GET')
    expect(sent[0].url).toContain('/medbox/api/v1/plans/p-3301/next-doses')
    expect(sent[0].url).toContain('days=7')
    expect(sent[0].header.Authorization).toBe('Bearer TOKEN')
    expect(sent[0].header['X-Request-Id']).toBeUndefined()
  })

  it('days 缺省按 7 下发；越权 40301 / 未知 40401 兑现', async () => {
    const { plan, CODE } = await loadPlanApi()
    scripts = [{ body: { code: 40301, message: '无权限访问该资源' } }]
    const err = await plan.getNextDoses('p-3301').catch((e) => e)
    expect(err.code).toBe(CODE.FORBIDDEN)

    scripts = [{ body: { code: 40401, message: '资源不存在' } }]
    const err2 = await plan.getNextDoses('p-9999', { days: 15 }).catch((e) => e)
    expect(err2.code).toBe(CODE.NOT_FOUND)
  })
})
