/**
 * 药品接口的**请求层链路**测试（`feat/mp-medicine`，对应 medbox-spec/02 第 5 章）。
 *
 * <p>与 `device.test.js` 同一套打桩方式：stub `uni.request`，URL / 方法 / Bearer /
 * 请求体 / 错误码分流走 `src/api/request.js` 真实实现；40101 续期与网络兜底不重复断言
 * （`request.js` 已由 user-delete 覆盖）。
 *
 * <p>⚠️ 后端 `medicine-core` 未落地：这里断言的是**前端契约**（02 第 5 章的路径与方法、
 * 分页参数、40901 兑现），真机联调后如有偏差改契约处即可。
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

async function loadMedicineApi({ token = 'TOKEN' } = {}) {
  if (token) storage.accessToken = token
  vi.resetModules()
  const request = await import('@/api/request.js')
  const medicine = await import('@/api/medicine.js')
  return { medicine, CODE: request.CODE }
}

describe('medicine —— 请求层', () => {
  it('listMedicines：GET /medicines 带 Bearer，分页与搜索参数进 query', async () => {
    const { medicine } = await loadMedicineApi()
    scripts = [{ body: { code: 0, data: { list: [], total: 0 } } }]

    const data = await medicine.listMedicines({ keyword: '阿莫西林', page: 2, size: 20 })

    expect(data).toEqual({ list: [], total: 0 })
    expect(sent).toHaveLength(1)
    expect(sent[0].method).toBe('GET')
    expect(sent[0].url).toContain('/medbox/api/v1/medicines')
    expect(sent[0].url).toContain('keyword=' + encodeURIComponent('阿莫西林'))
    expect(sent[0].url).toContain('page=2')
    expect(sent[0].url).toContain('size=20')
    expect(sent[0].header.Authorization).toBe('Bearer TOKEN')
  })

  it('listMedicines：空 keyword / 空 page 被请求层丢弃（后端有默认值，传空反而出错）', async () => {
    const { medicine } = await loadMedicineApi()
    scripts = [{ body: { code: 0, data: { list: [], total: 0 } } }]

    await medicine.listMedicines({ keyword: '', page: '', size: 20 })

    expect(sent[0].url).not.toContain('keyword')
    expect(sent[0].url).not.toContain('page')
  })

  it('getMedicine：URL 拼入 medicineId，兑现详情', async () => {
    const { medicine } = await loadMedicineApi()
    scripts = [{ body: { code: 0, data: { medicineId: 'm-205', name: '阿莫西林胶囊' } } }]

    const data = await medicine.getMedicine('m-205')

    expect(data.medicineId).toBe('m-205')
    expect(sent[0].method).toBe('GET')
    expect(sent[0].url).toContain('/medbox/api/v1/medicines/m-205')
  })

  it('saveMedicine：POST /medicines，请求体原样透传，写操作注入 X-Request-Id', async () => {
    const { medicine } = await loadMedicineApi()
    scripts = [{ body: { code: 0, data: { medicineId: 'm-205', name: '阿莫西林胶囊' } } }]

    const payload = { name: '阿莫西林胶囊', spec: '0.25g×24粒', contraindication: '过敏者禁用' }
    const data = await medicine.saveMedicine(payload)

    expect(data.medicineId).toBe('m-205')
    expect(sent[0].method).toBe('POST')
    expect(sent[0].url).toContain('/medbox/api/v1/medicines')
    expect(sent[0].data).toEqual(payload)
    expect(sent[0].header['X-Request-Id']).toMatch(/^[0-9a-f]{32}$/)
  })

  it('saveMedicine：编辑带 medicineId（02 第 5 章新建/更新同一入口）', async () => {
    const { medicine } = await loadMedicineApi()
    scripts = [{ body: { code: 0, data: { medicineId: 'm-205' } } }]

    await medicine.saveMedicine({ medicineId: 'm-205', name: '阿莫西林胶囊' })

    expect(sent[0].data).toEqual({ medicineId: 'm-205', name: '阿莫西林胶囊' })
  })

  it('deleteMedicine：DELETE /medicines/{id}', async () => {
    const { medicine } = await loadMedicineApi()
    scripts = [{ body: { code: 0 } }]

    await medicine.deleteMedicine('m-205')

    expect(sent[0].method).toBe('DELETE')
    expect(sent[0].url).toContain('/medbox/api/v1/medicines/m-205')
  })

  it('deleteMedicine：40901（被库存引用）兑现 ApiError(code 40901)，凭据保留', async () => {
    const { medicine, CODE } = await loadMedicineApi()
    scripts = [{ body: { code: 40901, message: '药品仍被库存引用，无法删除' } }]

    const error = await medicine.deleteMedicine('m-205').catch((err) => err)

    expect(error.code).toBe(CODE.CONFLICT)
    expect(error.code).toBe(40901)
    expect(storage.accessToken).toBe('TOKEN')
  })

  it('getMedicine：40302（监护未生效）兑现 ApiError，不清凭据', async () => {
    const { medicine, CODE } = await loadMedicineApi()
    scripts = [{ body: { code: 40302, message: '监护关系未生效' } }]

    const error = await medicine.getMedicine('m-205').catch((err) => err)

    expect(error.code).toBe(CODE.GUARDIAN_PENDING)
    expect(storage.accessToken).toBe('TOKEN')
  })
})
