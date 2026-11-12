import { describe, expect, it } from 'vitest'
import {
  AI_TYPE,
  AI_TYPES,
  AI_PROVIDERS,
  MAX_RANGE_DAYS,
  SCHEDULE_FREQUENCIES,
  buildInsightRange,
  buildInsightPayload,
  initialState,
  applyInsightFrame,
  parseInsight,
  parseLlmConfig,
  validateLlmConfig,
  maskApiKey,
  isNoKeyError,
  aiErrorMessage,
} from '@/utils/ai.js'

describe('utils/ai —— 维度与范围', () => {
  it('AI_TYPE / AI_TYPES 五个维度且含 COMPREHENSIVE 默认', () => {
    expect(AI_TYPES).toHaveLength(5)
    expect(AI_TYPE.COMPREHENSIVE).toBe('COMPREHENSIVE')
    expect(AI_TYPES.map((t) => t.value)).toContain('ENV_CORRELATION')
  })

  it('库存维度按手工台账展示，不出现按服药推算消耗的旧口径', () => {
    const stock = AI_TYPES.find((t) => t.value === 'STOCK')
    expect(stock.label).toBe('库存盘点')
    expect(stock.desc).toContain('手工台账')
    expect(stock.desc).toContain('不推算消耗')
    expect(stock.desc).not.toMatch(/近 N 日消耗|预测/)
    const env = AI_TYPES.find((t) => t.value === 'ENV_CORRELATION')
    expect(env.desc).toContain('服药记录')
  })

  it('buildInsightRange 封顶 30 天，输出 ISO-8601 UTC', () => {
    const now = new Date('2026-11-01T00:00:00Z')
    const r = buildInsightRange(7, now)
    expect(r.from).toMatch(/Z$/)
    expect(r.to).toBe('2026-11-01T00:00:00.000Z')
    const r30 = buildInsightRange(999, now)
    const span = Date.parse(r30.to) - Date.parse(r30.from)
    expect(span).toBe(MAX_RANGE_DAYS * 24 * 60 * 60 * 1000)
  })

  it('buildInsightPayload 缺 elderId 抛错；deviceId 仅在提供时下发', () => {
    expect(() => buildInsightPayload({})).toThrow(/elderId/)
    const p1 = buildInsightPayload({ elderId: 'u-1001', type: 'STOCK', rangeDays: 14 })
    expect(p1.type).toBe('STOCK')
    expect(p1.range).toHaveProperty('from')
    const p2 = buildInsightPayload({ elderId: 'u-1001' })
    expect(p2.type).toBe('COMPREHENSIVE')
    expect(p2.deviceId).toBeUndefined()
  })
})

describe('utils/ai —— 流式 reducer', () => {
  it('initialState 全空', () => {
    expect(initialState()).toEqual({ content: '', citations: [], disclaimer: '', done: false })
  })

  it('delta 帧追加 content；done 帧落定 citations/disclaimer 并置 done', () => {
    let s = initialState()
    s = applyInsightFrame(s, { type: 'delta', content: '你好' })
    s = applyInsightFrame(s, { type: 'delta', content: '世界' })
    expect(s.content).toBe('你好世界')
    expect(s.done).toBe(false)
    s = applyInsightFrame(s, { type: 'done', citations: ['a'], disclaimer: '免责' })
    expect(s.content).toBe('你好世界')
    expect(s.citations).toEqual(['a'])
    expect(s.disclaimer).toBe('免责')
    expect(s.done).toBe(true)
  })

  it('null / 未知帧原样返回', () => {
    const s = initialState()
    expect(applyInsightFrame(s, null)).toBe(s)
    expect(applyInsightFrame(s, { type: 'ping' })).toBe(s)
  })
})

describe('utils/ai —— 解析与错误', () => {
  it('parseInsight 容错缺字段', () => {
    expect(parseInsight()).toEqual({ elderId: '', type: '', range: null, content: '', citations: [], disclaimer: '' })
    const d = parseInsight({ content: 'c', citations: ['x'], disclaimer: 'd', type: 'TREND' })
    expect(d.content).toBe('c')
    expect(d.citations).toEqual(['x'])
    expect(d.type).toBe('TREND')
  })

  it('isNoKeyError 仅识别 50310', () => {
    expect(isNoKeyError({ code: 50310 })).toBe(true)
    expect(isNoKeyError({ code: 40001 })).toBe(false)
    expect(isNoKeyError(null)).toBe(false)
  })

  it('aiErrorMessage 按错误码细分', () => {
    expect(aiErrorMessage({ code: 50310 })).toContain('暂未开通')
    expect(aiErrorMessage({ code: 40301 })).toContain('无权')
    expect(aiErrorMessage({ code: 40001 })).toContain('参数')
    expect(aiErrorMessage()).toContain('失败')
  })

  it('SCHEDULE_FREQUENCIES 含 DAILY / WEEKLY', () => {
    expect(SCHEDULE_FREQUENCIES.map((f) => f.value)).toEqual(['DAILY', 'WEEKLY'])
  })
})

describe('utils/ai —— LLM 配置纯函数（05 第 2.1 章 FP-8）', () => {
  it('AI_PROVIDERS 含 openai-compatible 且为 value/label 形态', () => {
    expect(AI_PROVIDERS.length).toBeGreaterThan(0)
    expect(AI_PROVIDERS.map((p) => p.value)).toContain('openai-compatible')
    expect(AI_PROVIDERS.every((p) => p.value && p.label)).toBe(true)
  })

  it('validateLlmConfig 必填校验', () => {
    const bad = validateLlmConfig({ provider: '', baseUrl: 'ftp://x', model: '', apiKey: '' })
    expect(bad.valid).toBe(false)
    expect(bad.errors.provider).toBeDefined()
    expect(bad.errors.baseUrl).toBeDefined()
    expect(bad.errors.model).toBeDefined()
    expect(bad.errors.apiKey).toBeDefined()
  })

  it('validateLlmConfig 合法通过；已配置时 apiKey 可空（requireApiKey=false）', () => {
    const good = validateLlmConfig({ provider: 'openai-compatible', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini', apiKey: 'sk-xxx' })
    expect(good.valid).toBe(true)
    const keepKey = validateLlmConfig({ provider: 'openai-compatible', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini', apiKey: '' }, { requireApiKey: false })
    expect(keepKey.valid).toBe(true)
    const needKey = validateLlmConfig({ provider: 'openai-compatible', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini', apiKey: '' })
    expect(needKey.valid).toBe(false)
    expect(needKey.errors.apiKey).toBeDefined()
  })

  it('maskApiKey 脱敏：保留前 2 + **** + 末 4；过短全打码；空返回空', () => {
    expect(maskApiKey('sk-abcdefghij')).toBe('sk****ghij')
    expect(maskApiKey('abc')).toBe('****')
    expect(maskApiKey('')).toBe('')
  })

  it('parseLlmConfig 容错脱敏形状（无明文 apiKey）', () => {
    const d = parseLlmConfig({ elderId: 'u-1001', provider: 'openai-compatible', model: 'gpt-4o-mini', apiKeyMask: 'sk****abcd', enabled: true })
    expect(d.apiKeyMask).toBe('sk****abcd')
    expect(d.enabled).toBe(true)
    expect('apiKey' in d).toBe(false)
    const empty = parseLlmConfig()
    expect(empty.apiKeyMask).toBe('')
    expect(empty.enabled).toBe(false)
  })
})
