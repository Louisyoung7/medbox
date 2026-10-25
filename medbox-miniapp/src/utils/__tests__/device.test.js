import { describe, expect, it } from 'vitest'
import {
  CMD,
  CMD_LABEL,
  FAULT_LABEL,
  CMD_LIST,
  cmdLabel,
  eventTypeLabel,
  faultLabel,
  commandStateLabel,
  isOnlineEvent,
  isOfflineEvent,
  isFaultEvent,
  isCommandEvent,
  parseEventsPage,
  toEventView,
} from '@/utils/device.js'

describe('device 常量与标签', () => {
  it('CMD_LABEL 四命令齐全', () => {
    expect(CMD_LABEL[CMD.BUZZ]).toBe('蜂鸣')
    expect(CMD_LABEL[CMD.UNLOCK]).toBe('解锁')
    expect(CMD_LABEL[CMD.CALIBRATE]).toBe('校准')
    expect(CMD_LABEL[CMD.REBOOT]).toBe('重启')
  })

  it('CMD_LIST 顺序与长度', () => {
    expect(CMD_LIST).toEqual([CMD.BUZZ, CMD.UNLOCK, CMD.CALIBRATE, CMD.REBOOT])
  })

  it('FAULT_LABEL 三故障码', () => {
    expect(FAULT_LABEL.DOOR_TIMEOUT).toBe('门超时未关')
    expect(FAULT_LABEL.CAMERA_FAILED).toBe('识别失败')
    expect(FAULT_LABEL.HW_FAULT).toBe('硬件故障')
  })

  it('标签函数缺省回退原值', () => {
    expect(cmdLabel('X')).toBe('X')
    expect(eventTypeLabel('Y')).toBe('Y')
    expect(faultLabel('Z')).toBe('Z')
    expect(commandStateLabel('W')).toBe('W')
  })
})

describe('device 事件分类', () => {
  it('isOnlineEvent 含 ONLINE/REGISTERED', () => {
    expect(isOnlineEvent('ONLINE')).toBe(true)
    expect(isOnlineEvent('REGISTERED')).toBe(true)
    expect(isOnlineEvent('OFFLINE')).toBe(false)
  })

  it('isOfflineEvent / isFaultEvent / isCommandEvent', () => {
    expect(isOfflineEvent('OFFLINE')).toBe(true)
    expect(isFaultEvent('FAULT')).toBe(true)
    expect(isCommandEvent('COMMAND')).toBe(true)
    expect(isFaultEvent('ONLINE')).toBe(false)
  })
})

describe('device parseEventsPage 容错', () => {
  it('{list,total}', () => {
    const r = parseEventsPage({ list: [1, 2], total: 2 })
    expect(r.list.length).toBe(2)
    expect(r.total).toBe(2)
  })

  it('{records,total}', () => {
    const r = parseEventsPage({ records: [1], total: 5 })
    expect(r.list.length).toBe(1)
    expect(r.total).toBe(5)
  })

  it('{items,total}', () => {
    const r = parseEventsPage({ items: [1], total: 1 })
    expect(r.list.length).toBe(1)
  })

  it('裸数组', () => {
    const r = parseEventsPage([1, 2, 3])
    expect(r.list.length).toBe(3)
    expect(r.total).toBe(3)
  })

  it('null / 缺 total', () => {
    expect(parseEventsPage(null)).toEqual({ list: [], total: 0 })
    expect(parseEventsPage({ list: [1] }).total).toBe(1)
  })
})

describe('device toEventView 归一化', () => {
  it('FAULT 事件解析故障码标签', () => {
    const v = toEventView({
      eventId: 'e1',
      deviceId: 'BOXA1001',
      eventType: 'FAULT',
      detail: 'DOOR_TIMEOUT',
      createdAt: '2026-01-01T00:00:00Z',
    })
    expect(v.isFault).toBe(true)
    expect(v.faultCode).toBe('DOOR_TIMEOUT')
    expect(v.faultLabel).toBe('门超时未关')
    expect(v.eventTypeLabel).toBe('设备异常')
  })

  it('COMMAND 事件解析 commandId/cmd/state', () => {
    const v = toEventView({
      eventId: 'e2',
      deviceId: 'BOXA1001',
      eventType: 'COMMAND',
      detail: { commandId: 'c-1', cmd: 'BUZZ', state: 'SUCCESS' },
      createdAt: '2026-01-01T00:00:00Z',
    })
    expect(v.isCommand).toBe(true)
    expect(v.commandId).toBe('c-1')
    expect(v.cmd).toBe('BUZZ')
    expect(v.cmdLabel).toBe('蜂鸣')
    expect(v.commandState).toBe('SUCCESS')
    expect(v.commandStateLabel).toBe('成功')
  })

  it('ONLINE 事件无故障/命令字段', () => {
    const v = toEventView({
      eventId: 'e3',
      deviceId: 'BOXA1001',
      eventType: 'ONLINE',
      createdAt: '2026-01-01T00:00:00Z',
    })
    expect(v.isOnline).toBe(true)
    expect(v.isFault).toBe(false)
    expect(v.faultCode).toBeUndefined()
    expect(v.commandId).toBeUndefined()
  })
})
