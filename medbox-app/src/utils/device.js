/**
 * 设备命令与事件流水 的纯函数 / 常量（medbox-spec/02 第 4 章；`feat/mp-device-extra`）。
 *
 * <p>后端 `device-command` / `device-fault` 在 07 清单尚未落地（截止 2026-10-25 均为 `[ ]`），
 * 本文件按 02 / 03 契约先行约定；真后端定稿后只收紧此处。
 *
 * <p>契约要点：
 * - 命令 `cmd` ∈ `BUZZ`(蜂鸣) / `UNLOCK`(解锁) / `CALIBRATE`(校准) / `REBOOT`(重启)（03 第 2 章 `down/command`）；
 * - 事件 `event_type` ∈ `REGISTERED` / `ONLINE` / `OFFLINE` / `FAULT` / `COMMAND`（06 的 2.16）；
 * - `FAULT` 的 `detail` 为故障码 `DOOR_TIMEOUT`(门超时) / `CAMERA_FAILED`(识别失败) / `HW_FAULT`(硬件故障)
 *   （03 第 3 章 `up/event/error`）；
 * - `COMMAND` 的 `detail` 约定为 `{ commandId, cmd, state }`（`state`: `PENDING`/`SUCCESS`/`FAILED`）。
 *   真后端 `device-command` 的 COMMAND 事件 `detail` 形态待定，miniapp 只按"含 commandId / cmd / state"
 *   读取，便于后端定稿后收紧（不另行发明命令状态 GET 或 WS 推送帧）。
 *
 * <p>纯函数、可单测（见 `__tests__/device.test.js`）。
 */

/** 命令枚举。 */
export const CMD = {
  BUZZ: 'BUZZ',
  UNLOCK: 'UNLOCK',
  CALIBRATE: 'CALIBRATE',
  REBOOT: 'REBOOT',
}

/** 命令中文标签（蜂鸣 / 解锁 / 校准 / 重启）。 */
export const CMD_LABEL = {
  [CMD.BUZZ]: '蜂鸣',
  [CMD.UNLOCK]: '解锁',
  [CMD.CALIBRATE]: '校准',
  [CMD.REBOOT]: '重启',
}

/** 事件类型中文标签。 */
export const EVENT_TYPE_LABEL = {
  REGISTERED: '设备登记',
  ONLINE: '上线',
  OFFLINE: '离线',
  FAULT: '设备异常',
  COMMAND: '控制命令',
}

/** 故障码中文标签（03 第 3 章 `up/event/error`）。 */
export const FAULT_LABEL = {
  DOOR_TIMEOUT: '门超时未关',
  CAMERA_FAILED: '识别失败',
  HW_FAULT: '硬件故障',
}

/** 命令状态中文标签。 */
export const COMMAND_STATE_LABEL = {
  PENDING: '下发中',
  SUCCESS: '成功',
  FAILED: '失败',
}

/** 命令列表（卡片渲染顺序）。 */
export const CMD_LIST = [CMD.BUZZ, CMD.UNLOCK, CMD.CALIBRATE, CMD.REBOOT]

/** 命令中文标签（蜂鸣 / 解锁 / 校准 / 重启）。 */
export function cmdLabel(cmd) {
  return CMD_LABEL[cmd] || cmd || '-'
}

/** 事件类型中文标签。 */
export function eventTypeLabel(type) {
  return EVENT_TYPE_LABEL[type] || type || '-'
}

/** 故障码中文标签（03 第 3 章 `up/event/error`）。 */
export function faultLabel(code) {
  return FAULT_LABEL[code] || code || '-'
}

/** 命令状态中文标签。 */
export function commandStateLabel(state) {
  return COMMAND_STATE_LABEL[state] || state || '-'
}

/** 是否"上线 / 登记"类事件（绿）。 */
export function isOnlineEvent(type) {
  return type === 'ONLINE' || type === 'REGISTERED'
}

/** 是否"离线"事件（心跳异常落在此类）。 */
export function isOfflineEvent(type) {
  return type === 'OFFLINE'
}

/** 是否"设备侧异常"事件（FAULT）。 */
export function isFaultEvent(type) {
  return type === 'FAULT'
}

/** 是否"控制命令"事件（COMMAND）。 */
export function isCommandEvent(type) {
  return type === 'COMMAND'
}

/**
 * 事件分页响应容错解析（与 medicines / records 同口径：兼容 `{list,total}` / `{records,total}` /
 * `{items,total}` / 裸数组）。
 *
 * @param {*} data 后端响应 `data`
 * @returns {{list:Array, total:number}}
 */
export function parseEventsPage(data) {
  if (!data) return { list: [], total: 0 }
  if (Array.isArray(data)) return { list: data, total: data.length }
  const list = data.list || data.records || data.items || []
  const total = typeof data.total === 'number' ? data.total : (Array.isArray(list) ? list.length : 0)
  return { list: Array.isArray(list) ? list : [], total }
}

/**
 * 把一条设备事件归一化为前端展示视图。
 *
 * <p>COMMAND 事件的 `detail` 约定为 `{ commandId, cmd, state }`；FAULT 事件的 `detail` 为故障码字符串。
 * 后端落地后若 `detail` 形态不同，只改本函数。
 *
 * @param {Object} item `{ eventId, deviceId, eventType, detail, createdAt }`
 * @returns {Object} 视图对象（含 `eventTypeLabel` / 分类布尔 / 故障或命令的解析字段）
 */
export function toEventView(item) {
  const eventType = (item && item.eventType) || ''
  const detail = item ? item.detail : null
  const view = {
    eventId: item ? item.eventId : undefined,
    deviceId: item ? item.deviceId : undefined,
    eventType,
    eventTypeLabel: eventTypeLabel(eventType),
    createdAt: item ? item.createdAt : undefined,
    isOnline: isOnlineEvent(eventType),
    isOffline: isOfflineEvent(eventType),
    isFault: isFaultEvent(eventType),
    isCommand: isCommandEvent(eventType),
  }
  if (view.isCommand && detail && typeof detail === 'object') {
    view.commandId = detail.commandId
    view.cmd = detail.cmd
    view.cmdLabel = cmdLabel(detail.cmd)
    view.commandState = detail.state
    view.commandStateLabel = commandStateLabel(detail.state)
  } else if (view.isFault && typeof detail === 'string') {
    view.faultCode = detail
    view.faultLabel = faultLabel(detail)
  }
  return view
}
