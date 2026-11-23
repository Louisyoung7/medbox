/**
 * 监护关系域纯函数测试（`feat/mp-guardian`，对应 medbox-spec/02 第 3 章的 3.2）。
 *
 * <p>本模块**不做任何判定**（40301 / 40302 / 40901 全在服务端），这里只钉死文案映射、
 * 列表容错与输入归一化 —— 这些是页面渲染的直接依赖。
 */

import { describe, expect, it } from 'vitest'
import {
  normalizeElderId,
  parsePendingList,
  relationStatusTag,
  relationTimeText,
  RELATION_STATUS,
} from '@/utils/guardian.js'

describe('guardian utils —— 状态文案', () => {
  it('relationStatusTag：PENDING 待确认(warn) / ACTIVE 已生效(ok) / REVOKED 已解除(info)', () => {
    expect(relationStatusTag(RELATION_STATUS.PENDING)).toEqual({ text: '待确认', level: 'warn' })
    expect(relationStatusTag(RELATION_STATUS.ACTIVE)).toEqual({ text: '已生效', level: 'ok' })
    expect(relationStatusTag(RELATION_STATUS.REVOKED)).toEqual({ text: '已解除', level: 'info' })
  })

  it('relationStatusTag：未知状态原样返回，空状态按未知', () => {
    expect(relationStatusTag('WEIRD')).toEqual({ text: 'WEIRD', level: 'info' })
    expect(relationStatusTag('')).toEqual({ text: '未知', level: 'info' })
    expect(relationStatusTag(undefined)).toEqual({ text: '未知', level: 'info' })
  })
})

describe('guardian utils —— normalizeElderId', () => {
  it('去首尾空白；合法 ID 原样返回', () => {
    expect(normalizeElderId('  u-1001  ')).toBe('u-1001')
    expect(normalizeElderId('u-1001')).toBe('u-1001')
  })

  it('空串 / 纯空白 / 非字符串按空处理（页面据此禁用提交）', () => {
    expect(normalizeElderId('')).toBe('')
    expect(normalizeElderId('   ')).toBe('')
    expect(normalizeElderId(null)).toBe('')
    expect(normalizeElderId(undefined)).toBe('')
    expect(normalizeElderId(1001)).toBe('')
  })
})

describe('guardian utils —— parsePendingList', () => {
  const row = { relationId: 'gr-3', guardianName: '王芳' }

  it('裸数组：02 的 3.2 定义形状', () => {
    expect(parsePendingList([row])).toEqual({ list: [row], total: 1 })
  })

  it('容错 {list} / {items} 包装与 null', () => {
    expect(parsePendingList({ list: [row] })).toEqual({ list: [row], total: 1 })
    expect(parsePendingList({ items: [row] })).toEqual({ list: [row], total: 1 })
    expect(parsePendingList(null)).toEqual({ list: [], total: 0 })
    expect(parsePendingList(undefined)).toEqual({ list: [], total: 0 })
    expect(parsePendingList('x')).toEqual({ list: [], total: 0 })
  })
})

describe('guardian utils —— relationTimeText', () => {
  it('UTC 时间本地化：5 分钟前（vitest 固定 TZ=Asia/Shanghai）', () => {
    const fiveMinAgo = new Date(Date.now() - 5 * 60 * 1000).toISOString()
    expect(relationTimeText(fiveMinAgo)).toBe('5分钟前')
  })

  it('解析失败返回 -', () => {
    expect(relationTimeText('not-a-time')).toBe('-')
    expect(relationTimeText('')).toBe('-')
    expect(relationTimeText(null)).toBe('-')
  })
})
