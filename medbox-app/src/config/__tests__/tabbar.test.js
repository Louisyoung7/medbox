import { describe, it, expect, vi } from 'vitest'

// tabbar.js → guard.js → request.js → config/index.js 在模块加载期调用 uni.getStorageSync，
// 必须在静态 import 求值前垫好 uni（vi.hoisted 先于所有 import 执行）。
vi.hoisted(() => {
  globalThis.uni = {
    getStorageSync: () => '',
    setStorageSync: () => {},
    removeStorageSync: () => {},
  }
})

import pagesJson from '@/pages.json'
import { TAB_ENTRIES, DRAWER_ENTRIES } from '@/config/tabbar.js'
import { NAV_ICONS } from '@/utils/nav-icon.js'
import { ROLE } from '@/store/user.js'
import { canAccess, visibleEntries } from '@/utils/permission.js'

const registered = pagesJson.pages.map((p) => '/' + p.path)

describe('底部栏 TAB_ENTRIES（feat/mp-nav-drawer）', () => {
  it('只保留 4 个主入口', () => {
    expect(TAB_ENTRIES.map((e) => e.key)).toEqual(['home', 'medicine', 'plan', 'record'])
  })

  it('路径已在 pages.json 注册、图标齐备、均为一级页', () => {
    TAB_ENTRIES.forEach((e) => {
      expect(registered).toContain(e.path)
      expect(NAV_ICONS[e.icon]).toBeTruthy()
      expect(e.tab).toBe(true)
    })
  })
})

describe('侧边栏 DRAWER_ENTRIES（feat/mp-nav-drawer）', () => {
  it('收纳设备 / AI / 告警 / 监护关系 / 我的', () => {
    expect(DRAWER_ENTRIES.map((e) => e.key)).toEqual([
      'device',
      'ai',
      'alarm',
      'guardian',
      'profile',
    ])
  })

  it('与底部入口不重复', () => {
    const tabKeys = TAB_ENTRIES.map((e) => e.key)
    DRAWER_ENTRIES.forEach((e) => expect(tabKeys).not.toContain(e.key))
  })

  it('路径已在 pages.json 注册、图标齐备', () => {
    DRAWER_ENTRIES.forEach((e) => {
      expect(registered).toContain(e.path)
      expect(NAV_ICONS[e.icon]).toBeTruthy()
    })
  })

  it('一级页用 reLaunch、二级页用 navigateTo', () => {
    const byKey = Object.fromEntries(DRAWER_ENTRIES.map((e) => [e.key, e]))
    expect(byKey.device.tab).toBe(true)
    expect(byKey.ai.tab).toBe(true)
    expect(byKey.profile.tab).toBe(true)
    expect(byKey.alarm.tab).toBeFalsy()
    expect(byKey.guardian.tab).toBeFalsy()
  })

  it('AI / 监护关系对老人与监护人均可见（未登记 ENTRY_ROLES）', () => {
    expect(canAccess(ROLE.ELDER, 'ai')).toBe(true)
    expect(canAccess(ROLE.GUARDIAN, 'ai')).toBe(true)
    expect(canAccess(ROLE.ELDER, 'guardian')).toBe(true)
    expect(visibleEntries(ROLE.ELDER, DRAWER_ENTRIES).map((e) => e.key)).toContain('ai')
    expect(visibleEntries(ROLE.GUARDIAN, DRAWER_ENTRIES).map((e) => e.key)).toContain('ai')
  })
})
