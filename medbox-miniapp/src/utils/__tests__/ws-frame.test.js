import { describe, it, expect } from 'vitest'
import {
  WS_EVENT,
  parseFrame,
  isBusinessType,
  isBusinessFrame,
  isResyncFrame,
  deviceTypeLabel,
  describeFrame,
} from '@/utils/ws-frame.js'

describe('parseFrame', () => {
  it('合法帧：抽出 type / id / topic / ts / payload', () => {
    const frame = parseFrame(JSON.stringify({
      type: 'ALARM', id: 'evt-1', topic: 'elder:u-1001', ts: 1759474520000,
      payload: { message: '08:00 1 号格（早间）未按时取药' },
    }))
    expect(frame.type).toBe('ALARM')
    expect(frame.topic).toBe('elder:u-1001')
    expect(frame.payload.message).toContain('1 号格')
  })
  it('非法 JSON / 非对象 / 缺 type → null；缺字段兜底', () => {
    expect(parseFrame('{bad')).toBeNull()
    expect(parseFrame(null)).toBeNull()
    expect(parseFrame({})).toBeNull()
    expect(parseFrame({ type: 'PONG' })).toMatchObject({ id: '', payload: {}, ts: null })
  })
})

describe('业务帧判定', () => {
  it('ALARM / DEVICE_STATUS / REMINDER 推进 lastEventId，PONG 不推进', () => {
    expect(isBusinessType(WS_EVENT.ALARM)).toBe(true)
    expect(isBusinessType(WS_EVENT.REMINDER)).toBe(true)
    expect(isBusinessType(WS_EVENT.PONG)).toBe(false)
    expect(isBusinessFrame({ type: 'DEVICE_STATUS' })).toBe(true)
    expect(isResyncFrame({ type: 'RESYNC' })).toBe(true)
  })
  it('设备类型文案', () => {
    expect(deviceTypeLabel('MAIN')).toBe('药箱主控')
    expect(deviceTypeLabel('UNKNOWN')).toBe('UNKNOWN')
  })
})

describe('describeFrame（04：REMINDER 按格，ALARM 不带 medicineIds）', () => {
  it('REMINDER 按格号给摘要', () => {
    expect(describeFrame({ type: 'REMINDER', payload: { planId: 'p-1', planTime: '2026-11-12T00:00:00Z', cellNo: 1 } }))
      .toBe('服药提醒：1 号格（早间）')
    expect(describeFrame({ type: 'REMINDER', payload: { cellNo: 3 } }))
      .toBe('服药提醒：3 号格（晚间）')
  })
  it('旧帧缺格号时兜底，不白屏', () => {
    expect(describeFrame({ type: 'REMINDER', payload: {} })).toBe('服药提醒：请按时服药')
    expect(describeFrame({ type: 'REMINDER', payload: { medicines: [] } })).toBe('服药提醒：请按时服药')
  })
  it('ALARM 直接展示后端文案（只提格号时间）', () => {
    expect(describeFrame({ type: 'ALARM', payload: { message: '08:00 2 号格（午间）未按时取药', level: 'WARN' } }))
      .toBe('08:00 2 号格（午间）未按时取药')
  })
  it('其它帧摘要', () => {
    expect(describeFrame({ type: 'DEVICE_STATUS', payload: { deviceType: 'MAIN', online: true } })).toBe('药箱主控已上线')
    expect(describeFrame({ type: 'RESYNC' })).toBe('离线期间事件已过期，请刷新列表')
    expect(describeFrame({ type: 'PONG' })).toBe('心跳正常')
    expect(describeFrame(null)).toBe('')
  })
})
