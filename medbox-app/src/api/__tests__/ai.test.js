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
    scripts = [{ body: { code: 0, data: { elderId: 'u-1001', enabled: false, cadence: 'DAILY' } } }]
    const data = await ai.getInsightSchedule({ elderId: 'u-1001' })
    expect(sent[0].method).toBe('GET')
    expect(sent[0].url).toContain('/medbox/api/v1/ai/insight/schedule')
    expect(sent[0].url).toContain('elderId=u-1001')
    expect(data.enabled).toBe(false)
    expect(data.cadence).toBe('DAILY')
  })

  it('putInsightSchedule PUT 带 elderId(query) + cadence(body) + X-Request-Id', async () => {
    const { ai } = await loadApi()
    scripts = [{ body: { code: 0, data: { elderId: 'u-1001', enabled: true, cadence: 'WEEKLY' } } }]
    await ai.putInsightSchedule({ elderId: 'u-1001', cadence: 'WEEKLY' })
    expect(sent[0].method).toBe('PUT')
    expect(sent[0].url).toContain('/medbox/api/v1/ai/insight/schedule')
    expect(sent[0].url).toContain('elderId=u-1001')
    expect(sent[0].data.cadence).toBe('WEEKLY')
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

  it('transport 同步抛错且未产出 → 降级同步接口（不把异常抛给页面）', async () => {
    const { ai } = await loadApi()
    let err = null
    let done = null
    scripts = [{ body: { code: 0, data: { summary: 'ok' } } }]
    const transport = () => {
      throw new Error('transport boom')
    }
    ai.streamInsight(
      { elderId: 'u-1001' },
      { onDone: (d) => (done = d), onError: (e) => (err = e) },
      { transport },
    )
    await new Promise((r) => setTimeout(r, 0))
    expect(err).toBe(null)
    expect(done).not.toBeNull()
    expect(sent.some((s) => s.url.includes('/ai/insight') && !s.url.includes('/stream'))).toBe(true)
  })
})

describe('ai —— LLM 配置请求层（05 第 2.1 章 FP-8）', () => {
  it('getLlmConfig GET 且只回 apiKeyMask 不回明文', async () => {
    const { ai } = await loadApi()
    scripts = [{ body: { code: 0, data: { elderId: 'u-1001', provider: 'openai-compatible', model: 'gpt-4o-mini', apiKeyMask: 'sk****abcd', enabled: true } } }]
    const cfg = await ai.getLlmConfig({ elderId: 'u-1001' })
    expect(sent[0].method).toBe('GET')
    expect(sent[0].url).toContain('/medbox/api/v1/ai/config')
    expect(sent[0].url).toContain('elderId=u-1001')
    expect(cfg.apiKeyMask).toBe('sk****abcd')
    expect(cfg.enabled).toBe(true)
  })

  it('getLlmConfig 未配置(40401) 不抛错、返回空配置（真后端口径）', async () => {
    const { ai } = await loadApi()
    scripts = [{ body: { code: 40401, message: '尚未配置大模型' } }]
    const cfg = await ai.getLlmConfig({ elderId: 'u-1002' })
    expect(cfg.apiKeyMask).toBe('')
    expect(cfg.enabled).toBe(false)
  })

  it('putLlmConfig PUT 带 elderId(query) + 配置体，只回 apiKeyMask', async () => {
    const { ai } = await loadApi()
    scripts = [{ body: { code: 0, data: { elderId: 'u-1001', provider: 'openai-compatible', model: 'gpt-4o-mini', apiKeyMask: 'sk****abcd', enabled: true } } }]
    const cfg = await ai.putLlmConfig({ elderId: 'u-1001', provider: 'openai-compatible', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini', apiKey: 'sk-xxx', enabled: true })
    expect(sent[0].method).toBe('PUT')
    expect(sent[0].url).toContain('/medbox/api/v1/ai/config')
    expect(sent[0].url).toContain('elderId=u-1001')
    expect(sent[0].data.apiKey).toBe('sk-xxx')
    expect(sent[0].data.enabled).toBe(true)
    expect(cfg.apiKeyMask).toBe('sk****abcd')
  })

  it('testLlmConfig POST /ai/config/test 回 ok/message', async () => {
    const { ai } = await loadApi()
    scripts = [{ body: { code: 0, data: { ok: true, message: '连通正常' } } }]
    const res = await ai.testLlmConfig({ elderId: 'u-1001' })
    expect(sent[0].method).toBe('POST')
    expect(sent[0].url).toContain('/medbox/api/v1/ai/config/test')
    expect(res.ok).toBe(true)
    expect(res.message).toBe('连通正常')
  })

  it('deleteLlmConfig DELETE', async () => {
    const { ai } = await loadApi()
    scripts = [{ body: { code: 0, data: {} } }]
    await ai.deleteLlmConfig({ elderId: 'u-1001' })
    expect(sent[0].method).toBe('DELETE')
    expect(sent[0].url).toContain('/medbox/api/v1/ai/config')
  })

  it('配置写操作老人 → 40301（后端强校验）', async () => {
    const { ai, CODE } = await loadApi()
    scripts = [{ body: { code: 40301, message: '无权限执行该操作' } }]
    const err = await ai.putLlmConfig({ elderId: 'u-1001', provider: 'openai-compatible', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini', apiKey: 'sk-xxx' }).catch((e) => e)
    expect(err.code).toBe(CODE.FORBIDDEN)
  })

  it('配置校验缺 baseUrl → 40001', async () => {
    const { ai, CODE } = await loadApi()
    scripts = [{ body: { code: 40001, message: 'baseUrl 不合法' } }]
    const err = await ai.putLlmConfig({ elderId: 'u-1001', provider: 'openai-compatible', baseUrl: 'not-url', model: 'gpt-4o-mini', apiKey: 'sk-xxx' }).catch((e) => e)
    expect(err.code).toBe(CODE.BAD_REQUEST)
  })
})

describe('流式传输选择与降级（feat/app-runtime）', () => {
  /** 等请求层的 Promise 链跑完（同步降级只经过 Promise 微任务）。 */
  const flush = () => new Promise((r) => setTimeout(r, 0))

  it('App 有 plus.net.XMLHttpRequest 时优先 App 传输；否则用 fetch', async () => {
    const { ai } = await loadApi()
    expect(ai.resolveStreamTransport({ hasAppXhr: true, hasFetch: true })).toBeTypeOf('function')
    expect(ai.resolveStreamTransport({ hasFetch: true })).toBeTypeOf('function')
    expect(ai.resolveStreamTransport({})).toBe(null)
  })

  it('无任何流式能力 → 降级为同步 /ai/insight，不请求 /stream', async () => {
    vi.stubGlobal('fetch', undefined)
    const { ai } = await loadApi()
    let done = null
    scripts = [{ body: { code: 0, data: { summary: '降级结果' } } }]
    ai.streamInsight({ elderId: 'u-1001', type: 'ADHOC' }, { onDone: (d) => { done = d }, onError: () => {} })
    await flush()
    expect(done).toBeTruthy()
    expect(sent[0].url).toContain('/ai/insight')
    expect(sent[0].url).not.toContain('/stream')
  })

  it('首个 delta 前失败 → 降级同步接口', async () => {
    const { ai } = await loadApi()
    let done = null
    scripts = [{ body: { code: 0, data: { summary: '降级结果' } } }]
    const transport = (payload, handlers) => {
      handlers.onError({ code: -1, message: 'boom' })
      return { abort: () => {} }
    }
    ai.streamInsight({ elderId: 'u-1001', type: 'ADHOC' }, { onDone: (d) => { done = d }, onError: () => {} }, { transport })
    await flush()
    expect(done).toBeTruthy()
    expect(sent.some((s) => s.url.includes('/ai/insight') && !s.url.includes('/stream'))).toBe(true)
  })

  it('已输出 delta 后失败 → 不降级，直接报错（避免重复生成）', async () => {
    const { ai } = await loadApi()
    let done = null
    let error = null
    const transport = (payload, handlers) => {
      handlers.onDelta('前半段')
      handlers.onError({ code: -1, message: 'boom' })
      return { abort: () => {} }
    }
    ai.streamInsight({ elderId: 'u-1001', type: 'ADHOC' }, { onDone: (d) => { done = d }, onError: (e) => { error = e } }, { transport })
    await flush()
    expect(done).toBe(null)
    expect(error).toBeTruthy()
    expect(sent.length).toBe(0)
  })

  it('主动取消（onError(null)）不触发降级', async () => {
    const { ai } = await loadApi()
    let done = null
    const transport = (payload, handlers) => {
      handlers.onError(null)
      return { abort: () => {} }
    }
    ai.streamInsight({ elderId: 'u-1001', type: 'ADHOC' }, { onDone: (d) => { done = d }, onError: () => {} }, { transport })
    await flush()
    expect(done).toBe(null)
    expect(sent.length).toBe(0)
  })
})
