/**
 * 药品域纯函数测试（`feat/mp-medicine`，`src/utils/medicine.js`）。
 *
 * <p>覆盖：表单 → 请求体组装（trim / 省略空值 / ownerElderId 必填语义）、分页响应容错解析
 * （后端 `medicine-core` 未落地，字段名未定）、列表文案、角色写权限。
 */

import { describe, expect, it } from 'vitest'
import {
  buildMedicinePayload,
  canWriteMedicine,
  medicineLabel,
  parseMedicinePage,
} from '@/utils/medicine.js'

describe('buildMedicinePayload', () => {
  it('新建：trim 后只带非空字段；ownerElderId 为空时省略（页面必填校验 + 后端 40001 兜底）', () => {
    const payload = buildMedicinePayload({
      name: '  阿莫西林胶囊 ',
      dosePerTime: '',
      unit: '   ',
      description: null,
      storageCond: '遮光密封',
      ownerElderId: '',
    })

    expect(payload).toEqual({
      name: '阿莫西林胶囊',
      storageCond: '遮光密封',
    })
    expect('medicineId' in payload).toBe(false)
    expect('ownerElderId' in payload).toBe(false)
    expect('spec' in payload).toBe(false) // 规格字段前端已不再下发
  })

  it('新建：带归属老人时下发 ownerElderId（药品必归属老人）', () => {
    expect(buildMedicinePayload({ name: '阿莫西林胶囊', ownerElderId: '  u-1001  ' })).toEqual({
      name: '阿莫西林胶囊',
      ownerElderId: 'u-1001',
    })
  })

  it('编辑：带上 medicineId 与 ownerElderId', () => {
    const payload = buildMedicinePayload({
      medicineId: 'm-205',
      name: '阿莫西林胶囊',
      ownerElderId: 'u-1001',
    })

    expect(payload).toEqual({ medicineId: 'm-205', name: '阿莫西林胶囊', ownerElderId: 'u-1001' })
  })

  it('新建：药品说明 trim 后下发（AI 用药安全洞察的唯一事实来源）', () => {
    expect(buildMedicinePayload({
      name: '阿莫西林胶囊',
      description: '  主要成分：阿莫西林。禁忌：对青霉素过敏者禁用。  ',
      ownerElderId: 'u-1001',
    })).toEqual({
      name: '阿莫西林胶囊',
      description: '主要成分：阿莫西林。禁忌：对青霉素过敏者禁用。',
      ownerElderId: 'u-1001',
    })
  })

  it('name 为空时省略该字段（由页面必填校验先拦，40001 兜底）', () => {
    expect(buildMedicinePayload({ name: '   ' })).toEqual({})
    expect(buildMedicinePayload({})).toEqual({})
    expect(buildMedicinePayload(null)).toEqual({})
  })
})

describe('parseMedicinePage', () => {
  it('标准形状：list + total', () => {
    const page = parseMedicinePage({ list: [{ medicineId: 'm-1' }], total: 42 })

    expect(page).toEqual({ list: [{ medicineId: 'm-1' }], total: 42 })
  })

  it('容错：records / items 命名回退（后端未定稿）', () => {
    expect(parseMedicinePage({ records: [{ medicineId: 'm-1' }], total: 1 }).list).toHaveLength(1)
    expect(parseMedicinePage({ items: [{ medicineId: 'm-1' }], total: 1 }).list).toHaveLength(1)
  })

  it('容错：缺 total 时按本页长度；整体缺失按空页', () => {
    expect(parseMedicinePage({ list: [1, 2] })).toEqual({ list: [1, 2], total: 2 })
    expect(parseMedicinePage(null)).toEqual({ list: [], total: 0 })
    expect(parseMedicinePage(undefined)).toEqual({ list: [], total: 0 })
    expect(parseMedicinePage({ foo: 1 })).toEqual({ list: [], total: 0 })
  })
})

describe('medicineLabel', () => {
  it('默认剂量+单位拼成副标题（规格字段前端已移除，不参与展示）', () => {
    expect(medicineLabel({ dosePerTime: '1', unit: '粒' })).toBe('1粒')
    expect(medicineLabel({ spec: '0.25g×24粒', dosePerTime: '1', unit: '粒' })).toBe('1粒')
  })

  it('缺字段自动省略；全缺回退“药品档案”', () => {
    expect(medicineLabel({ dosePerTime: '1' })).toBe('1')
    expect(medicineLabel({})).toBe('药品档案')
    expect(medicineLabel(null)).toBe('药品档案')
  })
})

describe('canWriteMedicine', () => {
  it('仅监护人可写（WRITE_MEDICINE 仅 GUARDIAN，老人只读）', () => {
    expect(canWriteMedicine('GUARDIAN')).toBe(true)
    expect(canWriteMedicine('ELDER')).toBe(false)
    expect(canWriteMedicine('')).toBe(false)
  })
})
