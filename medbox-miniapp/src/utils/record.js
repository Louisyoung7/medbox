/**
 * 记录域纯函数（`feat/mp-record`，对应 medbox-spec/02 第 7 章、06 的 2.7）。
 *
 * <p>只放与页面解耦的可单测逻辑：列表分页容错解析、按时标签、时间本地化、
 * 列表项标题与药品文案。时间字段（planTime / actualTime）一律 UTC，仅做本地化展示，
 * 不做时区换算（与 02 通用约定一致；记录不涉及计划 `times` 的墙钟例外）。
 *
 * <p>**非判定型**：08 的 `feat/mp-record` 未标 🧭，按时直接由后端布尔字段
 * `onTime` 映射展示，本模块不实现任何判定规则表。
 */

import { ROLE } from '@/store/user.js'
import { formatDateTime } from '@/utils/datetime.js'
import { isUncertain } from '@/utils/capture.js'

/**
 * 记录列表分页响应容错解析。
 *
 * <p>⚠️ 后端 `record-core` 未落地，`GET /records` 的响应形状未定：兼容 `{list}` /
 * `{records}` / `{items}` + `total` 与裸数组四种形状，后端定稿后只改这一处，页面不受影响。
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

/**
 * 当前角色是否可见记录写入口（监护人补录 / 确认）。
 *
 * <p>02 的 1.3 权限矩阵：`WRITE_RECORD` 仅 GUARDIAN ✅，老人只读。注册角色只有 ELDER / GUARDIAN，
 * 这里用 GUARDIAN 代理；**服务端仍按矩阵强校验**，前端只决定显不显示。
 *
 * @param {string} role `store/user.js` 的角色
 * @returns {boolean}
 */
export function canWriteRecord(role) {
  return role === ROLE.GUARDIAN
}

/**
 * 按时 / 待确认 / 已确认 标签（直接映射后端字段，不做判定）。
 *
 * <p>优先级：`isUncertain` 命中（低置信度 `UNCERTAIN` / `pendingConfirm`）→ 待确认（pending），
 * 置顶高亮；`onTime=true` → 按时（ok）；否则 → 未按时（warn）。
 * `confirmedBy` 存在（监护人已补录 / 确认）→ 追加"已确认"（info）。
 * `level` 仅作 CSS 配色语义，页面自行映射到样式类（`tag--pending` 橙黄高亮）。
 *
 * @param {Object} [record]
 * @returns {Array<{text: string, level: 'ok'|'warn'|'info'|'pending'}>}
 */
export function recordStatusTags(record) {
  const r = record || {}
  const tags = []
  if (isUncertain(r)) {
    tags.push({ text: '待确认', level: 'pending' })
  }
  if (r.onTime) {
    tags.push({ text: '按时', level: 'ok' })
  } else {
    tags.push({ text: '未按时', level: 'warn' })
  }
  if (r.confirmedBy) {
    tags.push({ text: '已确认', level: 'info' })
  }
  return tags
}

/**
 * 列表项 / 详情标题：计划名（缺省回退）。
 *
 * @param {Object} [record]
 * @returns {string}
 */
export function recordTitle(record) {
  const r = record || {}
  return r.planName || '未命名计划'
}

/**
 * 列表项药品文案：`药品名 剂量单位`（缺省回退 medicineId）。
 *
 * @param {Object} [record]
 * @returns {string}
 */
export function recordMedicineText(record) {
  const r = record || {}
  const name = r.medicineName || r.medicineId || '未知药品'
  const dose = [r.medicineDose, r.medicineUnit].filter(Boolean).join('')
  return dose ? `${name} ${dose}` : name
}

/** 计划时间本地化（UTC → 设备时区）。 */
export function formatPlanTime(record) {
  return formatDateTime(record && record.planTime)
}

/** 实际服药时间本地化（UTC → 设备时区）；未记录回退 '-'。 */
export function formatActualTime(record) {
  return formatDateTime(record && record.actualTime)
}

// 待确认态判定统一从 capture 域复用，页面可直接从本模块引入，减少导入面。
export { isUncertain } from '@/utils/capture.js'
