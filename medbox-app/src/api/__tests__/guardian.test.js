/**
 * 监护关系接口的**请求层链路**测试（`feat/mp-guardian`，对应 medbox-spec/02 第 3 章的 3.2）。
 *
 * <p>与 `record.test.js` 同一套打桩方式：stub `uni.request`，URL / 方法 / Bearer / 请求体 /
 * 错误码分流走 `src/api/request.js` 真实实现。
 *
 * <p>**后端 `guardian-relation` 已落地**（07 已勾选）：这里断言的是 02 的 3.2 契约本身 ——
 * 四条接口的路径与方法、申请无请求体、accept 的空 `{}` 体、写操作注入 `X-Request-Id`、
 * 40301 / 40901 兑现，无前端先行契约。
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

async function loadGuardianApi({ token = 'TOKEN' } = {}) {
  if (token) storage.accessToken = token
  vi.resetModules()
  const request = await import('@/api/request.js')
  const guardian = await import('@/api/guardian.js')
  return { guardian, CODE: request.CODE }
}

describe('guardian —— 请求层', () => {
  it('applyGuardian：POST /users/{elderId}/guardians，无请求体，写操作注入 X-Request-Id', async () => {
    const { guardian } = await loadGuardianApi()
    scripts = [
      {
        body: {
          code: 0,
          data: { relationId: 'gr-9', elderId: 'u-1001', guardianId: 'u-2001', status: 'PENDING', createdAt: '2026-10-23T08:00:00Z' },
        },
      },
    ]

    const data = await guardian.applyGuardian('u-1001')

    expect(data.relationId).toBe('gr-9')
    expect(data.status).toBe('PENDING')
    expect(sent[0].method).toBe('POST')
    expect(sent[0].url).toContain('/medbox/api/v1/users/u-1001/guardians')
    expect(sent[0].data).toBeUndefined()
    expect(sent[0].header.Authorization).toBe('Bearer TOKEN')
    expect(sent[0].header['X-Request-Id']).toMatch(/^[0-9a-f]{32}$/)
  })

  it('applyGuardian：40301（非监护人 / 目标不是老人）兑现 ApiError（FORBIDDEN）', async () => {
    const { guardian, CODE } = await loadGuardianApi()
    scripts = [{ body: { code: 40301, message: '目标用户不存在或不是老人账号' } }]

    const error = await guardian.applyGuardian('u-9999').catch((err) => err)

    expect(error.code).toBe(CODE.FORBIDDEN)
    expect(storage.accessToken).toBe('TOKEN')
  })

  it('applyGuardian：40901（已有 PENDING / ACTIVE）兑现 ApiError（CONFLICT）', async () => {
    const { guardian, CODE } = await loadGuardianApi()
    scripts = [{ body: { code: 40901, message: '已存在进行中或已生效的监护关系' } }]

    const error = await guardian.applyGuardian('u-1001').catch((err) => err)

    expect(error.code).toBe(CODE.CONFLICT)
  })

  it('listPendingGuardians：GET /users/guardians/pending，兑现数组', async () => {
    const { guardian } = await loadGuardianApi()
    scripts = [
      {
        body: {
          code: 0,
          data: [{ relationId: 'gr-3', elderId: 'u-1001', guardianId: 'u-2002', guardianName: '王芳', guardianPhone: '13800001235', createdAt: '2026-10-21T08:00:00Z' }],
        },
      },
    ]

    const data = await guardian.listPendingGuardians()

    expect(Array.isArray(data)).toBe(true)
    expect(data[0].guardianName).toBe('王芳')
    expect(sent[0].method).toBe('GET')
    expect(sent[0].url).toContain('/medbox/api/v1/users/guardians/pending')
    expect(sent[0].header.Authorization).toBe('Bearer TOKEN')
    // GET 不注入 X-Request-Id（只给写操作，见 request.js 的 WRITE_METHODS）
    expect(sent[0].header['X-Request-Id']).toBeUndefined()
  })

  it('listPendingGuardians：40301（非老人端）兑现 ApiError（FORBIDDEN）', async () => {
    const { guardian, CODE } = await loadGuardianApi()
    scripts = [{ body: { code: 40301, message: '只有老人账号可以查看待确认列表' } }]

    const error = await guardian.listPendingGuardians().catch((err) => err)

    expect(error.code).toBe(CODE.FORBIDDEN)
  })

  it('acceptGuardian：PATCH /users/guardians/{relationId}/accept，请求体为空的 {}', async () => {
    const { guardian } = await loadGuardianApi()
    scripts = [
      {
        body: {
          code: 0,
          data: { relationId: 'gr-3', elderId: 'u-1001', guardianId: 'u-2002', status: 'ACTIVE', createdAt: '2026-10-21T08:00:00Z' },
        },
      },
    ]

    const data = await guardian.acceptGuardian('gr-3')

    expect(data.status).toBe('ACTIVE')
    expect(sent[0].method).toBe('PATCH')
    expect(sent[0].url).toContain('/medbox/api/v1/users/guardians/gr-3/accept')
    expect(sent[0].data).toEqual({})
    expect(sent[0].header['X-Request-Id']).toMatch(/^[0-9a-f]{32}$/)
  })

  it('acceptGuardian：40901（已非 PENDING）兑现 ApiError（CONFLICT）', async () => {
    const { guardian, CODE } = await loadGuardianApi()
    scripts = [{ body: { code: 40901, message: '该监护请求已被处理' } }]

    const error = await guardian.acceptGuardian('gr-3').catch((err) => err)

    expect(error.code).toBe(CODE.CONFLICT)
  })

  it('revokeGuardian：DELETE /users/guardians/{relationId}，成功无 data', async () => {
    const { guardian } = await loadGuardianApi()
    scripts = [{ body: { code: 0 } }]

    const data = await guardian.revokeGuardian('gr-3')

    expect(data).toBeNull()
    expect(sent[0].method).toBe('DELETE')
    expect(sent[0].url).toContain('/medbox/api/v1/users/guardians/gr-3')
    expect(sent[0].header['X-Request-Id']).toMatch(/^[0-9a-f]{32}$/)
  })

  it('revokeGuardian：40301（查不到 / 非关系双方）兑现 ApiError（FORBIDDEN）', async () => {
    const { guardian, CODE } = await loadGuardianApi()
    scripts = [{ body: { code: 40301, message: '监护关系不存在' } }]

    const error = await guardian.revokeGuardian('gr-9999').catch((err) => err)

    expect(error.code).toBe(CODE.FORBIDDEN)
  })

  it('listPendingGuardians：网络失败兑现 ApiError（-1）', async () => {
    const { guardian } = await loadGuardianApi()
    scripts = [{ fail: true }]

    const error = await guardian.listPendingGuardians().catch((err) => err)

    expect(error.isNetworkError()).toBe(true)
  })
})
