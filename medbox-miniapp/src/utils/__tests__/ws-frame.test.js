import { describe, expect, it } from 'vitest'
import {
  WS_EVENT,
  describeFrame,
  deviceTypeLabel,
  isBusinessFrame,
  isBusinessType,
  isResyncFrame,
  parseFrame,
} from '@/utils/ws-frame.js'

const raw = (frame) => JSON.stringify(frame)

describe('parseFrame', () => {
  it('解析合法帧：type / id / topic / ts(毫秒) / payload', () => {
    const frame = parseFrame(
      raw({
        type: 'ALARM',
        id: 'evt-9a1c',
        topic: 'elder:e-1001',
        ts: 1759474520000,
        payload: { alarmId: 'a-5001', level: 'WARN' },
      }),
    )
    expect(frame).toEqual({
      type: 'ALARM',
      id: 'evt-9a1c',
      topic: 'elder:e-1001',
      ts: 1759474520000,
      payload: { alarmId: 'a-5001', level: 'WARN' },
    })
  })

  it('PONG 是控制帧：不带 topic 与 payload 也能解析（补空串 / 空对象）', () => {
    const frame = parseFrame(raw({ type: 'PONG', id: 'evt-9a1f', ts: 1759474550000 }))
    expect(frame.type).toBe(WS_EVENT.PONG)
    expect(frame.topic).toBe('')
    expect(frame.payload).toEqual({})
  })

  it('ts 缺失或不是数字 → null（页面据此不显示时间）', () => {
    expect(parseFrame(raw({ type: 'ALARM' })).ts).toBeNull()
    expect(parseFrame(raw({ type: 'ALARM', ts: '1759474520000' })).ts).toBeNull()
  })

  it('非法输入一律返回 null，不抛异常', () => {
    expect(parseFrame('not json')).toBeNull()
    expect(parseFrame('')).toBeNull()
    expect(parseFrame(null)).toBeNull()
    expect(parseFrame(undefined)).toBeNull()
    expect(parseFrame(42)).toBeNull()
    expect(parseFrame('[]')).toBeNull()
    expect(parseFrame(raw({ id: 'evt-1' }))).toBeNull() // 缺 type
    expect(parseFrame(raw({ type: 123 }))).toBeNull() // type 不是字符串
    expect(parseFrame(raw({ type: '' }))).toBeNull()
  })

  it('兼容已解析的对象与字符串两种入参', () => {
    const obj = { type: 'REMINDER', id: 'evt-1', ts: 1, payload: {} }
    expect(parseFrame(obj)).toEqual(parseFrame(raw(obj)))
  })
})

describe('isBusinessFrame / isBusinessType', () => {
  it('ALARM / DEVICE_STATUS / REMINDER 是业务帧', () => {
    expect(isBusinessType(WS_EVENT.ALARM)).toBe(true)
    expect(isBusinessType(WS_EVENT.DEVICE_STATUS)).toBe(true)
    expect(isBusinessType(WS_EVENT.REMINDER)).toBe(true)
  })

  it('PONG / RESYNC / AI_STREAM 不是业务帧（不推进 lastEventId）', () => {
    expect(isBusinessType(WS_EVENT.PONG)).toBe(false)
    expect(isBusinessType(WS_EVENT.RESYNC)).toBe(false)
    expect(isBusinessType(WS_EVENT.AI_STREAM)).toBe(false)
  })

  it('帧对象版：空值不为业务帧', () => {
    expect(isBusinessFrame(parseFrame(raw({ type: 'ALARM', id: 'evt-1' })))).toBe(true)
    expect(isBusinessFrame(null)).toBe(false)
  })
})

describe('isResyncFrame', () => {
  it('识别补推窗口过期帧', () => {
    const frame = parseFrame(
      raw({ type: 'RESYNC', id: 'evt-2', topic: 'elder:e-1', payload: { reason: 'WINDOW_EXPIRED', latestEventId: 'evt-1' } }),
    )
    expect(isResyncFrame(frame)).toBe(true)
    expect(isResyncFrame(parseFrame(raw({ type: 'ALARM' })))).toBe(false)
    expect(isResyncFrame(null)).toBe(false)
  })
})

describe('describeFrame', () => {
  it('告警优先用后端给的 message，没有则回退级别', () => {
    expect(
      describeFrame({ type: 'ALARM', payload: { message: '08:00 计划服药未按时服用' } }),
    ).toBe('08:00 计划服药未按时服用')
    expect(describeFrame({ type: 'ALARM', payload: { level: 'WARN' } })).toBe('告警（WARN）')
    expect(describeFrame({ type: 'ALARM', payload: {} })).toBe('告警（未分级）')
  })

  it('设备状态：类型 + 在线/离线', () => {
    expect(describeFrame({ type: 'DEVICE_STATUS', payload: { deviceType: 'MAIN', online: true } })).toBe(
      '药箱主控已上线',
    )
    expect(describeFrame({ type: 'DEVICE_STATUS', payload: { deviceType: 'CAMERA', online: false } })).toBe(
      '摄像头已离线',
    )
  })

  it('服药提醒：一次可含多种药，列名；无名时退化为数量', () => {
    const frame = {
      type: 'REMINDER',
      payload: {
        medicines: [
          { medicineName: '阿莫西林', dose: '1', unit: '片' },
          { medicineName: '维生素D', dose: '2', unit: '粒' },
        ],
      },
    }
    expect(describeFrame(frame)).toBe('服药提醒：阿莫西林、维生素D')
    expect(describeFrame({ type: 'REMINDER', payload: { medicines: [{}, {}] } })).toBe('服药提醒：2 种药品')
    expect(describeFrame({ type: 'REMINDER', payload: {} })).toBe('服药提醒：请按时服药')
  })

  it('RESYNC / PONG / 未知类型与非法输入', () => {
    expect(describeFrame({ type: 'RESYNC', payload: {} })).toBe('离线期间事件已过期，请刷新列表')
    expect(describeFrame({ type: 'PONG', payload: {} })).toBe('心跳正常')
    expect(describeFrame({ type: 'SOMETHING_NEW', payload: {} })).toBe('SOMETHING_NEW')
    expect(describeFrame('not json')).toBe('')
  })

  it('设备类型中文名：未知类型原样返回', () => {
    expect(deviceTypeLabel('TEMP_HUMI')).toBe('温湿度传感器')
    expect(deviceTypeLabel('LIGHT')).toBe('光照传感器')
    expect(deviceTypeLabel('UNKNOWN')).toBe('UNKNOWN')
    expect(deviceTypeLabel('')).toBe('设备')
  })
})
