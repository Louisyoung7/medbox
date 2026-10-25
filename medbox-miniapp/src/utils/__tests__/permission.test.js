import { describe, it, expect, afterEach } from 'vitest'
import { ROLE } from '@/store/user.js'
import { ENTRY_ROLES, canAccess, visibleEntries } from '@/utils/permission.js'

// 测试期间临时写入受限入口，结束后清理，避免污染模块级配置
afterEach(() => {
  delete ENTRY_ROLES.demo
  delete ENTRY_ROLES.guardOnly
})

describe('canAccess', () => {
  it('未在表登记的入口对所有角色（含未登录）可见', () => {
    expect(canAccess(ROLE.ELDER, 'home')).toBe(true)
    expect(canAccess(ROLE.GUARDIAN, 'home')).toBe(true)
    expect(canAccess('', 'home')).toBe(true)
  })
  it('白名单内角色可见、外角色不可见', () => {
    ENTRY_ROLES.demo = [ROLE.GUARDIAN]
    expect(canAccess(ROLE.GUARDIAN, 'demo')).toBe(true)
    expect(canAccess(ROLE.ELDER, 'demo')).toBe(false)
    expect(canAccess('', 'demo')).toBe(false)
  })
  it('未知角色不可访问受限入口', () => {
    ENTRY_ROLES.demo = [ROLE.ELDER]
    expect(canAccess('STRANGER', 'demo')).toBe(false)
  })
})

describe('visibleEntries', () => {
  it('按角色过滤入口并保持原序', () => {
    ENTRY_ROLES.guardOnly = [ROLE.GUARDIAN]
    const entries = [
      { key: 'home', title: '首页' },
      { key: 'guardOnly', title: '仅监护' },
    ]
    expect(visibleEntries(ROLE.ELDER, entries).map((e) => e.key)).toEqual(['home'])
    expect(visibleEntries(ROLE.GUARDIAN, entries).map((e) => e.key)).toEqual([
      'home',
      'guardOnly',
    ])
  })
  it('非数组返回空', () => {
    expect(visibleEntries(ROLE.ELDER, null)).toEqual([])
  })
})

describe('ENTRY_ROLES 已登记项（feat/mp-profile）', () => {
  it('alarmSettings「监护台」仅监护人', () => {
    expect(canAccess(ROLE.GUARDIAN, 'alarmSettings')).toBe(true)
    expect(canAccess(ROLE.ELDER, 'alarmSettings')).toBe(false)
    expect(canAccess('', 'alarmSettings')).toBe(false)
  })

  it('elderBindGuide「告诉监护人」仅老人', () => {
    expect(canAccess(ROLE.ELDER, 'elderBindGuide')).toBe(true)
    expect(canAccess(ROLE.GUARDIAN, 'elderBindGuide')).toBe(false)
    expect(canAccess('', 'elderBindGuide')).toBe(false)
  })
})
