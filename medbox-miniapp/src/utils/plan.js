/**
 * 计划域纯函数（`feat/mp-plan`，规则表 A/B/C 的可单测落地，对应 medbox-spec/02 第 6 章、
 * 06 的 2.6 / 2.6.1）。
 *
 * 规则表（用户确认后同步沉淀进 02 第 6 章）：
 * - **A 时间**：`times` 为**设备本地墙钟**（02/06/01 三处一致，时区取 `device.timezone`，
 *   默认 `Asia/Shanghai`），**不是 UTC**——本模块只做字符串校验 / 去重 / 排序，绝不换算；
 *   跨天 / 修改当日生效等边界由服务端处理。
 * - **B items**：一次提醒 = 一个计划可含多种药；`medicineId` 必填，`dose`/`unit`/`note` 选填
 *   （空串省略，后端按 06"计划明细未指定时取药品默认剂量"兜底）；同一计划内药品不重复。
 * - **C 过期拦截**：06 的 2.6.1 约束②——计划中的药品必须在药箱有库存且未过期（否则后端
 *   40901）；前端用 `expiryTag` 预判，过期 / 无库存置灰，**库存数据缺失时不拦截**（后端兜底）。
 */

import { ROLE } from '@/store/user.js'
import { expiryTag } from '@/utils/stock.js'

/** `HH:mm` 24 小时制（picker mode="time" 的输出格式）。 */
const TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/

/**
 * 归一化提醒时间（规则 A）：格式校验 + 计划内去重 + 升序排序。
 *
 * <p>零填充的 `HH:mm` 字典序即时间序（"08:00" < "20:00"），与 02 示例一致；
 * 非法项（"25:00" / "8:0" / 空）直接剔除，由页面在保存前提示"至少添加一个有效时间"。
 *
 * @param {Array<string>} [times] 原始输入（picker 值或后端回显）
 * @returns {Array<string>} 合法、去重、升序的时间数组
 */
export function normalizeTimes(times) {
  if (!Array.isArray(times)) return []
  const seen = new Set()
  const valid = []
  times.forEach((item) => {
    const value = String(item || '').trim()
    if (!TIME_PATTERN.test(value) || seen.has(value)) return
    seen.add(value)
    valid.push(value)
  })
  return valid.sort()
}

/**
 * 表单 → `POST /plans` / `PUT /plans/{id}` 请求体（规则 B）。
 *
 * 规则：`name` trim；`times` 走 {@link normalizeTimes}；`repeatRule` 空串省略（不传即后端默认）；
 * `items` 逐项组装——`medicineId` 必填（缺失的项整体剔除），`dose`/`unit`/`note` trim 后空串省略；
 * **不传** `missAlarmAfterMin` / `alarmRule`（本期范围决策：走后端默认与老人级 `alarm_setting`，
 * 归 P1 `mp-alarm`）；`sortOrder` 由后端按 items 数组序生成，前端不传。
 *
 * @param {Object} form 页面表单
 * @param {string} form.name 方案名（如"早餐后"）
 * @param {Array<string>} [form.times] 提醒时间（设备本地墙钟）
 * @param {string} [form.repeatRule] DAILY / WEEKLY（枚举文档未列全，见 02 口径）
 * @param {Array<Object>} [form.items] `[{medicineId, dose?, unit?, note?}]`
 * @param {string} elderId 归属老人 `userId`
 * @returns {Object} 请求体
 */
export function buildPlanPayload(form, elderId) {
  const src = form || {}
  const payload = {
    elderId: String(elderId || '').trim(),
    name: String(src.name || '').trim(),
  }
  const times = normalizeTimes(src.times)
  if (times.length) payload.times = times
  const repeatRule = String(src.repeatRule || '').trim()
  if (repeatRule) payload.repeatRule = repeatRule
  payload.items = (Array.isArray(src.items) ? src.items : [])
    .map((item) => {
      const entry = {}
      const medicineId = String((item && item.medicineId) || '').trim()
      if (!medicineId) return null
      entry.medicineId = medicineId
      const dose = String((item && item.dose) || '').trim()
      if (dose) entry.dose = dose
      const unit = String((item && item.unit) || '').trim()
      if (unit) entry.unit = unit
      const note = String((item && item.note) || '').trim()
      if (note) entry.note = note
      return entry
    })
    .filter(Boolean)
  return payload
}

/**
 * 计划列表分页响应容错解析。
 *
 * <p>⚠️ 后端 `plan-core` 未落地，`GET /plans` 的响应形状未定：兼容 `{list}` / `{records}` /
 * `{items}` + `total` 与裸数组四种形状，后端定稿后只改这一处。
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

/**
 * 计划可选药品拆分（规则 C）：按老人在其可见设备上的**全部库存行**聚合判定。
 *
 * 同一药品可能出现在多台药箱：**任一设备有未过期库存即可加入**；该药品所有库存行都已过期
 * 才判 `expired`；没有任何库存行判 `no-stock`。库存数据拉取失败（空数组）时调用方按规则
 * "数据缺失不拦截"处理——本函数照实返回（全部 no-stock），由页面决定是否降级放行。
 *
 * @param {Array<Object>} medicines 药品字典（`listMedicines` 的 `list`）
 * @param {Array<Object>} [stockRows] 该老人在可见设备上的库存行（可跨设备，`parseStocks` 结果拼接）
 * @returns {{selectable: Array<{medicine: Object, expiry: Object}>, blocked: Array<{medicine: Object, reason: 'expired'|'no-stock'}>}}
 */
export function pickableMedicines(medicines, stockRows) {
  // 药品 → 'ok'（有未过期库存）/ 'expired'（有库存但全过期）
  const stockState = {}
  ;(Array.isArray(stockRows) ? stockRows : []).forEach((row) => {
    if (!row || !row.medicineId) return
    const expired = expiryTag(row.expiryDate).state === 'expired'
    const prev = stockState[row.medicineId]
    if (!prev) {
      stockState[row.medicineId] = expired ? 'expired' : 'ok'
    } else if (prev === 'expired' && !expired) {
      stockState[row.medicineId] = 'ok'
    }
  })
  const selectable = []
  const blocked = []
  ;(Array.isArray(medicines) ? medicines : []).forEach((medicine) => {
    if (!medicine || !medicine.medicineId) return
    const state = stockState[medicine.medicineId]
    if (state === 'ok') {
      selectable.push({ medicine, expiry: { state: 'ok', text: '正常' } })
    } else if (state === 'expired') {
      blocked.push({ medicine, reason: 'expired' })
    } else {
      blocked.push({ medicine, reason: 'no-stock' })
    }
  })
  return { selectable, blocked }
}

/**
 * 提醒时间展示文案（规则 A 回显：原样拼接，不格式化）。
 *
 * @param {Array<string>} [times]
 * @returns {string} 如 `08:00、20:00`；空则"未设置时间"
 */
export function planTimesText(times) {
  const list = normalizeTimes(times)
  return list.length ? list.join('、') : '未设置时间'
}

/**
 * 药品概览文案（02 第 6 章"列表项含药品概览"的前端兜底：后端未内嵌时用药品字典 join）。
 *
 * @param {Array<Object>} [items] 计划明细（`{medicineId, dose?, unit?}`）
 * @param {Object} [medicineMap] `medicineId -> medicine`（`toMedicineMap` 结果）
 * @returns {string} 如 `阿莫西林 1粒 + 布洛芬 10ml`；空则"未添加药品"
 */
export function planItemsText(items, medicineMap) {
  const list = Array.isArray(items) ? items : []
  if (!list.length) return '未添加药品'
  return list
    .map((item) => {
      const medicine = (medicineMap && medicineMap[item.medicineId]) || null
      const name = (medicine && medicine.name) || item.medicineId || '未知药品'
      const dose = [item.dose, item.unit].filter(Boolean).join('')
      return dose ? `${name} ${dose}` : name
    })
    .join(' + ')
}

/**
 * 当前角色是否可见计划写入口（创建 / 编辑 / 启停 / 删除）。
 *
 * <p>02 的 1.3 权限矩阵：`WRITE_PLAN` 仅 FAMILY ✅ + DOCTOR ✅，老人只读。注册角色只有
 * ELDER / GUARDIAN（relationRole 未暴露，P1 `mp-guardian` 才有），这里用 GUARDIAN 代理；
 * **服务端仍按矩阵强校验**，前端只决定显不显示。
 *
 * @param {string} role `store/user.js` 的角色
 * @returns {boolean}
 */
export function canWritePlan(role) {
  return role === ROLE.GUARDIAN
}

/** 重复周期文案（06 的 2.6 注释"DAILY / WEEKLY ..."枚举未列全，未知值原样返回）。 */
export function repeatRuleLabel(rule) {
  const labels = { DAILY: '每天', WEEKLY: '每周' }
  const value = String(rule || '').trim()
  if (!value) return '未设置周期'
  return labels[value] || value
}

/** 计划状态文案（06 的 2.6 `status` 默认 `ENABLED`；`DISABLED` 为前端启停契约取值）。 */
export function planStatusLabel(status) {
  const labels = { ENABLED: '启用中', DISABLED: '已停用' }
  const value = String(status || '').trim()
  if (!value) return '未知状态'
  return labels[value] || value
}

/** 同步设备状态文案（02 第 6 章响应 `syncState`；已知值映射，未知原样返回）。 */
export function syncStateLabel(syncState) {
  const labels = { SYNCED_TO_DEVICE: '已同步设备' }
  const value = String(syncState || '').trim()
  return labels[value] || value
}

/**
 * 未来待服药时间线响应容错解析（feat/mp-plan-timeline；02 第 6 章 `next-doses`）。
 *
 * <p>⚠️ 后端 `next-doses` 响应外壳未定稿示例：兼容 `{doses:[...]}` 与裸数组两种，后端定稿后只改这一处。
 *
 * @param {Object|Array|null} data 请求层兑现的 `data`
 * @returns {Array<Object>} 服药点数组（每项原样保留 `scheduledTimeUtc` / `scheduledTimeLocal` / `items` 等字段）
 */
export function parseNextDoses(data) {
  if (Array.isArray(data)) return data
  const src = data || {}
  if (Array.isArray(src.doses)) return src.doses
  return []
}

/**
 * 服药点 → 展示视图（feat/mp-plan-timeline）。
 *
 * <p>规则：直接展示后端 `scheduledTimeLocal`（设备本地墙钟，**前端不换算**），拆出本地日期
 * `YYYY-MM-DD` 与 `HH:mm` 供分组/排序；`items` 映射为药品+剂量明细（缺字段兜底）。
 *
 * @param {Object} dose 后端服药点（含 `scheduledTimeLocal` / `items`）
 * @returns {Object} `{day, time, scheduledTimeLocal, items}`，缺 `scheduledTimeLocal` 时 day/time 为空串
 */
export function toDoseView(dose) {
  const src = dose || {}
  const local = String(src.scheduledTimeLocal || '').trim()
  // "2026-10-27T08:00:00" → day="2026-10-27"、time="08:00"
  const day = local.length >= 10 ? local.slice(0, 10) : ''
  const time = local.length >= 16 ? local.slice(11, 16) : ''
  const items = Array.isArray(src.items) ? src.items.map(toDoseItemView) : []
  return { day, time, scheduledTimeLocal: local, items }
}

/** 服药点明细 → 展示视图（药品+剂量文案，缺字段兜底）。 */
function toDoseItemView(item) {
  const src = item || {}
  const medicineId = String(src.medicineId || '').trim()
  const name = String(src.medicineName || src.name || '').trim() || (medicineId || '未知药品')
  const dose = String(src.dose || '').trim()
  const unit = String(src.unit || '').trim()
  const text = [dose, unit].filter(Boolean).join('')
  return {
    medicineId,
    name,
    dose,
    unit,
    text: text ? `${name} ${text}` : name,
  }
}

/**
 * 服药点按本地日期分组（feat/mp-plan-timeline）。
 *
 * <p>返回按 `day` 升序的分组数组，组内服药点按 `time` 升序，便于时间线渲染；空输入返回空数组。
 *
 * @param {Array<Object>} doses `parseNextDoses` + `toDoseView` 后的视图数组（每项含 `day` / `time`）
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
