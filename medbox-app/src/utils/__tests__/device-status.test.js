/**
 * 设备在线状态合并逻辑测试（`feat/mp-device`，对应 `utils/device-status.js`）。
 *
 * <p>这里是 WS 帧（boolean `online`）与 REST 数据（字符串 `onlineState`）之间的唯一转换点，
 * 写错不会报错、只会让列表 / 详情状态显示反 —— 所以拆成纯函数直接单测。
 */

import { describe, expect, it } from 'vitest'
import { applyStatus, toOnlineState } from '@/utils/device-status.js'

const frame = (deviceId, online) => ({ type: 'DEVICE_STATUS', payload: { deviceId, online } })

describe('toOnlineState', () => {
  it('true → ONLINE', () => {
    expect(toOnlineState(true)).toBe('ONLINE')
  })

  it('false → OFFLINE', () => {
    expect(toOnlineState(false)).toBe('OFFLINE')
  })
})

describe('applyStatus', () => {
  it('数组：命中项更新 onlineState，未命中项保持原引用，返回新数组', () => {
    const list = [
      { deviceId: 'A', onlineState: 'ONLINE' },
      { deviceId: 'B', onlineState: 'ONLINE' },
    ]

    const next = applyStatus(list, frame('B', false))

    expect(next).not.toBe(list)
    expect(next[0]).toBe(list[0])
    expect(next[1]).toEqual({ deviceId: 'B', onlineState: 'OFFLINE' })
    // 原数组未被原地改
    expect(list[1].onlineState).toBe('ONLINE')
  })

  it('单个对象：命中返回新对象，未命中原样返回', () => {
    const device = { deviceId: 'A', onlineState: 'OFFLINE' }

    const hit = applyStatus(device, frame('A', true))
    expect(hit).toEqual({ deviceId: 'A', onlineState: 'ONLINE' })
    expect(hit).not.toBe(device)

    expect(applyStatus(device, frame('X', true))).toBe(device)
  })

  it('payload 缺 deviceId / 空串 / 帧为空：原样返回，不抛异常', () => {
    const list = [{ deviceId: 'A', onlineState: 'OFFLINE' }]

    expect(applyStatus(list, { payload: { online: true } })).toBe(list)
    expect(applyStatus(list, { payload: { deviceId: '', online: true } })).toBe(list)
    expect(applyStatus(list, {})).toBe(list)
    expect(applyStatus(list, null)).toBe(list)
  })
})
