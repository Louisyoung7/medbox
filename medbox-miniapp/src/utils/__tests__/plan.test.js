/**
 * 计划域纯函数测试（`feat/mp-plan`，规则表 A/B/C 的可单测落地，对应 medbox-spec/02 第 6 章、
 * 06 的 2.6 / 2.6.1）。
 *
 * 规则表索引（确认后同步沉淀进 02 第 6 章）：
 * - A 时间：`normalizeTimes`（格式校验 + 去重 + 升序；**设备本地墙钟，不换 UTC**）
 * - B items：`buildPlanPayload`（medicineId 必填、dose/unit/note 空串省略、按序组装）
 * - C 过期拦截：`pickableMedicines`（无库存 / 已过期置灰，跨设备聚合）
 * - D 启停 / E 权限 / 文案：`canWritePlan` / `planTimesText` / `planItemsText`
 */

import { describe, expect, it } from 'vitest'
import { ROLE } from '@/store/user.js'
import { expiryTag } from '@/utils/stock.js'
import {
  buildPlanPayload,
  canWritePlan,
  normalizeTimes,
  parsePlansPage,
  parseNextDoses,
  pickableMedicines,
  planItemsText,
  planStatusLabel,
  planTimesText,
  repeatRuleLabel,
  syncStateLabel,
  toDoseView,
  groupDosesByDay,
} from '@/utils/plan.js'

/** 相对今天偏移 N 天的 YYYY-MM-DD（临期断言的确定性输入）。 */
function dateOffset(days) {
  const d = new Date()
  d.setDate(d.getDate() + days)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

describe('normalizeTimes（规则 A：设备本地墙钟，校验 + 去重 + 升序）', () => {
  it('合法时间：去重后按 HH:mm 升序', () => {
    expect(normalizeTimes(['20:00', '08:00', '08:00', '12:30'])).toEqual(['08:00', '12:30', '20:00'])
  })

  it('非法格式剔除（25:00 / 8:00 / 08:0 / 空 / null）', () => {
    expect(normalizeTimes(['25:00', '8:00', '08:0', '', null, '09:15'])).toEqual(['09:15'])
  })

  it('边界值：00:00 与 23:59 合法；非数组按空处理', () => {
    expect(normalizeTimes(['23:59', '00:00'])).toEqual(['00:00', '23:59'])
    expect(normalizeTimes(undefined)).toEqual([])
    expect(normalizeTimes(null)).toEqual([])
    expect(normalizeTimes('08:00')).toEqual([])
  })
})

describe('buildPlanPayload（规则 B：items 组装，空值省略）', () => {
  const form = {
    name: '  早餐后 ',
    times: ['20:00', '08:00'],
    repeatRule: 'DAILY',
    items: [
      { medicineId: 'm-201', dose: '1', unit: '粒', note: '' },
      { medicineId: 'm-205', dose: '', unit: '', note: '餐后' },
      { medicineId: '', dose: '1' },
    ],
  }

  it('name trim、times 归一、dose/unit/note 空串省略、无 medicineId 项剔除', () => {
    const payload = buildPlanPayload(form, 'u-1001')
    expect(payload).toEqual({
      elderId: 'u-1001',
      name: '早餐后',
      times: ['08:00', '20:00'],
      repeatRule: 'DAILY',
      items: [
        { medicineId: 'm-201', dose: '1', unit: '粒' },
        { medicineId: 'm-205', note: '餐后' },
      ],
    })
  })

  it('不传 missAlarmAfterMin / alarmRule（本期范围决策：走后端默认与老人级配置）', () => {
    const payload = buildPlanPayload(form, 'u-1001')
    expect('missAlarmAfterMin' in payload).toBe(false)
    expect('alarmRule' in payload).toBe(false)
  })

  it('repeatRule 为空则省略；items 非数组按空处理', () => {
    const payload = buildPlanPayload({ name: 'x', times: ['08:00'], repeatRule: '', items: null }, 'u-1001')
    expect('repeatRule' in payload).toBe(false)
    expect(payload.items).toEqual([])
  })
})

describe('parsePlansPage（后端未定稿，字段名容错）', () => {
  it('{list,total} 标准形状', () => {
    expect(parsePlansPage({ list: [{ planId: 'p-1' }], total: 3 })).toEqual({ list: [{ planId: 'p-1' }], total: 3 })
  })

  it('容错 records / items / 裸数组；缺失按空页', () => {
    expect(parsePlansPage({ records: [1], total: 1 }).list).toEqual([1])
    expect(parsePlansPage({ items: [1], total: 1 }).list).toEqual([1])
    expect(parsePlansPage([1, 2])).toEqual({ list: [1, 2], total: 2 })
    expect(parsePlansPage(null)).toEqual({ list: [], total: 0 })
    expect(parsePlansPage({ foo: 1 })).toEqual({ list: [], total: 0 })
  })
})

describe('pickableMedicines（规则 C：无库存 / 已过期拦截，跨设备聚合）', () => {
  const medicines = [
    { medicineId: 'm-ok', name: '正常药' },
    { medicineId: 'm-expired', name: '过期药' },
    { medicineId: 'm-none', name: '无库存药' },
    { medicineId: 'm-mixed', name: '一过期一正常' },
  ]
  const stocks = [
    { medicineId: 'm-ok', expiryDate: dateOffset(60) },
    { medicineId: 'm-expired', expiryDate: dateOffset(-5) },
    { medicineId: 'm-mixed', expiryDate: dateOffset(-5) },
    { medicineId: 'm-mixed', expiryDate: dateOffset(10) },
  ]

  it('有未过期库存 → 可选；全过期 → 拦截(expired)；无记录 → 拦截(no-stock)', () => {
    const { selectable, blocked } = pickableMedicines(medicines, stocks)
    expect(selectable.map((s) => s.medicine.medicineId)).toEqual(['m-ok', 'm-mixed'])
    expect(blocked).toEqual([
      { medicine: medicines[1], reason: 'expired' },
      { medicine: medicines[2], reason: 'no-stock' },
    ])
  })

  it('库存数据缺失（空数组）→ 全部 no-stock，页面按规则不硬拦截', () => {
    const { selectable, blocked } = pickableMedicines(medicines, [])
    expect(selectable).toHaveLength(0)
    expect(blocked.every((b) => b.reason === 'no-stock')).toBe(true)
  })
})

describe('文案与权限', () => {
  it('planTimesText', () => {
    expect(planTimesText(['20:00', '08:00'])).toBe('08:00、20:00')
    expect(planTimesText([])).toBe('未设置时间')
  })

  it('planItemsText：join 药品名 + 剂量，缺省回退 medicineId', () => {
    const map = { 'm-201': { name: '阿莫西林' }, 'm-205': { name: '布洛芬' } }
    const text = planItemsText(
      [
        { medicineId: 'm-201', dose: '1', unit: '粒' },
        { medicineId: 'm-205' },
        { medicineId: 'm-999', dose: '2', unit: '片' },
      ],
      map,
    )
    expect(text).toBe('阿莫西林 1粒 + 布洛芬 + m-999 2片')
    expect(planItemsText([], map)).toBe('未添加药品')
  })

  it('canWritePlan：仅监护人（WRITE_PLAN 仅 FAMILY+DOCTOR，GUARDIAN 代理）', () => {
    expect(canWritePlan(ROLE.GUARDIAN)).toBe(true)
    expect(canWritePlan(ROLE.ELDER)).toBe(false)
    expect(canWritePlan('')).toBe(false)
  })

  it('repeatRuleLabel / planStatusLabel / syncStateLabel：已知值映射，未知原样', () => {
    expect(repeatRuleLabel('DAILY')).toBe('每天')
    expect(repeatRuleLabel('WEEKLY')).toBe('每周')
    expect(repeatRuleLabel('MONTHLY')).toBe('MONTHLY')
    expect(repeatRuleLabel('')).toBe('未设置周期')
    expect(planStatusLabel('ENABLED')).toBe('启用中')
    expect(planStatusLabel('DISABLED')).toBe('已停用')
    expect(syncStateLabel('SYNCED_TO_DEVICE')).toBe('已同步设备')
    expect(syncStateLabel('SYNCING')).toBe('SYNCING')
    expect(syncStateLabel('')).toBe('')
  })

  it('expiryTag 复用校验（规则 C 的底层判定）', () => {
    expect(expiryTag(dateOffset(60)).state).toBe('ok')
    expect(expiryTag(dateOffset(10)).state).toBe('expiring')
    expect(expiryTag(dateOffset(-1)).state).toBe('expired')
    expect(expiryTag('').state).toBe('unknown')
  })
})

describe('next-doses 解析（feat/mp-plan-timeline）', () => {
  it('parseNextDoses：兼容 {doses} / 裸数组 / 空', () => {
    expect(parseNextDoses({ doses: [{ a: 1 }] })).toEqual([{ a: 1 }])
    expect(parseNextDoses([{ a: 1 }, { a: 2 }])).toEqual([{ a: 1 }, { a: 2 }])
    expect(parseNextDoses(null)).toEqual([])
    expect(parseNextDoses({})).toEqual([])
    expect(parseNextDoses({ list: [] })).toEqual([])
  })

  it('toDoseView：拆本地日期与 HH:mm，items 缺字段兜底', () => {
    const view = toDoseView({
      scheduledTimeLocal: '2026-10-27T08:00:00',
      items: [
        { medicineId: 'm-201', medicineName: '阿莫西林', dose: '1', unit: '粒' },
        { medicineId: 'm-205' },
      ],
    })
    expect(view.day).toBe('2026-10-27')
    expect(view.time).toBe('08:00')
    expect(view.items[0].text).toBe('阿莫西林 1粒')
    expect(view.items[1].text).toBe('m-205')
  })

  it('toDoseView：缺 scheduledTimeLocal 时 day/time 为空串', () => {
    const view = toDoseView({ items: [] })
    expect(view.day).toBe('')
    expect(view.time).toBe('')
    expect(view.items).toEqual([])
  })

  it('groupDosesByDay：按本地日期升序分组，组内按时间升序', () => {
    const groups = groupDosesByDay([
      toDoseView({ scheduledTimeLocal: '2026-10-27T20:00:00' }),
      toDoseView({ scheduledTimeLocal: '2026-10-27T08:00:00' }),
      toDoseView({ scheduledTimeLocal: '2026-10-26T09:00:00' }),
    ])
    expect(groups.map((g) => g.day)).toEqual(['2026-10-26', '2026-10-27'])
    expect(groups[1].doses.map((d) => d.time)).toEqual(['08:00', '20:00'])
  })

  it('groupDosesByDay：空输入返回空数组', () => {
    expect(groupDosesByDay([])).toEqual([])
    expect(groupDosesByDay(null)).toEqual([])
  })
})
