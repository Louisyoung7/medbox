/**
 * 告警纯函数 / 常量（medbox-spec/02f、04 第 3 章）。
 *
 * <p>契约要点：
 * - 告警 `type` ∈ `MISS`(漏服) / `ENV`(环境超标) / `DEVICE_OFFLINE` / `DEVICE_FAULT`；
 * - 告警 `level` ∈ `INFO` / `WARN` / `CRITICAL`；
 * - WS `ALARM` 帧 payload：`alarmId` / `type` / `level` / `message` / `elderId`，
 *   **不再带 `medicineIds`**；MISS 文案只提格号与时间；
 * - WS `REMINDER` 帧 payload：`planId` / `planTime` / `cellNo`（非告警、不进列表）。
 */

/** 固定格位中文标签（1 早间 / 2 午间 / 3 晚间）。 */
export function cellLabel(cellNo) {
  const no = Number(cellNo)
  if (no === 1) return '1 号格（早间）'
  if (no === 2) return '2 号格（午间）'
  if (no === 3) return '3 号格（晚间）'
  return ''
}

/** 告警类型枚举。 */
export const ALARM_TYPE = {
  MISS: 'MISS',
  ENV: 'ENV',
  DEVICE_OFFLINE: 'DEVICE_OFFLINE',
  DEVICE_FAULT: 'DEVICE_FAULT',
}

/** 告警类型中文标签。 */
export const ALARM_TYPE_LABEL = {
  [ALARM_TYPE.MISS]: '漏服',
  [ALARM_TYPE.ENV]: '环境超标',
  [ALARM_TYPE.DEVICE_OFFLINE]: '设备离线',
  [ALARM_TYPE.DEVICE_FAULT]: '设备故障',
}

/** 告警级别枚举。 */
export const ALARM_LEVEL = { INFO: 'INFO', WARN: 'WARN', CRITICAL: 'CRITICAL' }

/** 告警级别中文标签。 */
export const ALARM_LEVEL_LABEL = {
  [ALARM_LEVEL.INFO]: '提示',
  [ALARM_LEVEL.WARN]: '警告',
  [ALARM_LEVEL.CRITICAL]: '严重',
}

/** 告警类型列表（筛选下拉顺序）。 */
export const ALARM_TYPE_OPTIONS = [
  { value: 'ALL', label: '全部类型' },
  { value: ALARM_TYPE.MISS, label: ALARM_TYPE_LABEL[ALARM_TYPE.MISS] },
  { value: ALARM_TYPE.ENV, label: ALARM_TYPE_LABEL[ALARM_TYPE.ENV] },
  { value: ALARM_TYPE.DEVICE_OFFLINE, label: ALARM_TYPE_LABEL[ALARM_TYPE.DEVICE_OFFLINE] },
  { value: ALARM_TYPE.DEVICE_FAULT, label: ALARM_TYPE_LABEL[ALARM_TYPE.DEVICE_FAULT] },
]

/** 已读/未读筛选选项。 */
export const READ_STATE_OPTIONS = [
  { value: 'ALL', label: '全部' },
  { value: 'UNREAD', label: '未读' },
  { value: 'READ', label: '已读' },
]

/** 告警设置默认值（老人级）。 */
export const ALARM_SETTINGS_DEFAULT = {
  advanceMin: 10,
  missTolerateMin: 15,
  envThreshold: {
    temp: { min: 15, max: 30 },
    humidity: { min: 30, max: 70 },
    light: { min: 50, max: 500 },
  },
}

export function alarmTypeLabel(type) {
  return ALARM_TYPE_LABEL[type] || type || '-'
}

export function alarmLevelLabel(level) {
  return ALARM_LEVEL_LABEL[level] || level || '-'
}

export function alarmTypeClass(type) {
  if (type === ALARM_TYPE.MISS) return 'alarm-type--critical'
  if (type === ALARM_TYPE.DEVICE_OFFLINE || type === ALARM_TYPE.DEVICE_FAULT) return 'alarm-type--warn'
  return 'alarm-type--info'
}

export function alarmLevelClass(level) {
  if (level === ALARM_LEVEL.CRITICAL) return 'alarm-level--critical'
  if (level === ALARM_LEVEL.WARN) return 'alarm-level--warn'
  return 'alarm-level--info'
}

export function isCriticalLevel(level) {
  return level === ALARM_LEVEL.CRITICAL
}

/** 告警分页响应容错解析（兼容 `{list,total}` / `{records,total}` / `{items,total}` / 裸数组）。 */
export function parseAlarmsPage(data) {
  if (!data) return { list: [], total: 0 }
  if (Array.isArray(data)) return { list: data, total: data.length }
  const list = data.list || data.records || data.items || []
  const total = typeof data.total === 'number' ? data.total : (Array.isArray(list) ? list.length : 0)
  return { list: Array.isArray(list) ? list : [], total }
}

/**
 * 一条告警 → 前端展示视图。MISS 带 `cellNo`（格号），不再有 `medicineIds` / `medicineCount`。
 *
 * @param {Object} item 原始告警
 * @returns {Object} 视图对象
 */
export function toAlarmView(item) {
  const no = Number(item && item.cellNo)
  const cellNo = Number.isInteger(no) && no >= 1 && no <= 3 ? no : null
  return {
    alarmId: item ? item.alarmId : undefined,
    type: item ? item.type : '',
    typeLabel: alarmTypeLabel(item ? item.type : ''),
    level: item ? item.level : '',
    levelLabel: alarmLevelLabel(item ? item.level : ''),
    typeClass: alarmTypeClass(item ? item.type : ''),
    levelClass: alarmLevelClass(item ? item.level : ''),
    isCritical: isCriticalLevel(item ? item.level : ''),
    message: item ? item.message || '' : '',
    elderId: item ? item.elderId : undefined,
    cellNo,
    cellLabel: cellLabel(cellNo),
    createdAt: item ? item.createdAt : undefined,
    readState: item ? item.readState || 'UNREAD' : 'READ',
    isUnread: item ? (item.readState || 'UNREAD') === 'UNREAD' : false,
    handled: !!(item && item.handled),
  }
}

/** 归一化告警设置（合并默认值，缺字段兜底）。 */
export function toAlarmSettingsView(raw) {
  const base = ALARM_SETTINGS_DEFAULT
  if (!raw || typeof raw !== 'object') {
    return JSON.parse(JSON.stringify(base))
  }
  const env = raw.envThreshold || {}
  const pick = (obj, key, fb) =>
    obj && typeof obj === 'object' && typeof obj[key] === 'number' ? obj[key] : fb
  return {
    advanceMin: typeof raw.advanceMin === 'number' ? raw.advanceMin : base.advanceMin,
    missTolerateMin:
      typeof raw.missTolerateMin === 'number' ? raw.missTolerateMin : base.missTolerateMin,
    envThreshold: {
      temp: {
        min: pick(env.temp, 'min', base.envThreshold.temp.min),
        max: pick(env.temp, 'max', base.envThreshold.temp.max),
      },
      humidity: {
        min: pick(env.humidity, 'min', base.envThreshold.humidity.min),
        max: pick(env.humidity, 'max', base.envThreshold.humidity.max),
      },
      light: {
        min: pick(env.light, 'min', base.envThreshold.light.min),
        max: pick(env.light, 'max', base.envThreshold.light.max),
      },
    },
  }
}
