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
  const alarm = await import('@/api/alarm.js')
  return { alarm, CODE: request.CODE }
}

describe('alarm —— getAlarms 请求层', () => {
  it('GET /alarms 带 Bearer 与分页/筛选参数，兑现 {list,total}', async () => {
    const { alarm } = await loadApi()
    scripts = [{ body: { code: 0, data: { list: [{ alarmId: 'a-1', type: 'MISS' }], total: 1 } } }]
    const data = await alarm.getAlarms({ elderId: 'u-1001', type: 'MISS', readState: 'UNREAD', page: 1, size: 20 })
    expect(data.list.length).toBe(1)
    expect(data.total).toBe(1)
    expect(sent[0].method).toBe('GET')
    expect(sent[0].url).toContain('/medbox/api/v1/alarms')
    expect(sent[0].url).toContain('elderId=u-1001')
    expect(sent[0].url).toContain('type=MISS')
    expect(sent[0].url).toContain('readState=UNREAD')
    expect(sent[0].header.Authorization).toBe('Bearer TOKEN')
    expect(sent[0].header['X-Request-Id']).toBeUndefined()
  })

  it("类型/已读筛选传 'ALL' 时丢弃（不进 query）", async () => {
    const { alarm } = await loadApi()
    scripts = [{ body: { code: 0, data: { list: [], total: 0 } } }]
    await alarm.getAlarms({ elderId: 'u-1001', type: 'ALL', readState: 'ALL' })
    expect(sent[0].url).toContain('elderId=u-1001')
    expect(sent[0].url).not.toContain('type=ALL')
    expect(sent[0].url).not.toContain('readState=ALL')
  })

  it('越权 40301 兑现 FORBIDDEN', async () => {
    const { alarm, CODE } = await loadApi()
    scripts = [{ body: { code: 40301, message: '无权限访问该资源' } }]
    const err = await alarm.getAlarms({ elderId: 'u-9999' }).catch((e) => e)
    expect(err.code).toBe(CODE.FORBIDDEN)
  })
})

describe('alarm —— getUnreadCount / 标记已读 / 处理 请求层', () => {
  it('GET /alarms/unread-count 带 elderId，兑现 {count}', async () => {
    const { alarm } = await loadApi()
    scripts = [{ body: { code: 0, data: { count: 3 } } }]
    const data = await alarm.getUnreadCount({ elderId: 'u-1001' })
    expect(data.count).toBe(3)
    expect(sent[0].url).toContain('/medbox/api/v1/alarms/unread-count')
    expect(sent[0].url).toContain('elderId=u-1001')
  })

  it('PATCH /alarms/{id}/read 写操作带 X-Request-Id，兑现 null', async () => {
    const { alarm } = await loadApi()
    scripts = [{ body: { code: 0, data: null } }]
    const data = await alarm.markAlarmRead('a-1')
    expect(data).toBeNull()
    expect(sent[0].method).toBe('PATCH')
    expect(sent[0].url).toContain('/medbox/api/v1/alarms/a-1/read')
    expect(sent[0].header['X-Request-Id']).toBeDefined()
  })

  it('PATCH /alarms/{id}/handle 写操作带 X-Request-Id，兑现 null', async () => {
    const { alarm } = await loadApi()
    scripts = [{ body: { code: 0, data: null } }]
    const data = await alarm.handleAlarm('a-1')
    expect(data).toBeNull()
    expect(sent[0].method).toBe('PATCH')
    expect(sent[0].url).toContain('/medbox/api/v1/alarms/a-1/handle')
    expect(sent[0].header['X-Request-Id']).toBeDefined()
  })

  it('处理 40301（非监护人/护士）兑现 FORBIDDEN', async () => {
    const { alarm, CODE } = await loadApi()
    scripts = [{ body: { code: 40301, message: '无权限执行该操作' } }]
    const err = await alarm.handleAlarm('a-1').catch((e) => e)
    expect(err.code).toBe(CODE.FORBIDDEN)
  })

  it('标记已读 40401（告警不存在）兑现 NOT_FOUND', async () => {
    const { alarm, CODE } = await loadApi()
    scripts = [{ body: { code: 40401, message: '资源不存在' } }]
    const err = await alarm.markAlarmRead('a-x').catch((e) => e)
    expect(err.code).toBe(CODE.NOT_FOUND)
  })
})

describe('alarm —— 阈值设置 请求层', () => {
  it('GET /alarm-settings 带 elderId，兑现设置对象', async () => {
    const { alarm } = await loadApi()
    scripts = [
      { body: { code: 0, data: { elderId: 'u-1001', advanceMin: 10 } } },
    ]
    const data = await alarm.getAlarmSettings({ elderId: 'u-1001' })
    expect(data.advanceMin).toBe(10)
    expect(sent[0].url).toContain('/medbox/api/v1/alarm-settings')
    expect(sent[0].url).toContain('elderId=u-1001')
  })

  it('PUT /alarm-settings 带 elderId(query) + 阈值(body) + X-Request-Id', async () => {
    const { alarm } = await loadApi()
    scripts = [{ body: { code: 0, data: null } }]
    await alarm.saveAlarmSettings({ elderId: 'u-1001' }, { advanceMin: 20, envThreshold: { temp: { min: 15, max: 35 } } })
    expect(sent[0].method).toBe('PUT')
    expect(sent[0].url).toContain('/medbox/api/v1/alarm-settings')
    expect(sent[0].url).toContain('elderId=u-1001')
    expect(sent[0].data.advanceMin).toBe(20)
    expect(sent[0].data.envThreshold.temp.max).toBe(35)
    expect(sent[0].header['X-Request-Id']).toBeDefined()
  })

  it('保存 40301（非监护人）兑现 FORBIDDEN', async () => {
    const { alarm, CODE } = await loadApi()
    scripts = [{ body: { code: 40301, message: '无权限执行该操作' } }]
    const err = await alarm
      .saveAlarmSettings({ elderId: 'u-1001' }, { advanceMin: 5 })
      .catch((e) => e)
    expect(err.code).toBe(CODE.FORBIDDEN)
  })
})
