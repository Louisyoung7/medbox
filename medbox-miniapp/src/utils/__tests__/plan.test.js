import { describe, it, expect } from 'vitest'
import {
  normalizeSlots,
  buildPlanPayload,
  cellLabel,
  planSlotsText,
  parsePlansPage,
  parseNextDoses,
  toDoseView,
  groupDosesByDay,
  syncStateLabel,
  canWritePlan,
  repeatRuleLabel,
  planStatusLabel,
} from '@/utils/plan.js'
import { ROLE } from '@/store/user.js'

const slot = (cellNo, time) => ({ cellNo, time })

describe('normalizeSlots（02d 规则 B：格位归一化）', () => {
  it('合法项去重并按格号升序', () => {
    expect(normalizeSlots([slot(2, '12:30'), slot(1, '08:00'), slot(3, '18:00')]))
      .toEqual([slot(1, '08:00'), slot(2, '12:30'), slot(3, '18:00')])
  })
  it('越界格号 / 重复格号 / 非法时间剔除', () => {
    expect(normalizeSlots([
      slot(4, '08:00'), slot(0, '09:00'), slot(1, '25:00'),
      slot(1, '8:00'), slot(1, '08:00'), slot(1, '09:00'), slot(2, '12:30'),
    ])).toEqual([slot(1, '08:00'), slot(2, '12:30')])
  })
  it('非数组 / 空数组 → 空', () => {
    expect(normalizeSlots(null)).toEqual([])
    expect(normalizeSlots([])).toEqual([])
  })
})

describe('buildPlanPayload', () => {
  it('只提交 slots，name trim、格号升序，不含药品字段', () => {
    const payload = buildPlanPayload({
      name: '  日常服药  ',
      slots: [slot(3, '18:00'), slot(1, '08:00'), slot(4, '09:00')],
      repeatRule: 'DAILY',
    }, 'u-1001')
    expect(payload).toEqual({
      elderId: 'u-1001',
      name: '日常服药',
      slots: [slot(1, '08:00'), slot(3, '18:00')],
      repeatRule: 'DAILY',
    })
    expect(payload.items).toBeUndefined()
    expect(payload.times).toBeUndefined()
  })
  it('name / repeatRule 为空则省略；slots 始终存在', () => {
    const payload = buildPlanPayload({ name: '', slots: [] }, 'u-1001')
    expect(payload).toEqual({ elderId: 'u-1001', slots: [] })
  })
})

describe('文案与权限', () => {
  it('cellLabel：1/2/3 对应早/午/晚，未知回退', () => {
    expect(cellLabel(1)).toBe('1 号格（早间）')
    expect(cellLabel(2)).toBe('2 号格（午间）')
    expect(cellLabel(3)).toBe('3 号格（晚间）')
    expect(cellLabel(4)).toBe('未识别格位')
    expect(cellLabel(null)).toBe('未识别格位')
  })
  it('planSlotsText：按格拼接，空则未设置', () => {
    expect(planSlotsText([slot(2, '12:30'), slot(1, '08:00')]))
      .toBe('1 号格 08:00、2 号格 12:30')
    expect(planSlotsText([])).toBe('未设置格位')
  })
  it('syncStateLabel：PENDING 待同步、未知原样、空不渲染文案', () => {
    expect(syncStateLabel('PENDING')).toBe('待同步')
    expect(syncStateLabel('SYNCED_TO_DEVICE')).toBe('已同步设备')
    expect(syncStateLabel('SYNC_FAILED')).toBe('同步失败')
    expect(syncStateLabel('CUSTOM')).toBe('CUSTOM')
    expect(syncStateLabel('')).toBe('')
  })
  it('repeatRuleLabel / planStatusLabel 已知值映射', () => {
    expect(repeatRuleLabel('DAILY')).toBe('每天')
    expect(planStatusLabel('DISABLED')).toBe('已停用')
  })
  it('canWritePlan 仅 GUARDIAN 可写', () => {
    expect(canWritePlan(ROLE.GUARDIAN)).toBe(true)
    expect(canWritePlan('ELDER')).toBe(false)
  })
})

describe('响应容错与视图', () => {
  it('parsePlansPage 兼容四种外壳', () => {
    expect(parsePlansPage([{ planId: 'p-1' }])).toEqual({ list: [{ planId: 'p-1' }], total: 1 })
    expect(parsePlansPage({ list: [], total: 3 })).toEqual({ list: [], total: 3 })
    expect(parsePlansPage({ records: [{}] }).list).toHaveLength(1)
    expect(parsePlansPage({ items: [{}] }).list).toHaveLength(1)
  })
  it('parseNextDoses 兼容 {doses} 与裸数组', () => {
    expect(parseNextDoses({ doses: [{ cellNo: 1 }] })).toEqual([{ cellNo: 1 }])
    expect(parseNextDoses([{ cellNo: 2 }])).toEqual([{ cellNo: 2 }])
    expect(parseNextDoses(null)).toEqual([])
  })
  it('toDoseView 带格号与格位文案，拆出 day/time，不含药品', () => {
    const view = toDoseView({
      cellNo: 2,
      scheduledTimeLocal: '2026-11-12T12:30',
      scheduledTimeUtc: '2026-11-12T04:30:00Z',
    })
    expect(view).toMatchObject({ day: '2026-11-12', time: '12:30', cellNo: 2, cellLabel: '2 号格（午间）' })
    expect(view.items).toBeUndefined()
  })
  it('toDoseView 缺格号兜底', () => {
    expect(toDoseView({}).cellNo).toBeNull()
    expect(toDoseView({}).cellLabel).toBe('未识别格位')
  })
  it('groupDosesByDay 按日期升序、组内按时间升序', () => {
    const groups = groupDosesByDay([
      { day: '2026-11-13', time: '08:00' },
      { day: '2026-11-12', time: '18:00' },
      { day: '2026-11-12', time: '08:00' },
    ])
    expect(groups.map((g) => g.day)).toEqual(['2026-11-12', '2026-11-13'])
    expect(groups[0].doses.map((d) => d.time)).toEqual(['08:00', '18:00'])
  })
})
