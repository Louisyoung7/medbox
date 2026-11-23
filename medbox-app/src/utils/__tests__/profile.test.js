/**
 * 个人资料域纯函数测试（`feat/mp-profile`，对应 medbox-spec/02 第 3 章、01 第 3 章）。
 *
 * <p>钉死四件事：字段兜底文案（后端 non_null 会省略 null 字段）、角色权限一句说明
 * （口径取 01 / 02 的 1.3）、老人姓名 join（含失败回退）、"要不要静默补 /users/me"的判定。
 */

import { describe, expect, it } from 'vitest'
import {
  elderNameText,
  needsProfileRefresh,
  profileFieldText,
  rolePermissionText,
} from '@/utils/profile.js'
import { ROLE } from '@/store/user.js'

describe('profileFieldText —— 字段兜底', () => {
  it('null / undefined / 空串 / 纯空白 → 未设置', () => {
    expect(profileFieldText(null)).toBe('未设置')
    expect(profileFieldText(undefined)).toBe('未设置')
    expect(profileFieldText('')).toBe('未设置')
    expect(profileFieldText('   ')).toBe('未设置')
  })

  it('正常值原样返回（去首尾空白）', () => {
    expect(profileFieldText('张桂英')).toBe('张桂英')
    expect(profileFieldText('  13900000001  ')).toBe('13900000001')
    expect(profileFieldText(0)).toBe('0')
  })
})

describe('rolePermissionText —— 角色权限一句说明', () => {
  it('老人：只读本人数据', () => {
    expect(rolePermissionText(ROLE.ELDER)).toContain('只读本人数据')
  })

  it('监护人：对已确认绑定的老人可读可写', () => {
    expect(rolePermissionText(ROLE.GUARDIAN)).toContain('可读可写')
  })

  it('未知角色 / 空 → 空串（页面不渲染该行说明）', () => {
    expect(rolePermissionText('STRANGER')).toBe('')
    expect(rolePermissionText('')).toBe('')
    expect(rolePermissionText(undefined)).toBe('')
  })
})

describe('elderNameText —— 老人姓名 join', () => {
  it('解析到姓名 → "姓名（elderId）"', () => {
    expect(elderNameText({ name: '张桂英' }, 'u-1001')).toBe('张桂英（u-1001）')
  })

  it('姓名带空白时先 trim', () => {
    expect(elderNameText({ name: '  张桂英  ' }, 'u-1001')).toBe('张桂英（u-1001）')
  })

  it('拉不到姓名（40302/40301/网络失败传 null）→ 回退 elderId 原文', () => {
    expect(elderNameText(null, 'u-1001')).toBe('u-1001')
    expect(elderNameText({}, 'u-1001')).toBe('u-1001')
    expect(elderNameText({ name: '' }, 'u-1001')).toBe('u-1001')
    expect(elderNameText({ name: '   ' }, 'u-1001')).toBe('u-1001')
  })

  it('边界：无 elderId 只显姓名；都空返回空串', () => {
    expect(elderNameText({ name: '张桂英' }, '')).toBe('张桂英')
    expect(elderNameText(null, '')).toBe('')
  })
})

describe('needsProfileRefresh —— 要不要静默补 /users/me', () => {
  it('缺 name 或 phone → true（登录时 getMe 失败的半份缓存）', () => {
    expect(needsProfileRefresh({})).toBe(true)
    expect(needsProfileRefresh({ name: '张桂英' })).toBe(true)
    expect(needsProfileRefresh({ phone: '13900000001' })).toBe(true)
  })

  it('缓存满 → false（TabBar 页 onShow 频繁，不重复请求）', () => {
    expect(needsProfileRefresh({ name: '张桂英', phone: '13900000001' })).toBe(false)
  })

  it('无资料 → true', () => {
    expect(needsProfileRefresh(null)).toBe(true)
    expect(needsProfileRefresh(undefined)).toBe(true)
  })
})
