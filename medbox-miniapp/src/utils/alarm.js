/**
 * 告警 的纯函数 / 常量（medbox-spec/02 第 8 章、04 第 3 章；`feat/mp-alarm`）。
 *
 * <p>后端 `alarm-core`（列表 / 未读 / 已读 / 处理 / 阈值设置）已落地（07 P1，2026-10-26）；
 * 告警数据源 `miss-alarm` / `env-alarm` 已落地，`alarm-device-offline` 在 07 仍为 `[ ]` 未落地
 * ——列表对缺类自动少该类，不报错。本文件按 02 / 04 契约为准。
 *
 * <p>契约要点：
 * - 告警 `type` ∈ `MISS`(漏服) / `ENV`(环境超标) /
 *   `DEVICE_OFFLINE`(设备离线) / `DEVICE_FAULT`(设备故障)（02 第 8 章枚举）；
 * - 告警 `level` ∈ `INFO` / `WARN` / `CRITICAL`（04 第 3 章 ALARM 帧 `level`）；
 * - 列表 / 未读 / 已读 / 设置查询属 `READ_ALARM`（四角色可读）；`PATCH /alarms/{id}/handle`
 *   属 `HANDLE_ALARM`（FAMILY + NURSE）；`PUT /alarm-settings` 属 `WRITE_ALARM_SETTINGS`
 *   （仅 FAMILY，DOCTOR / NURSE / ELDER 写返回 40301）；
 * - WS 收到 `ALARM` 帧：`payload` 含 `alarmId` / `type` / `level` / `message` / `elderId` /
 *   `medicineIds[]`；`REMINDER` 帧：`payload` 含 `planId` / `planTime` / `medicines[]`
 *   （一次提醒可含多种药，非告警、不进列表）。
 *
 * <p>纯函数、可单测（见 `__tests__/alarm.test.js`）。
 */

/** 告警类型枚举（02 第 8 章）。 */
export const ALARM_TYPE = {
  MISS: 'MISS',
  ENV: 'ENV',
  DEVICE_OFFLINE: 'DEVICE_OFFLINE',
  DEVICE_FAULT: 'DEVICE_FAULT',
}

/** 告警类型中文标签（漏服 / 环境超标 / 设备离线 / 设备故障）。 */
export const ALARM_TYPE_LABEL = {
  [ALARM_TYPE.MISS]: '漏服',
  [ALARM_TYPE.ENV]: '环境超标',
  [ALARM_TYPE.DEVICE_OFFLINE]: '设备离线',
  [ALARM_TYPE.DEVICE_FAULT]: '设备故障',
}

/** 告警级别枚举（04 第 3 章）。 */
export const ALARM_LEVEL = {
  INFO: 'INFO',
  WARN: 'WARN',
  CRITICAL: 'CRITICAL',
}

/** 告警级别中文标签（提示 / 警告 / 严重）。 */
export const ALARM_LEVEL_LABEL = {
  [ALARM_LEVEL.INFO]: '提示',
  [ALARM_LEVEL.WARN]: '警告',
  [ALARM_LEVEL.CRITICAL]: '严重',
}

/** 告警类型列表（筛选下拉顺序）。'ALL' 为"全部"占位，不进枚举。 */
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

/** 告警设置默认值（老人级；字段对齐 02 第 8/9 章 + 06 §2.6.1，便于 `GET` 回填缺失项）。 */
export const ALARM_SETTINGS_DEFAULT = {
  advanceMin: 10,
  missTolerateMin: 15,
  envThreshold: {
    temp: { min: 15, max: 30 },
    humidity: { min: 30, max: 70 },
    light: { min: 50, max: 500 },
  },
}

/** 告警类型中文标签。 */
export function alarmTypeLabel(type) {
  return ALARM_TYPE_LABEL[type] || type || '-'
}

/** 告警级别中文标签。 */
export function alarmLevelLabel(level) {
  return ALARM_LEVEL_LABEL[level] || level || '-'
}

/** 告警类型着色类（CRITICAL 红 / WARN 橙 / 其余蓝）。 */
export function alarmTypeClass(type) {
  if (type === ALARM_TYPE.MISS) return 'alarm-type--critical'
  if (type === ALARM_TYPE.DEVICE_OFFLINE || type === ALARM_TYPE.DEVICE_FAULT) return 'alarm-type--warn'
  return 'alarm-type--info'
}

/** 告警级别着色类（CRITICAL 红 / WARN 橙 / INFO 蓝）。 */
export function alarmLevelClass(level) {
  if (level === ALARM_LEVEL.CRITICAL) return 'alarm-level--critical'
  if (level === ALARM_LEVEL.WARN) return 'alarm-level--warn'
  return 'alarm-level--info'
}

/** 是否"严重"（CRITICAL）。 */
export function isCriticalLevel(level) {
  return level === ALARM_LEVEL.CRITICAL
}

/**
 * 告警分页响应容错解析（与 events / medicines / records 同口径：兼容 `{list,total}` /
 * `{records,total}` / `{items,total}` / 裸数组）。
 *
 * @param {*} data 后端响应 `data`
 * @returns {{list:Array, total:number}}
 */
export function parseAlarmsPage(data) {
  if (!data) return { list: [], total: 0 }
  if (Array.isArray(data)) return { list: data, total: data.length }
  const list = data.list || data.records || data.items || []
  const total = typeof data.total === 'number' ? data.total : (Array.isArray(list) ? list.length : 0)
  return { list: Array.isArray(list) ? list : [], total }
}

/**
 * 把一条告警归一化为前端展示视图。
 *
 * <p>后端告警对象字段（02 第 8 章 + 04 第 3 章 ALARM 帧）：`alarmId` / `type` / `level` /
 * `message` / `elderId` / `medicineIds[]` / `createdAt` / `readState`("READ"|"UNREAD") /
 * `handled`(boolean)。后端落地后若字段形态不同，只改本函数。
 *
 * @param {Object} item 原始告警
 * @returns {Object} 视图对象（含标签 / 着色类 / 分类布尔）
 */
export function toAlarmView(item) {
  const view = {
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
    medicineIds: Array.isArray(item && item.medicineIds) ? item.medicineIds : [],
    medicineCount: Array.isArray(item && item.medicineIds) ? item.medicineIds.length : 0,
    createdAt: item ? item.createdAt : undefined,
    readState: item ? item.readState || 'UNREAD' : 'READ',
    isUnread: item ? (item.readState || 'UNREAD') === 'UNREAD' : false,
    handled: !!(item && item.handled),
  }
  return view
}

/**
 * 归一化告警设置（合并默认值，缺字段用 {@link ALARM_SETTINGS_DEFAULT} 兜底）。
 *
 * @param {Object} [raw] 后端 `GET /alarm-settings` 返回的 `data`
 * @returns {Object} 完整设置对象（可直接驱动表单）
 */
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
