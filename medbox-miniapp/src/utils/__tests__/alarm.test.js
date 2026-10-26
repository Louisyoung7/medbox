import { describe, expect, it } from 'vitest'
import {
  ALARM_TYPE_LABEL,
  ALARM_LEVEL_LABEL,
  ALARM_TYPE_OPTIONS,
  READ_STATE_OPTIONS,
  ALARM_SETTINGS_DEFAULT,
  alarmTypeLabel,
  alarmLevelLabel,
  alarmTypeClass,
  alarmLevelClass,
  isCriticalLevel,
  parseAlarmsPage,
  toAlarmView,
  toAlarmSettingsView,
} from '@/utils/alarm.js'

describe('alarm 常量与标签', () => {
  it('ALARM_TYPE_LABEL 六类齐全', () => {
    expect(ALARM_TYPE_LABEL.MISS).toBe('漏服')
    expect(ALARM_TYPE_LABEL.WRONG_DRUG).toBe('错服')
    expect(ALARM_TYPE_LABEL.EXPIRED).toBe('临期过期')
    expect(ALARM_TYPE_LABEL.ENV).toBe('环境超标')
    expect(ALARM_TYPE_LABEL.DEVICE_OFFLINE).toBe('设备离线')
    expect(ALARM_TYPE_LABEL.DEVICE_FAULT).toBe('设备故障')
  })

  it('ALARM_LEVEL_LABEL 三级齐全', () => {
    expect(ALARM_LEVEL_LABEL.INFO).toBe('提示')
    expect(ALARM_LEVEL_LABEL.WARN).toBe('警告')
    expect(ALARM_LEVEL_LABEL.CRITICAL).toBe('严重')
  })

  it('ALARM_TYPE_OPTIONS / READ_STATE_OPTIONS 含全部 + ALL', () => {
    expect(ALARM_TYPE_OPTIONS.length).toBe(7)
    expect(ALARM_TYPE_OPTIONS[0].value).toBe('ALL')
    expect(READ_STATE_OPTIONS.map((o) => o.value)).toEqual(['ALL', 'UNREAD', 'READ'])
  })

  it('标签函数缺省回退原值', () => {
    expect(alarmTypeLabel('X')).toBe('X')
    expect(alarmLevelLabel('Y')).toBe('Y')
  })
})

describe('alarm 着色类', () => {
  it('alarmTypeClass：漏服/错服→critical；设备离线/故障→warn；环境/过期→info', () => {
    expect(alarmTypeClass('MISS')).toBe('alarm-type--critical')
    expect(alarmTypeClass('WRONG_DRUG')).toBe('alarm-type--critical')
    expect(alarmTypeClass('DEVICE_OFFLINE')).toBe('alarm-type--warn')
    expect(alarmTypeClass('DEVICE_FAULT')).toBe('alarm-type--warn')
    expect(alarmTypeClass('ENV')).toBe('alarm-type--info')
  })

  it('alarmLevelClass：CRITICAL→critical；WARN→warn；INFO→info', () => {
    expect(alarmLevelClass('CRITICAL')).toBe('alarm-level--critical')
    expect(alarmLevelClass('WARN')).toBe('alarm-level--warn')
    expect(alarmLevelClass('INFO')).toBe('alarm-level--info')
  })

  it('isCriticalLevel', () => {
    expect(isCriticalLevel('CRITICAL')).toBe(true)
    expect(isCriticalLevel('WARN')).toBe(false)
  })
})

describe('alarm parseAlarmsPage 容错', () => {
  it('{list,total}', () => {
    const r = parseAlarmsPage({ list: [1, 2], total: 2 })
    expect(r.list.length).toBe(2)
    expect(r.total).toBe(2)
  })

  it('{records,total}', () => {
    const r = parseAlarmsPage({ records: [1], total: 5 })
    expect(r.list.length).toBe(1)
    expect(r.total).toBe(5)
  })

  it('{items,total} / 裸数组 / null', () => {
    expect(parseAlarmsPage({ items: [1], total: 1 }).list.length).toBe(1)
    expect(parseAlarmsPage([1, 2, 3]).list.length).toBe(3)
    expect(parseAlarmsPage(null)).toEqual({ list: [], total: 0 })
  })
})

describe('alarm toAlarmView 归一化', () => {
  it('未读 + CRITICAL + 关联药品', () => {
    const v = toAlarmView({
      alarmId: 'a-1',
      type: 'WRONG_DRUG',
      level: 'CRITICAL',
      message: '错服',
      elderId: 'u-1001',
      medicineIds: ['m-1', 'm-2'],
      createdAt: '2026-01-01T00:00:00Z',
      readState: 'UNREAD',
      handled: false,
    })
    expect(v.alarmId).toBe('a-1')
    expect(v.typeLabel).toBe('错服')
    expect(v.levelLabel).toBe('严重')
    expect(v.isCritical).toBe(true)
    expect(v.isUnread).toBe(true)
    expect(v.handled).toBe(false)
    expect(v.medicineCount).toBe(2)
    expect(v.typeClass).toBe('alarm-type--critical')
    expect(v.levelClass).toBe('alarm-level--critical')
  })

  it('已读 + 已处理 → isUnread false', () => {
    const v = toAlarmView({
      alarmId: 'a-2',
      type: 'ENV',
      level: 'WARN',
      message: '环境',
      readState: 'READ',
      handled: true,
    })
    expect(v.isUnread).toBe(false)
    expect(v.handled).toBe(true)
    expect(v.medicineCount).toBe(0)
  })

  it('缺字段不报错', () => {
    const v = toAlarmView(null)
    expect(v.alarmId).toBeUndefined()
    expect(v.isUnread).toBe(false)
    expect(v.medicineCount).toBe(0)
  })
})

describe('alarm toAlarmSettingsView 兜底与合并', () => {
  it('null → 默认完整结构', () => {
    const v = toAlarmSettingsView(null)
    expect(v.advanceMin).toBe(ALARM_SETTINGS_DEFAULT.advanceMin)
    expect(v.missTolerateMin).toBe(ALARM_SETTINGS_DEFAULT.missTolerateMin)
    expect(v.envThreshold.temp.max).toBe(ALARM_SETTINGS_DEFAULT.envThreshold.temp.max)
  })

  it('部分字段合并，缺项用默认', () => {
    const v = toAlarmSettingsView({ advanceMin: 20, envThreshold: { temp: { max: 35 } } })
    expect(v.advanceMin).toBe(20)
    expect(v.missTolerateMin).toBe(ALARM_SETTINGS_DEFAULT.missTolerateMin)
    expect(v.envThreshold.temp.max).toBe(35)
    expect(v.envThreshold.temp.min).toBe(ALARM_SETTINGS_DEFAULT.envThreshold.temp.min)
  })
})
