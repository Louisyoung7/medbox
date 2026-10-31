/**
 * 依从性报告域纯函数测试（`feat/mp-report`，`src/utils/report.js`）。
 *
 * <p>覆盖：`parseAdherence` 容错兜底、`formatRate` 百分比、周期文案、
 * `buildChartPoints` 趋势点、导出权限判定。非判定型：只验证"缺字段兜底"，不重算业务规则。
 */

import { describe, expect, it } from 'vitest'
import {
  ADHERENCE_PERIODS,
  adherencePeriodLabel,
  buildChartPoints,
  canExportRecord,
  formatRate,
  parseAdherence,
} from '@/utils/report.js'
import { ROLE } from '@/store/user.js'

describe('parseAdherence', () => {
  it('完整响应原样透传 + onTimeRate 保留', () => {
    const src = {
      elderId: 'u-1001',
      period: 'week',
      windowStart: '2026-01-01T00:00:00Z',
      windowEnd: '2026-01-08T00:00:00Z',
      totalDoses: 4,
      onTimeCount: 2,
      lateCount: 2,
      unjudgedCount: 0,
      onTimeRate: 0.5,
      missCount: 2,
      dailyBreakdown: [{ date: '2026-01-01', onTimeRate: 1, totalDoses: 2, onTimeCount: 2, missCount: 1 }],
    }
    expect(parseAdherence(src)).toEqual(src)
  })

  it('totalDoses=0 → onTimeRate 兜底 0.0（不除零）', () => {
    const r = parseAdherence({ totalDoses: 0, onTimeCount: 0 })
    expect(r.totalDoses).toBe(0)
    expect(r.onTimeRate).toBe(0)
  })

  it('缺失 onTimeRate → 按 onTimeCount/totalDoses 兜底', () => {
    const r = parseAdherence({ totalDoses: 4, onTimeCount: 3 })
    expect(r.onTimeRate).toBeCloseTo(0.75, 5)
  })

  it('dailyBreakdown 缺省 []，单项同样兜底（缺失 onTimeRate 按 onTimeCount/dayTotal）', () => {
    const r = parseAdherence({
      totalDoses: 2,
      dailyBreakdown: [
        { date: '2026-01-01', totalDoses: 2, onTimeCount: 1 },
        { date: '2026-01-02' },
      ],
    })
    expect(Array.isArray(r.dailyBreakdown)).toBe(true)
    expect(r.dailyBreakdown).toHaveLength(2)
    expect(r.dailyBreakdown[0].onTimeRate).toBeCloseTo(0.5, 5)
    expect(r.dailyBreakdown[1].onTimeRate).toBe(0) // totalDoses=0 → 0
    expect(r.dailyBreakdown[1].totalDoses).toBe(0)
  })

  it('整体缺失 / 非对象按空兜底', () => {
    expect(parseAdherence(null).totalDoses).toBe(0)
    expect(parseAdherence(undefined).dailyBreakdown).toEqual([])
    expect(parseAdherence({}).onTimeRate).toBe(0)
  })

  it('计数缺省按 0，避免 NaN', () => {
    const r = parseAdherence({ totalDoses: 5 })
    expect(r.onTimeCount).toBe(0)
    expect(r.lateCount).toBe(0)
    expect(r.unjudgedCount).toBe(0)
    expect(r.missCount).toBe(0)
  })
})

describe('formatRate', () => {
  it('0.0~1.0 → 百分比（默认一位小数）', () => {
    expect(formatRate(0.5)).toBe('50.0%')
    expect(formatRate(0)).toBe('0.0%')
    expect(formatRate(1)).toBe('100.0%')
  })

  it('非数字兜底 0.0%', () => {
    expect(formatRate(NaN)).toBe('0.0%')
    expect(formatRate(undefined)).toBe('0.0%')
    expect(formatRate(null)).toBe('0.0%')
  })

  it('自定义小数位', () => {
    expect(formatRate(0.856, 0)).toBe('86%')
    expect(formatRate(0.856, 2)).toBe('85.60%')
  })
})

describe('adherencePeriodLabel', () => {
  it('day/week/month → 日/周/月，未知回退空串', () => {
    expect(adherencePeriodLabel('day')).toBe('日')
    expect(adherencePeriodLabel('week')).toBe('周')
    expect(adherencePeriodLabel('month')).toBe('月')
    expect(adherencePeriodLabel('quarter')).toBe('')
    expect(adherencePeriodLabel('')).toBe('')
  })

  it('ADHERENCE_PERIODS 恒量含三档', () => {
    expect(ADHERENCE_PERIODS).toEqual(['day', 'week', 'month'])
  })
})

describe('buildChartPoints', () => {
  it('按日期升序，rate = onTimeRate*100', () => {
    const pts = buildChartPoints([
      { date: '2026-01-03', onTimeRate: 0.5 },
      { date: '2026-01-01', onTimeRate: 1 },
      { date: '2026-01-02', onTimeRate: 0 },
    ])
    expect(pts).toEqual([
      { date: '2026-01-01', rate: 100 },
      { date: '2026-01-02', rate: 0 },
      { date: '2026-01-03', rate: 50 },
    ])
  })

  it('缺失 / 非数组 → []', () => {
    expect(buildChartPoints(null)).toEqual([])
    expect(buildChartPoints(undefined)).toEqual([])
    expect(buildChartPoints([])).toEqual([])
  })

  it('非数字 onTimeRate 兜底 0', () => {
    expect(buildChartPoints([{ date: '2026-01-01' }])).toEqual([{ date: '2026-01-01', rate: 0 }])
  })
})

describe('canExportRecord', () => {
  it('仅 GUARDIAN 可导出（EXPORT_RECORD；老人不可）', () => {
    expect(canExportRecord(ROLE.GUARDIAN)).toBe(true)
    expect(canExportRecord(ROLE.ELDER)).toBe(false)
    expect(canExportRecord('')).toBe(false)
  })
})
