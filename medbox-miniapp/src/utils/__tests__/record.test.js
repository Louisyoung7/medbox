/**
 * 记录域纯函数测试（`feat/mp-record`，`src/utils/record.js`）。
 *
 * <p>覆盖：列表分页容错解析、按时 / 已确认标签映射、写入口角色判定、
 * 列表项标题与药品文案、时间本地化委托（具体时区转换见 datetime.test.js）。
 */

import { describe, expect, it } from 'vitest'
import {
  canWriteRecord,
  formatActualTime,
  formatPlanTime,
  parseRecordsPage,
  recordMedicineText,
  recordStatusTags,
  recordTitle,
} from '@/utils/record.js'
import { ROLE } from '@/store/user.js'

describe('parseRecordsPage', () => {
  it('裸数组原样包装为 {list,total}', () => {
    const rows = [{ recordId: 'r-1' }]
    expect(parseRecordsPage(rows)).toEqual({ list: rows, total: 1 })
  })

  it('容错：{list} / {records} / {items} 包装，total 优先取字段', () => {
    expect(parseRecordsPage({ list: [1, 2] })).toEqual({ list: [1, 2], total: 2 })
    expect(parseRecordsPage({ records: [1] })).toEqual({ list: [1], total: 1 })
    expect(parseRecordsPage({ items: [1] })).toEqual({ list: [1], total: 1 })
    expect(parseRecordsPage({ list: [1, 2, 3], total: 99 })).toEqual({ list: [1, 2, 3], total: 99 })
  })

  it('整体缺失 / 非对象按空数组处理', () => {
    expect(parseRecordsPage(null)).toEqual({ list: [], total: 0 })
    expect(parseRecordsPage(undefined)).toEqual({ list: [], total: 0 })
    expect(parseRecordsPage({ foo: 1 })).toEqual({ list: [], total: 0 })
  })
})

describe('recordStatusTags', () => {
  it('onTime=true → 按时（ok）', () => {
    expect(recordStatusTags({ onTime: true })).toEqual([{ text: '按时', level: 'ok' }])
  })

  it('onTime=false → 未按时（warn）', () => {
    expect(recordStatusTags({ onTime: false })).toEqual([
      { text: '未按时', level: 'warn' },
    ])
  })

  it('已确认：追加"已确认"（info）', () => {
    expect(recordStatusTags({ onTime: true, confirmedBy: 'u-2001' })).toEqual([
      { text: '按时', level: 'ok' },
      { text: '已确认', level: 'info' },
    ])
  })

  it('空对象 / null 兜底为未按时', () => {
    expect(recordStatusTags({})).toEqual([{ text: '未按时', level: 'warn' }])
    expect(recordStatusTags(null)).toEqual([{ text: '未按时', level: 'warn' }])
  })

  it('待确认（UNCERTAIN）置顶高亮 pending 标签', () => {
    expect(recordStatusTags({ faceResult: 'UNCERTAIN', onTime: true })).toEqual([
      { text: '待确认', level: 'pending' },
      { text: '按时', level: 'ok' },
    ])
  })

  it('pendingConfirm=true 同样判待确认', () => {
    expect(recordStatusTags({ pendingConfirm: true, onTime: false })).toEqual([
      { text: '待确认', level: 'pending' },
      { text: '未按时', level: 'warn' },
    ])
  })

  it('已确认 + 待确认：两个标签并存', () => {
    expect(recordStatusTags({ pendingConfirm: true, onTime: true, confirmedBy: 'u-2001' })).toEqual([
      { text: '待确认', level: 'pending' },
      { text: '按时', level: 'ok' },
      { text: '已确认', level: 'info' },
    ])
  })
})

describe('canWriteRecord', () => {
  it('仅 GUARDIAN 可写', () => {
    expect(canWriteRecord(ROLE.GUARDIAN)).toBe(true)
    expect(canWriteRecord(ROLE.ELDER)).toBe(false)
    expect(canWriteRecord('')).toBe(false)
  })
})

describe('recordTitle', () => {
  it('取计划名，缺省回退', () => {
    expect(recordTitle({ planName: '早餐后' })).toBe('早餐后')
    expect(recordTitle({})).toBe('未命名计划')
    expect(recordTitle(null)).toBe('未命名计划')
  })
})

describe('recordMedicineText', () => {
  it('药品名 + 剂量单位', () => {
    expect(recordMedicineText({ medicineName: '阿莫西林', medicineDose: '1', medicineUnit: '粒' })).toBe(
      '阿莫西林 1粒',
    )
    expect(recordMedicineText({ medicineName: '阿莫西林' })).toBe('阿莫西林')
  })

  it('缺名称回退 medicineId / 未知', () => {
    expect(recordMedicineText({ medicineId: 'm-201' })).toBe('m-201')
    expect(recordMedicineText({})).toBe('未知药品')
    expect(recordMedicineText(null)).toBe('未知药品')
  })
})

describe('时间本地化', () => {
  it('合法 UTC 转为本地日期串（含日期，时区无关）', () => {
    expect(formatPlanTime({ planTime: '2026-01-01T00:00:00Z' })).toContain('2026-01-01')
    expect(formatActualTime({ actualTime: '2026-06-15T12:34:56Z' })).toContain('2026-06-15')
  })

  it('缺失返回 -', () => {
    expect(formatPlanTime({})).toBe('-')
    expect(formatActualTime({ actualTime: null })).toBe('-')
    expect(formatPlanTime(null)).toBe('-')
  })
})
