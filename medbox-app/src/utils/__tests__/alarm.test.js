import { describe, it, expect } from 'vitest'
import {
  ALARM_TYPE,
  ALARM_LEVEL,
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

describe('枚举与文案', () => {
  it('类型 / 级别标签', () => {
    expect(alarmTypeLabel(ALARM_TYPE.MISS)).toBe('漏服')
    expect(alarmLevelLabel(ALARM_LEVEL.CRITICAL)).toBe('严重')
    expect(alarmTypeLabel('UNKNOWN')).toBe('UNKNOWN')
  })
  it('着色类与严重级别判定', () => {
    expect(alarmTypeClass(ALARM_TYPE.MISS)).toBe('alarm-type--critical')
    expect(alarmLevelClass(ALARM_LEVEL.WARN)).toBe('alarm-level--warn')
    expect(isCriticalLevel(ALARM_LEVEL.CRITICAL)).toBe(true)
  })
  it('筛选项与默认阈值', () => {
    expect(ALARM_TYPE_OPTIONS[0].value).toBe('ALL')
    expect(READ_STATE_OPTIONS).toHaveLength(3)
    expect(ALARM_SETTINGS_DEFAULT.advanceMin).toBe(10)
  })
})

describe('parseAlarmsPage', () => {
  it('兼容四种外壳', () => {
    expect(parseAlarmsPage([{ alarmId: 'a-1' }])).toEqual({ list: [{ alarmId: 'a-1' }], total: 1 })
    expect(parseAlarmsPage({ list: [], total: 4 })).toEqual({ list: [], total: 4 })
    expect(parseAlarmsPage({ records: [{}] }).list).toHaveLength(1)
    expect(parseAlarmsPage({ items: [{}] }).list).toHaveLength(1)
    expect(parseAlarmsPage(null)).toEqual({ list: [], total: 0 })
  })
})

describe('toAlarmView（02f / 04：无 medicineIds，MISS 带格号）', () => {
  it('未读 + CRITICAL + 格号', () => {
    const view = toAlarmView({
      alarmId: 'a-1', type: 'MISS', level: 'CRITICAL',
      message: '08:00 1 号格（早间）未按时取药', cellNo: 1,
      elderId: 'u-1001', readState: 'UNREAD',
    })
    expect(view.isUnread).toBe(true)
    expect(view.isCritical).toBe(true)
    expect(view.cellNo).toBe(1)
    expect(view.cellLabel).toBe('1 号格（早间）')
    expect(view.medicineIds).toBeUndefined()
    expect(view.medicineCount).toBeUndefined()
  })
  it('已读 + 已处理 → isUnread false；格号为空时无格位文案', () => {
    const view = toAlarmView({ readState: 'READ', handled: true, type: 'ENV', level: 'WARN' })
    expect(view.isUnread).toBe(false)
    expect(view.handled).toBe(true)
    expect(view.cellNo).toBeNull()
    expect(view.cellLabel).toBe('')
  })
  it('缺字段不报错', () => {
    expect(() => toAlarmView(null)).not.toThrow()
    expect(toAlarmView(null).readState).toBe('READ')
  })
})

describe('toAlarmSettingsView', () => {
  it('缺字段用默认值兜底，已有字段保留', () => {
    expect(toAlarmSettingsView(null)).toEqual(ALARM_SETTINGS_DEFAULT)
    const merged = toAlarmSettingsView({ advanceMin: 20, envThreshold: { temp: { max: 35 } } })
    expect(merged.advanceMin).toBe(20)
    expect(merged.envThreshold.temp.max).toBe(35)
    expect(merged.envThreshold.temp.min).toBe(15)
  })
})
