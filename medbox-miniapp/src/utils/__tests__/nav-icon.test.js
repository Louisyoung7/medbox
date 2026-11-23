import { describe, it, expect } from 'vitest'
import { NAV_ICONS, iconDataUri } from '@/utils/nav-icon.js'

describe('iconDataUri（feat/mp-nav-drawer）', () => {
  it('选中态用主色，未选中用灰色', () => {
    expect(decodeURIComponent(iconDataUri('home', true))).toContain('#0e9f8e')
    expect(decodeURIComponent(iconDataUri('home', false))).toContain('#a8b4b6')
  })

  it('输出 data URI，且包含该图标的 SVG 主体', () => {
    const uri = iconDataUri('alarm', true)
    expect(uri.startsWith('data:image/svg+xml,')).toBe(true)
    expect(decodeURIComponent(uri)).toContain(NAV_ICONS.alarm)
  })

  it('未知图标名不抛错（正文为空）', () => {
    expect(() => iconDataUri('not-exist', false)).not.toThrow()
  })
})
