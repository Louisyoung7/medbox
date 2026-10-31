/**
 * 药品域纯函数（`feat/mp-medicine`，对应 medbox-spec/02 第 5 章、06 的 2.4）。
 *
 * <p>只放与页面解耦的可单测逻辑：表单 → 请求体组装、分页响应容错解析、列表文案、
 * 写权限判断。时间展示一律走 `utils/datetime.js`，本文件不碰时间。
 */

import { ROLE } from '@/store/user.js'

/**
 * 表单 → `POST /medicines` 请求体（02 第 5 章"新建/更新"同一入口）。
 *
 * 规则：
 * - 字符串字段 trim；**空串一律省略**（后端全局 `non_null` 下省略即"不更新该字段"）
 * - `ownerElderId` 为空即**公共字典**（不传该字段，02 第 5 章口径）
 * - `medicineId` 仅编辑时带上（新建不带，由后端生成）
 * - `name` 为空时**原样省略**，由页面先做必填校验拦住（40001 兜底）
 *
 * @param {Object} form 页面表单
 * @param {string} [form.medicineId] 编辑时的药品编号；新建传空
 * @param {string} [form.name] 药品名
 * @param {string} [form.spec] 规格
 * @param {string} [form.dosePerTime] 默认单次剂量
 * @param {string} [form.unit] 单位（片/粒/ml...）
 * @param {string} [form.contraindication] 禁忌
 * @param {string} [form.storageCond] 储存条件
 * @param {string} [form.ownerElderId] 归属老人；空 = 公共
 * @returns {Object} 请求体（只含非空字段）
 */
export function buildMedicinePayload(form) {
  const src = form || {}
  const payload = {}
  const text = (value) => (value == null ? '' : String(value).trim())
  const put = (key, value) => {
    const trimmed = text(value)
    if (trimmed) payload[key] = trimmed
  }
  const id = text(src.medicineId)
  if (id) payload.medicineId = id
  put('name', src.name)
  put('spec', src.spec)
  put('dosePerTime', src.dosePerTime)
  put('unit', src.unit)
  put('contraindication', src.contraindication)
  put('storageCond', src.storageCond)
  put('ownerElderId', src.ownerElderId)
  return payload
}

/**
 * 分页响应容错解析。
 *
 * <p>⚠️ **后端 `medicine-core` 未落地**，`GET /medicines` 分页响应的字段名未定：
 * 02 只约定了 query 参数与 `total`。这里兼容 `list` / `records` / `items` 三种常见命名，
 * 后端定稿后（若不一致）只改这一处，页面不受影响；解析失败按空页处理（`total` 0）。
 *
 * @param {Object|null} data 请求层兑现的 `data`
 * @returns {{list: Array<Object>, total: number}}
 */
export function parseMedicinePage(data) {
  const src = data || {}
  let list = []
  if (Array.isArray(src.list)) {
    list = src.list
  } else if (Array.isArray(src.records)) {
    list = src.records
  } else if (Array.isArray(src.items)) {
    list = src.items
  }
  const total = typeof src.total === 'number' ? src.total : list.length
  return { list, total }
}

/**
 * 列表行副标题：规格 · 默认剂量+单位（缺字段自动省略）。
 *
 * @param {Object} medicine 药品档案
 * @returns {string} 如 `0.1g×24片 · 1片`；都缺时回退"药品档案"
 */
export function medicineLabel(medicine) {
  const m = medicine || {}
  const parts = []
  if (m.spec) parts.push(String(m.spec))
  const dose = [m.dosePerTime, m.unit].filter(Boolean).join('')
  if (dose) parts.push(dose)
  return parts.length ? parts.join(' · ') : '药品档案'
}

/**
 * 归属标签：公共药品 / 我的私有药品 / 某老人的私有药品。
 *
 * @param {Object} medicine 药品档案（`ownerElderId` 为空 = 公共）
 * @param {string} [myUserId] 当前登录用户 ID（`ownerElderId` 与它相等时显示"我的"）
 * @returns {string}
 */
export function medicineOwnerLabel(medicine, myUserId) {
  const m = medicine || {}
  const owner = m.ownerElderId
  if (!owner) return '公共'
  if (myUserId && owner === myUserId) return '我的'
  return `私有 · ${owner}`
}

/**
 * 当前角色是否可见药品写入口（新建 / 编辑 / 删除）。
 *
 * <p>02 的 1.3 权限矩阵：`WRITE_MEDICINE` 仅 **GUARDIAN（监护人）** ✅，老人只读。
 * 注册角色只有 ELDER / GUARDIAN，这里用 GUARDIAN 代理；**服务端仍按矩阵强校验**，
 * 前端只决定显不显示。
 *
 * @param {string} role `store/user.js` 的角色（ELDER / GUARDIAN / 空串）
 * @returns {boolean}
 */
export function canWriteMedicine(role) {
  return role === ROLE.GUARDIAN
}
