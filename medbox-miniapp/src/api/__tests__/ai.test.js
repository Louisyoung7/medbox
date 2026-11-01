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
  const ai = await import('@/api/ai.js')
  return { ai, CODE: request.CODE }
}

describe('ai —— 同步洞察 / 周期设置 请求层', () => {
  it('requestInsight 组装 body，兑现解析后的洞察（POST 带 X-Request-Id）', async () => {
    const { ai } = await loadApi()
    scripts = [{ body: { code: 0, data: { content: '洞察文本', citations: ['med_record#1'], disclaimer: '免责' } } }]
    const insight = await ai.requestInsight({ elderId: 'u-1001', type: 'ADHERENCE', rangeDays: 14 })
    expect(sent[0].method).toBe('POST')
    expect(sent[0].url).toContain('/medbox/api/v1/ai/insight')
    expect(sent[0].data.elderId).toBe('u-1001')
    expect(sent[0].data.type).toBe('ADHERENCE')
    expect(sent[0].data.range).toHaveProperty('from')
    expect(sent[0].data.range).toHaveProperty('to')
    expect(sent[0].header['X-Request-Id']).toBeDefined()
    expect(insight.content).toBe('洞察文本')
    expect(insight.citations).toEqual(['med_record#1'])
  })

  it('requestInsight 未配 Key → 50310 兑现 AI_UNAVAILABLE', async () => {
    const { ai, CODE } = await loadApi()
    scripts = [{ body: { code: 50310, message: 'AI 助手暂未开通' } }]
    const err = await ai.requestInsight({ elderId: 'u-1002' }).catch((e) => e)
    expect(err.code).toBe(CODE.AI_UNAVAILABLE)
  })

  it('getInsightSchedule GET 带 elderId', async () => {
    const { ai } = await loadApi()
    scripts = [{ body: { code: 0, data: { elderId: 'u-1001', enabled: false, frequency: 'DAILY' } } }]
    const data = await ai.getInsightSchedule({ elderId: 'u-1001' })
    expect(sent[0].method).toBe('GET')
    expect(sent[0].url).toContain('/medbox/api/v1/ai/insight/schedule')
    expect(sent[0].url).toContain('elderId=u-1001')
    expect(data.enabled).toBe(false)
  })

  it('putInsightSchedule PUT 带 elderId(query) + frequency(body) + X-Request-Id', async () => {
    const { ai } = await loadApi()
    scripts = [{ body: { code: 0, data: { elderId: 'u-1001', enabled: true, frequency: 'WEEKLY' } } }]
    await ai.putInsightSchedule({ elderId: 'u-1001', frequency: 'WEEKLY' })
    expect(sent[0].method).toBe('PUT')
    expect(sent[0].url).toContain('/medbox/api/v1/ai/insight/schedule')
    expect(sent[0].url).toContain('elderId=u-1001')
    expect(sent[0].data.frequency).toBe('WEEKLY')
    expect(sent[0].header['X-Request-Id']).toBeDefined()
  })
})

describe('ai —— streamInsight 流式封装（注入 transport）', () => {
  it('逐帧回调 delta 拼接 + done 落定 citations/disclaimer，payload 正确', async () => {
    const { ai } = await loadApi()
    const deltas = []
    let done = null
    let capturedPayload = null
    const transport = (payload, handlers) => {
      capturedPayload = payload
      handlers.onDelta('你好')
      handlers.onDelta('世界')
      handlers.onDone({ content: '你好世界', citations: ['a', 'b'], disclaimer: '免责' })
      return { abort: () => {} }
    }
    const handle = ai.streamInsight(
      { elderId: 'u-1001', type: 'COMPREHENSIVE', rangeDays: 7 },
      { onDelta: (c) => deltas.push(c), onDone: (d) => (done = d) },
      { transport },
    )
    expect(deltas.join('')).toBe('你好世界')
    expect(done).not.toBeNull()
    expect(done.content).toBe('你好世界')
    expect(done.citations).toEqual(['a', 'b'])
    expect(capturedPayload.elderId).toBe('u-1001')
    expect(capturedPayload.type).toBe('COMPREHENSIVE')
    expect(capturedPayload.range).toHaveProperty('from')
    expect(handle).toHaveProperty('abort')
  })

  it('onError 在 transport 抛错时回调', async () => {
    const { ai } = await loadApi()
    let err = null
    const transport = () => {
      throw new Error('transport boom')
    }
    ai.streamInsight(
      { elderId: 'u-1001' },
      { onError: (e) => (err = e) },
      { transport },
    )
    expect(err).toBeInstanceOf(Error)
  })
})
