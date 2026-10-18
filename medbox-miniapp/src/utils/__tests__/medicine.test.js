/**
 * 药品域纯函数测试（`feat/mp-medicine`，`src/utils/medicine.js`）。
 *
 * <p>覆盖：表单 → 请求体组装（trim / 省略空值 / ownerElderId 语义）、分页响应容错解析
 * （后端 `medicine-core` 未落地，字段名未定）、列表文案、角色写权限。
 */

import { describe, expect, it } from 'vitest'
import {
  buildMedicinePayload,
  canWriteMedicine,
  medicineLabel,
  medicineOwnerLabel,
  parseMedicinePage,
} from '@/utils/medicine.js'

describe('buildMedicinePayload', () => {
  it('新建：trim 后只带非空字段，无 medicineId 与 ownerElderId 即公共字典', () => {
    const payload = buildMedicinePayload({
      name: '  阿莫西林胶囊 ',
      spec: ' 0.25g×24粒 ',
      dosePerTime: '',
      unit: '   ',
      contraindication: null,
      storageCond: '遮光密封',
      ownerElderId: '',
    })

    expect(payload).toEqual({
      name: '阿莫西林胶囊',
      spec: '0.25g×24粒',
      storageCond: '遮光密封',
    })
    expect('medicineId' in payload).toBe(false)
    expect('ownerElderId' in payload).toBe(false)
  })

  it('编辑：带上 medicineId 与 ownerElderId', () => {
    const payload = buildMedicinePayload({
      medicineId: 'm-205',
      name: '阿莫西林胶囊',
      ownerElderId: 'u-1001',
    })

    expect(payload).toEqual({ medicineId: 'm-205', name: '阿莫西林胶囊', ownerElderId: 'u-1001' })
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
  it('规格与默认剂量拼成副标题', () => {
    expect(medicineLabel({ spec: '0.25g×24粒', dosePerTime: '1', unit: '粒' })).toBe(
      '0.25g×24粒 · 1粒',
    )
  })

  it('缺字段自动省略；全缺回退“药品档案”', () => {
    expect(medicineLabel({ dosePerTime: '1', unit: '片' })).toBe('1片')
    expect(medicineLabel({ spec: '0.1g' })).toBe('0.1g')
    expect(medicineLabel({})).toBe('药品档案')
    expect(medicineLabel(null)).toBe('药品档案')
  })
})

describe('medicineOwnerLabel', () => {
  it('公共 / 我的私有 / 他人私有', () => {
    expect(medicineOwnerLabel({})).toBe('公共')
    expect(medicineOwnerLabel({ ownerElderId: 'u-1001' }, 'u-1001')).toBe('我的')
    expect(medicineOwnerLabel({ ownerElderId: 'u-1002' }, 'u-1001')).toBe('私有 · u-1002')
    expect(medicineOwnerLabel(null, 'u-1001')).toBe('公共')
  })
})

describe('canWriteMedicine', () => {
  it('仅监护人可写（WRITE_MEDICINE 仅 FAMILY，老人只读）', () => {
    expect(canWriteMedicine('GUARDIAN')).toBe(true)
    expect(canWriteMedicine('ELDER')).toBe(false)
    expect(canWriteMedicine('')).toBe(false)
  })
})
