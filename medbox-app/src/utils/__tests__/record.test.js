import { describe, it, expect } from 'vitest'
import {
  parseRecordsPage,
  canWriteRecord,
  recordStatusTags,
  recordTitle,
  recordCellText,
  formatPlanTime,
  formatActualTime,
} from '@/utils/record.js'
import { ROLE } from '@/store/user.js'

describe('parseRecordsPage', () => {
  it('兼容 {list}/{records}/{items} 与裸数组', () => {
    expect(parseRecordsPage([{ recordId: 'r-1' }])).toEqual({ list: [{ recordId: 'r-1' }], total: 1 })
    expect(parseRecordsPage({ list: [], total: 5 })).toEqual({ list: [], total: 5 })
    expect(parseRecordsPage({ records: [{}] }).list).toHaveLength(1)
    expect(parseRecordsPage({ items: [{}] }).list).toHaveLength(1)
    expect(parseRecordsPage(null)).toEqual({ list: [], total: 0 })
  })
})

describe('recordStatusTags（onTime 三态，无「已确认」派生态）', () => {
  it('true → 按时', () => {
    expect(recordStatusTags({ onTime: true })).toEqual([{ text: '按时', level: 'ok' }])
  })
  it('false → 未按时', () => {
    expect(recordStatusTags({ onTime: false })).toEqual([{ text: '未按时', level: 'warn' }])
  })
  it('null / 空对象 → 未判定；confirmedBy 不再追加标签', () => {
    expect(recordStatusTags({ onTime: null, confirmedBy: 'u-2001' }))
      .toEqual([{ text: '未判定', level: 'info' }])
    expect(recordStatusTags({})).toEqual([{ text: '未判定', level: 'info' }])
    expect(recordStatusTags(null)).toEqual([{ text: '未判定', level: 'info' }])
  })
})

describe('格位与标题文案', () => {
  it('recordCellText 按格号给文案', () => {
    expect(recordCellText({ cellNo: 1 })).toBe('1 号格（早间）')
    expect(recordCellText({ cellNo: 3 })).toBe('3 号格（晚间）')
    expect(recordCellText({})).toBe('未识别格位')
  })
  it('recordTitle 缺省回退', () => {
    expect(recordTitle({ planName: '日常服药' })).toBe('日常服药')
    expect(recordTitle({})).toBe('未命名计划')
  })
  it('时间本地化不抛错', () => {
    expect(typeof formatPlanTime({ planTime: '2026-11-12T00:00:00Z' })).toBe('string')
    expect(typeof formatActualTime({})).toBe('string')
  })
})

describe('canWriteRecord', () => {
  it('仅 GUARDIAN 可写', () => {
    expect(canWriteRecord(ROLE.GUARDIAN)).toBe(true)
    expect(canWriteRecord('ELDER')).toBe(false)
  })
})
