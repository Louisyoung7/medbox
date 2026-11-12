/**
 * 记录域纯函数（固定 3 药格模型，对应 medbox-spec/02e、06 的 2.7）。
 *
 * <p>一条 `med_record` = 某计划的某号格在某次计划时间窗内的一次取药事实，
 * 只带 `cellNo`，不带药品、剂量。时间字段（planTime / actualTime）一律 UTC，
 * 仅做本地化展示，不做时区换算。
 */

import { ROLE } from '@/store/user.js'
import { formatDateTime } from '@/utils/datetime.js'

/** 固定格位中文标签（1 早间 / 2 午间 / 3 晚间）。 */
export function cellLabel(cellNo) {
  const no = Number(cellNo)
  if (no === 1) return '1 号格（早间）'
  if (no === 2) return '2 号格（午间）'
  if (no === 3) return '3 号格（晚间）'
  return '未识别格位'
}

/**
 * 记录列表分页响应容错解析：兼容 `{list}` / `{records}` / `{items}` + `total` 与裸数组。
 *
 * @param {Object|Array|null} data 请求层兑现的 `data`
 * @returns {{list: Array<Object>, total: number}}
 */
export function parseRecordsPage(data) {
  if (Array.isArray(data)) return { list: data, total: data.length }
  const src = data || {}
  let list = []
  if (Array.isArray(src.list)) list = src.list
  else if (Array.isArray(src.records)) list = src.records
  else if (Array.isArray(src.items)) list = src.items
  const total = typeof src.total === 'number' ? src.total : list.length
  return { list, total }
}

/** 当前角色是否可见记录写入口（`WRITE_RECORD` 仅 GUARDIAN；服务端仍强校验）。 */
export function canWriteRecord(role) {
  return role === ROLE.GUARDIAN
}

/**
 * 按时标签（后端 `onTime` 三态）：true 按时 / false 未按时 / null 未判定。
 * 新契约无「待确认 / 已确认」派生态，不再输出 confirmedBy 标签。
 *
 * @param {Object} [record]
 * @returns {Array<{text: string, level: 'ok'|'warn'|'info'}>}
 */
export function recordStatusTags(record) {
  const r = record || {}
  if (r.onTime === true) return [{ text: '按时', level: 'ok' }]
  if (r.onTime === false) return [{ text: '未按时', level: 'warn' }]
  return [{ text: '未判定', level: 'info' }]
}

/** 列表项 / 详情标题：计划名（缺省回退）。 */
export function recordTitle(record) {
  const r = record || {}
  return r.planName || '未命名计划'
}

/** 列表项 / 详情的格位文案（替代原药品文案）。 */
export function recordCellText(record) {
  return cellLabel(record && record.cellNo)
}

/** 计划时间本地化（UTC → 设备时区）。 */
export function formatPlanTime(record) {
  return formatDateTime(record && record.planTime)
}

/** 实际服药时间本地化（UTC → 设备时区）；未记录回退 '-'。 */
export function formatActualTime(record) {
  return formatDateTime(record && record.actualTime)
}
