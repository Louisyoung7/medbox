import { describe, expect, it } from 'vitest'
import {
  ENV_METRICS,
  parseEnvRealtime,
  parseEnvHistory,
  toEnvThresholdView,
  envMetricStatus,
  computeEnvChartGeometry,
} from '@/utils/env.js'

describe('env —— parseEnvRealtime', () => {
  it('正常对象返回三项数值 + sampleTime', () => {
    const r = parseEnvRealtime({ temperature: 22, humidity: 55, lux: 300, sampleTime: 'T' })
    expect(r).toEqual({ temperature: 22, humidity: 55, lux: 300, sampleTime: 'T' })
  })
  it('非数字 / 缺失字段置 null，null 输入安全', () => {
    expect(parseEnvRealtime({ temperature: 'x', humidity: null })).toEqual({
      temperature: null,
      humidity: null,
      lux: null,
      sampleTime: null,
    })
    expect(parseEnvRealtime(null)).toEqual({
      temperature: null,
      humidity: null,
      lux: null,
      sampleTime: null,
    })
  })
})

describe('env —— parseEnvHistory', () => {
  it('兼容 {points:[...]}、裸数组、null、空', () => {
    expect(parseEnvHistory({ points: [{ temperature: 1 }] })).toEqual([
      { sampleTime: null, temperature: 1, humidity: null, lux: null },
    ])
    expect(parseEnvHistory([{ humidity: 2 }])).toEqual([
      { sampleTime: null, temperature: null, humidity: 2, lux: null },
    ])
    expect(parseEnvHistory(null)).toEqual([])
    expect(parseEnvHistory({})).toEqual([])
    expect(parseEnvHistory({ list: [] })).toEqual([])
  })
})

describe('env —— toEnvThresholdView（兼容扁平 + 嵌套）', () => {
  it('嵌套形状原样返回（temp/humidity/light）', () => {
    const v = toEnvThresholdView({ envThreshold: { temp: { min: 15, max: 30 }, humidity: { min: 30, max: 70 }, light: { min: 50, max: 500 } } })
    expect(v.temp).toEqual({ min: 15, max: 30 })
    expect(v.light).toEqual({ min: 50, max: 500 })
  })
  it('扁平形状 {tempMin,tempMax,humiMin,humiMax,luxMax} → 嵌套', () => {
    const v = toEnvThresholdView({ tempMin: 16, tempMax: 31, humiMin: 31, humiMax: 71, luxMax: 501 })
    expect(v.temp).toEqual({ min: 16, max: 31 })
    expect(v.humidity).toEqual({ min: 31, max: 71 })
    expect(v.light).toEqual({ min: 50, max: 501 })
  })
  it('null 输入 → 默认阈值（温度 15~30 / 湿度 30~70 / 光照 50~500）', () => {
    const v = toEnvThresholdView(null)
    expect(v.temp).toEqual({ min: 15, max: 30 })
    expect(v.humidity).toEqual({ min: 30, max: 70 })
    expect(v.light).toEqual({ min: 50, max: 500 })
  })
  it('扁平与嵌套混用时扁平不污染嵌套', () => {
    const v = toEnvThresholdView({
      envThreshold: { temp: { min: 15, max: 30 } },
      tempMin: 1,
      tempMax: 2,
    })
    // 嵌套优先（hasFlat 仅为 true 时套一层，但 envThreshold 已存在 → 以嵌套为准）
    expect(v.temp).toEqual({ min: 15, max: 30 })
  })
})

describe('env —— envMetricStatus', () => {
  it('null 值按正常（不高亮）', () => {
    expect(envMetricStatus(null, { min: 15, max: 30 })).toBe('ok')
  })
  it('偏低 / 偏高 / 正常', () => {
    expect(envMetricStatus(10, { min: 15, max: 30 })).toBe('low')
    expect(envMetricStatus(35, { min: 15, max: 30 })).toBe('high')
    expect(envMetricStatus(22, { min: 15, max: 30 })).toBe('ok')
  })
  it('只设单边阈值（仅 max）', () => {
    expect(envMetricStatus(600, { max: 500 })).toBe('high')
    expect(envMetricStatus(10, { max: 500 })).toBe('ok')
  })
})

describe('env —— computeEnvChartGeometry', () => {
  const points = [
    { sampleTime: 'T1', temperature: 20, humidity: 50, lux: 100 },
    { sampleTime: 'T2', temperature: 30, humidity: 60, lux: null },
    { sampleTime: 'T3', temperature: null, humidity: 70, lux: 300 },
  ]
  it('每个指标一段、line 点数 = 输入点数、null 值 y 为 null', () => {
    const g = computeEnvChartGeometry({ points, metrics: ['temperature', 'humidity', 'lux'], width: 300, height: 240 })
    expect(g.bands.length).toBe(3)
    expect(g.bands[0].line.length).toBe(3)
    const luxBand = g.bands[2]
    expect(luxBand.line[1].value).toBeNull() // 第二点 lux 为 null
    expect(luxBand.line[0].value).toBe(100)
  })
  it('阈值区间参与定义域，生成 max/min 虚线 y', () => {
    const g = computeEnvChartGeometry({
      points,
      metrics: ['temperature'],
      thresholds: { temp: { min: 18, max: 28 } },
      width: 300,
      height: 240,
    })
    const b = g.bands[0]
    expect(b.threshold.max).not.toBeNull()
    expect(b.threshold.min).not.toBeNull()
    expect(b.domainMin).toBeLessThanOrEqual(18)
    expect(b.domainMax).toBeGreaterThanOrEqual(28)
  })
  it('空点 → 空 bands line，不崩', () => {
    const g = computeEnvChartGeometry({ points: [], metrics: ['temperature'], width: 300, height: 240 })
    expect(g.bands[0].line).toEqual([])
  })
})
