/**
 * 库存域纯函数测试（`feat/mp-stock`，`src/utils/stock.js`）。
 *
 * <p>覆盖：清单容错解析、药品 join 与回退、数量文案、
 * 录入 / 补药 payload 组装与数量校验。
 */

import { describe, expect, it } from 'vitest'
import {
  buildRestockPayload,
  buildStockPayload,
  joinStockMedicine,
  parseStocks,
  stockQuantityText,
  toMedicineMap,
} from '@/utils/stock.js'

describe('parseStocks', () => {
  it('裸数组原样返回', () => {
    const rows = [{ medicineId: 'm-1' }]
    expect(parseStocks(rows)).toBe(rows)
  })

  it('容错：{list} / {records} / {items} 包装', () => {
    expect(parseStocks({ list: [1] })).toEqual([1])
    expect(parseStocks({ records: [1] })).toEqual([1])
    expect(parseStocks({ items: [1] })).toEqual([1])
  })

  it('整体缺失 / 非数组按空数组处理', () => {
    expect(parseStocks(null)).toEqual([])
    expect(parseStocks(undefined)).toEqual([])
    expect(parseStocks({ foo: 1 })).toEqual([])
  })
})

describe('toMedicineMap', () => {
  it('按 medicineId 建映射，跳过无 id 条目', () => {
    const map = toMedicineMap([
      { medicineId: 'm-1', name: 'A' },
      { name: '无 id' },
      { medicineId: 'm-2', name: 'B' },
    ])
    expect(Object.keys(map)).toEqual(['m-1', 'm-2'])
    expect(map['m-1'].name).toBe('A')
  })

  it('非数组返回空映射', () => {
    expect(toMedicineMap(null)).toEqual({})
    expect(toMedicineMap(undefined)).toEqual({})
  })
})

describe('joinStockMedicine', () => {
  const map = toMedicineMap([{ medicineId: 'm-1', name: '阿莫西林', spec: '0.25g', unit: '粒' }])

  it('命中字典：带出名称 / 规格 / 单位', () => {
    const row = joinStockMedicine({ medicineId: 'm-1', quantity: 24 }, map)
    expect(row.name).toBe('阿莫西林')
    expect(row.spec).toBe('0.25g')
    expect(row.unit).toBe('粒')
    expect(row.quantity).toBe(24)
  })

  it('字典缺失：回退显示 medicineId，不抛错', () => {
    const row = joinStockMedicine({ medicineId: 'm-999', quantity: 1 }, map)
    expect(row.name).toBe('m-999')
    expect(row.spec).toBe('')
  })

  it('空 stock 兜底', () => {
    expect(joinStockMedicine(null, map).name).toBe('未知药品')
    expect(joinStockMedicine({}, null).name).toBe('未知药品')
  })
})

describe('stockQuantityText', () => {
  const map = toMedicineMap([{ medicineId: 'm-1', name: 'A', unit: '粒' }])

  it('有单位：库存 N 单位', () => {
    expect(stockQuantityText({ medicineId: 'm-1', quantity: 24 }, map)).toBe('库存 24 粒')
  })

  it('无单位 / 缺数量', () => {
    expect(stockQuantityText({ medicineId: 'm-2', quantity: 5 }, map)).toBe('库存 5')
    // 字典里没有 m-2：无单位；数量缺失显示 -（有单位时才带单位，见下一行）
    expect(stockQuantityText({ medicineId: 'm-2' }, map)).toBe('库存 -')
    expect(stockQuantityText({ medicineId: 'm-1' }, map)).toBe('库存 - 粒')
  })
})


describe('buildStockPayload', () => {
  it('合法：数量转整数、日期带上', () => {
    const result = buildStockPayload({ quantity: ' 24 ', productionDate: '2026-01-01', expiryDate: '2026-12-31' })
    expect(result).toEqual({
      ok: true,
      payload: { quantity: 24, productionDate: '2026-01-01', expiryDate: '2026-12-31' },
    })
  })

  it('空日期省略', () => {
    const result = buildStockPayload({ quantity: '1', productionDate: '', expiryDate: '  ' })
    expect(result).toEqual({ ok: true, payload: { quantity: 1 } })
  })

  it('数量非法：0 / 负数 / 小数 / 非数字 / 空', () => {
    for (const quantity of ['0', '-1', '1.5', 'abc', '', null, undefined]) {
      const result = buildStockPayload({ quantity })
      expect(result.ok).toBe(false)
      expect(result.error).toBe('请输入正确的数量（正整数）')
    }
  })

  it('空表单 / null 不入库', () => {
    expect(buildStockPayload(null).ok).toBe(false)
    expect(buildStockPayload({}).ok).toBe(false)
  })
})

describe('buildRestockPayload', () => {
  it('与录入同一组装规则（增量语义由调用点区分）', () => {
    const result = buildRestockPayload({ quantity: '10', expiryDate: '2027-06-30' })
    expect(result).toEqual({ ok: true, payload: { quantity: 10, expiryDate: '2027-06-30' } })
    expect(buildRestockPayload({ quantity: '0' }).ok).toBe(false)
  })
})
