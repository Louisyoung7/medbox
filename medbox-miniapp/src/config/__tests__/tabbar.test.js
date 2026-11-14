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
import { TAB_ENTRIES } from '@/config/tabbar.js'
import { ROLE } from '@/store/user.js'
import { canAccess, visibleEntries } from '@/utils/permission.js'

describe('TAB_ENTRIES（feat/mp-ai-tab）', () => {
  it('包含 AI 一级入口，路径与图标齐备', () => {
    const ai = TAB_ENTRIES.find((e) => e.key === 'ai')
    expect(ai).toBeDefined()
    expect(ai.path).toBe('/pages/ai/insight')
    expect(ai.icon).toBe('ai')
    expect(ai.title).toBeTruthy()
  })

  it('AI 入口位于「记录」与「我的」之间（第 6 位）', () => {
    const keys = TAB_ENTRIES.map((e) => e.key)
    expect(keys.indexOf('ai')).toBe(keys.indexOf('record') + 1)
    expect(keys.indexOf('ai')).toBe(keys.indexOf('profile') - 1)
  })

  it('AI 入口路径已在 pages.json 注册', () => {
    const registered = pagesJson.pages.map((p) => '/' + p.path)
    TAB_ENTRIES.forEach((e) => {
      expect(registered).toContain(e.path)
    })
  })

  it('AI 入口未登记 ENTRY_ROLES，老人 / 监护人均可见（仅后端鉴权口径）', () => {
    expect(canAccess(ROLE.ELDER, 'ai')).toBe(true)
    expect(canAccess(ROLE.GUARDIAN, 'ai')).toBe(true)
    expect(visibleEntries(ROLE.ELDER, TAB_ENTRIES).map((e) => e.key)).toContain('ai')
    expect(visibleEntries(ROLE.GUARDIAN, TAB_ENTRIES).map((e) => e.key)).toContain('ai')
  })
})
