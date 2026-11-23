/**
 * 计划域纯函数（固定 3 药格模型，对应 medbox-spec/02d、02 第 6 章、06 的 2.6）。
 *
 * 规则表（已按 AGENTS.md 3.1.2 确认）：
 * - **A 格位**：药箱上层固定 1 / 2 / 3 号带锁药格（固件固定对应早 / 中 / 晚）；
 *   计划只用 `slots` 描述「几点开几号格」，不描述、不关联任何药品。
 * - **B 归一化**：`slots` 长度 1~3，`cellNo` ∈ 1/2/3 且不重复，`time` 为 `HH:mm`
 *   设备本地墙钟（**不是 UTC，前端绝不换算**），回显按 `cellNo` 升序；缺失格号 = 该格不排程。
 * - **C 一老人一计划**：列表返回 0 或 1 条；重复创建由后端返回 40901，计划不关联药品，
 *   前端不做药品预判。
 */

import { ROLE } from '@/store/user.js'

/** `HH:mm` 24 小时制（picker mode="time" 的输出格式）。 */
const TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/

/** 固定格位中文标签（1 早间 / 2 午间 / 3 晚间）。 */
export function cellLabel(cellNo) {
  const no = Number(cellNo)
  if (no === 1) return '1 号格（早间）'
  if (no === 2) return '2 号格（午间）'
  if (no === 3) return '3 号格（晚间）'
  return '未识别格位'
}

/**
 * 归一化格位排程（规则 B）：格号合法校验 + 计划内去重 + 按格号升序。
 *
 * 非法项（越界格号 / 重复格号 / "25:00" / 空时间）直接剔除，由页面在保存前提示
 * 「至少设置一个有效格位」。
 *
 * @param {Array<Object>} [slots] 原始输入（`[{cellNo, time}]`，可来自 picker 或后端回显）
 * @returns {Array<{cellNo: number, time: string}>} 合法、去重、升序的格位数组
 */
export function normalizeSlots(slots) {
  if (!Array.isArray(slots)) return []
  const seen = new Set()
  const valid = []
  slots.forEach((slot) => {
    const no = Number(slot && slot.cellNo)
    const time = String((slot && slot.time) || '').trim()
    if (!Number.isInteger(no) || no < 1 || no > 3) return
    if (!TIME_PATTERN.test(time) || seen.has(no)) return
    seen.add(no)
    valid.push({ cellNo: no, time })
  })
  return valid.sort((a, b) => a.cellNo - b.cellNo)
}

/**
 * 表单 → `POST /plans` / `PUT /plans/{planId}` 请求体。
 *
 * 只提交 `slots`（不含药品、剂量）；`repeatRule` 空串省略（不传即后端默认 DAILY）；
 * 不传 `missAlarmAfterMin` / `alarmRule`（走后端默认与老人级 `alarm_setting`）。
 *
 * @param {Object} form 页面表单：`{name, slots, repeatRule}`
 * @param {string} elderId 归属老人 `userId`
 * @returns {Object} 请求体
 */
export function buildPlanPayload(form, elderId) {
  const src = form || {}
  const payload = { elderId: String(elderId || '').trim() }
  const name = String(src.name || '').trim()
  if (name) payload.name = name
  payload.slots = normalizeSlots(src.slots)
  const repeatRule = String(src.repeatRule || '').trim()
  if (repeatRule) payload.repeatRule = repeatRule
  return payload
}

/**
 * 计划列表分页响应容错解析（一老人一计划，返回 0 或 1 条）。
 *
 * 兼容 `{list}` / `{records}` / `{items}` + `total` 与裸数组四种形状。
 *
 * @param {Object|Array|null} data 请求层兑现的 `data`
 * @returns {{list: Array<Object>, total: number}}
 */
export function parsePlansPage(data) {
  if (Array.isArray(data)) return { list: data, total: data.length }
  const src = data || {}
  let list = []
  if (Array.isArray(src.list)) list = src.list
  else if (Array.isArray(src.records)) list = src.records
  else if (Array.isArray(src.items)) list = src.items
  const total = typeof src.total === 'number' ? src.total : list.length
  return { list, total }
}

/** 格位排程展示文案（原样拼接，不格式化时间）：`1 号格 08:00、2 号格 12:30`。 */
export function planSlotsText(slots) {
  const list = normalizeSlots(slots)
  return list.length
    ? list.map((slot) => `${slot.cellNo} 号格 ${slot.time}`).join('、')
    : '未设置格位'
}

/**
 * 当前角色是否可见计划写入口（创建 / 编辑 / 启停 / 删除）。
 * `WRITE_PLAN` 仅 GUARDIAN；服务端仍按矩阵强校验，前端只决定显不显示。
 */
export function canWritePlan(role) {
  return role === ROLE.GUARDIAN
}

/** 重复周期文案（本期仅 DAILY，未知值原样返回）。 */
export function repeatRuleLabel(rule) {
  const labels = { DAILY: '每天' }
  const value = String(rule || '').trim()
  if (!value) return '未设置周期'
  return labels[value] || value
}

/** 计划状态文案。 */
export function planStatusLabel(status) {
  const labels = { ENABLED: '启用中', DISABLED: '已停用' }
  const value = String(status || '').trim()
  if (!value) return '未知状态'
  return labels[value] || value
}

/** 同步设备状态文案；`PENDING` 展示为「待同步」，未知值原样返回。 */
export function syncStateLabel(syncState) {
  const labels = {
    PENDING: '待同步',
    SYNCED_TO_DEVICE: '已同步设备',
    SYNC_FAILED: '同步失败',
  }
  const value = String(syncState || '').trim()
  if (!value) return ''
  return labels[value] || value
}

/**
 * 未来待服时间线响应容错解析：兼容 `{doses:[...]}` 与裸数组。
 *
 * @param {Object|Array|null} data 请求层兑现的 `data`
 * @returns {Array<Object>} 服药点数组（每项含 `cellNo` / `scheduledTimeUtc` / `scheduledTimeLocal`）
 */
export function parseNextDoses(data) {
  if (Array.isArray(data)) return data
  const src = data || {}
  if (Array.isArray(src.doses)) return src.doses
  return []
}

/**
 * 服药点 → 展示视图。
 *
 * 直接展示后端 `scheduledTimeLocal`（设备本地墙钟，**前端不换算**），拆出本地日期与
 * `HH:mm` 供分组/排序；每条带格号与格位文案，不含药品信息。
 *
 * @param {Object} dose 后端服药点
 * @returns {{day: string, time: string, scheduledTimeLocal: string, cellNo: ?number, cellLabel: string}}
 */
export function toDoseView(dose) {
  const src = dose || {}
  const local = String(src.scheduledTimeLocal || '').trim()
  const day = local.length >= 10 ? local.slice(0, 10) : ''
  const time = local.length >= 16 ? local.slice(11, 16) : ''
  const no = Number(src.cellNo)
  const cellNo = Number.isInteger(no) && no >= 1 && no <= 3 ? no : null
  return { day, time, scheduledTimeLocal: local, cellNo, cellLabel: cellLabel(cellNo) }
}

/**
 * 服药点按本地日期分组（按 day 升序，组内按 time 升序）。
 *
 * @param {Array<Object>} doses `parseNextDoses` + `toDoseView` 后的视图数组
 * @returns {Array<{day: string, doses: Array<Object>}>}
 */
export function groupDosesByDay(doses) {
  const list = Array.isArray(doses) ? doses : []
  const map = new Map()
  list.forEach((dose) => {
    const day = (dose && dose.day) || ''
    if (!map.has(day)) map.set(day, [])
    map.get(day).push(dose)
  })
  const groups = []
  map.forEach((items, day) => {
    items.sort((a, b) => String(a.time || '').localeCompare(String(b.time || '')))
    groups.push({ day, doses: items })
  })
  groups.sort((a, b) => String(a.day).localeCompare(String(b.day)))
  return groups
}
