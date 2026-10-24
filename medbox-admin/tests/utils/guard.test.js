import { describe, it, expect } from 'vitest'
import {
  isCleanable,
  isSafeViewRow,
  filterSafeView,
  confirmMatch,
  collectBatchTargets,
} from '../../src/utils/guard'

const deleted = { userId: 'u-1', status: 'DELETED', username: null, phone: null }
const testUser = { userId: 'u-2', status: 'ACTIVE', username: 'test01', phone: '13800001234' }
const realUser = { userId: 'u-3', status: 'ACTIVE', username: 'alice', phone: '13900001111' }

describe('guard 护栏纯函数', () => {
  it('isCleanable：ACTIVE 不可清理', () => {
    expect(isCleanable(deleted)).toBe(true)
    expect(isCleanable(testUser)).toBe(false)
    expect(isCleanable(realUser)).toBe(false)
  })

  it('isSafeViewRow：只放行 DELETED / test* / 1380000*', () => {
    expect(isSafeViewRow(deleted)).toBe(true)
    expect(isSafeViewRow(testUser)).toBe(true)
    expect(isSafeViewRow(realUser)).toBe(false)
  })

  it('filterSafeView：过滤掉真实活跃号', () => {
    expect(filterSafeView([deleted, testUser, realUser]).map((r) => r.userId)).toEqual(['u-1', 'u-2'])
  })

  it('confirmMatch：手输值与目标完全一致才放行（忽略首尾空格）', () => {
    expect(confirmMatch('u-2', 'u-2')).toBe(true)
    expect(confirmMatch(' u-2 ', 'u-2')).toBe(true)
    expect(confirmMatch('x', 'u-2')).toBe(false)
    expect(confirmMatch('', 'u-2')).toBe(false)
  })

  it('collectBatchTargets：排除 ACTIVE（含测试号）与真实号，仅留可清理行', () => {
    // testUser 虽 username=test01，但 status=ACTIVE，按护栏必须拒绝批量硬删（后端亦返 40902）
    expect(collectBatchTargets([deleted, testUser, realUser]).map((r) => r.userId)).toEqual(['u-1'])
  })
})
