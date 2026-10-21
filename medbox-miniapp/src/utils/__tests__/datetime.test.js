import { describe, it, expect } from 'vitest'
import {
  parseUtc,
  formatDateTime,
  formatDate,
  formatRelative,
  daysUntil,
  expiryState,
} from '@/utils/datetime.js'

// 固定基准：2026-10-11T04:00:00Z === 上海 12:00（TZ=Asia/Shanghai 见 vitest.config.js）
const NOW = new Date('2026-10-11T04:00:00Z')

describe('parseUtc', () => {
  it('带 Z 按 UTC 解析', () => {
    expect(parseUtc('2026-10-11T04:00:00Z').getUTCHours()).toBe(4)
  })
  it('缺时区偏移的日期时间按 UTC 兜底', () => {
    expect(parseUtc('2026-10-11T04:00:00').getUTCHours()).toBe(4)
  })
  it('非法输入返回 null', () => {
    expect(parseUtc('')).toBeNull()
    expect(parseUtc(null)).toBeNull()
    expect(parseUtc('not-a-date')).toBeNull()
    expect(parseUtc(new Date('invalid'))).toBeNull()
  })
})

describe('formatDateTime / formatDate（本地输出，TZ=Asia/Shanghai）', () => {
  it('UTC 04:00 -> 本地 12:00', () => {
    expect(formatDateTime('2026-10-11T04:00:00Z')).toBe('2026-10-11 12:00')
  })
  it('自定义 pattern', () => {
    expect(formatDateTime('2026-10-11T04:05:09Z', 'YYYY/MM/DD HH:mm:ss')).toBe(
      '2026/10/11 12:05:09'
    )
  })
  it('解析失败返回 -', () => {
    expect(formatDateTime('xxx')).toBe('-')
  })
  it('formatDate 只到日', () => {
    expect(formatDate('2026-10-11T04:00:00Z')).toBe('2026-10-11')
  })
})

describe('formatRelative', () => {
  it('刚刚（<1 分钟）', () => {
    expect(formatRelative('2026-10-11T03:59:30Z', NOW)).toBe('刚刚')
  })
  it('N 分钟前', () => {
    expect(formatRelative('2026-10-11T03:30:00Z', NOW)).toBe('30分钟前')
  })
  it('N 小时前', () => {
    expect(formatRelative('2026-10-11T02:00:00Z', NOW)).toBe('2小时前')
  })
  it('昨天', () => {
    expect(formatRelative('2026-10-10T04:00:00Z', NOW)).toBe('昨天')
  })
  it('N 天前（<7）', () => {
    expect(formatRelative('2026-10-08T04:00:00Z', NOW)).toBe('3天前')
  })
  it('超过一周回退绝对日期', () => {
    expect(formatRelative('2026-10-01T04:00:00Z', NOW)).toBe('2026-10-01')
  })
  it('未来时间回退绝对', () => {
    expect(formatRelative('2026-10-12T04:00:00Z', NOW)).toBe('2026-10-12 12:00')
  })
})

describe('daysUntil / expiryState', () => {
  it('正数 = 未过期', () => {
    expect(daysUntil('2026-10-15', NOW)).toBe(4)
  })
  it('负数 = 已过期', () => {
    expect(daysUntil('2026-10-10', NOW)).toBe(-1)
  })
  it('当天 = 0', () => {
    expect(daysUntil('2026-10-11', NOW)).toBe(0)
  })
  it('expiryState ok / expiring / expired', () => {
    // 相对当前日期取值，避免硬编码日期随日历推移失效
    const future = (n) => {
      const d = new Date()
      d.setDate(d.getDate() + n)
      return d.toISOString().slice(0, 10)
    }
    expect(expiryState(future(60), 30)).toBe('ok')
    expect(expiryState(future(10), 30)).toBe('expiring')
    expect(expiryState(future(-10), 30)).toBe('expired')
  })
  it('解析失败 expiryState = unknown', () => {
    expect(expiryState('bad')).toBe('unknown')
  })
})
