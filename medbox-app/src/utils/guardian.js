/**
 * 监护关系域纯函数（`feat/mp-guardian`，对应 medbox-spec/02 第 3 章的 3.2、06 的 2.2）。
 *
 * <p>只放与页面解耦的可单测逻辑：关系状态文案、待确认列表容错解析、老人用户ID
 * 归一化、申请时间展示。**不实现任何判定规则** —— 监护关系的判定（40301 / 40302 / 40901）
 * 全部在服务端（02 的 3.2 规则表），前端只按错误码渲染文案。
 *
 * <p>监护关系**无角色**：spec 已将监护人统一为单一 `GUARDIAN` 角色（02 的 1.3 权限矩阵仅
 * `ELDER` / `GUARDIAN` 两行），确认监护关系时不再指定 `FAMILY` / `DOCTOR` / `NURSE`。
 */

import { formatRelative } from '@/utils/datetime.js'

/**
 * 关系状态枚举（`guardian_relation.status`，06 的 2.2）。
 */
export const RELATION_STATUS = {
  PENDING: 'PENDING',
  ACTIVE: 'ACTIVE',
  REVOKED: 'REVOKED',
}

/**
 * 关系状态标签（直接映射服务端字段，不做判定）。
 *
 * <p>优先级：`PENDING` → 待确认（warn，琥珀色徽标）；`ACTIVE` → 已生效（ok）；
 * `REVOKED` / 未知 → 已解除 / 原样（info）。`level` 仅作配色语义，页面自行映射样式类
 * （同 `utils/record.js` 的 {@link recordStatusTags}）。
 *
 * @param {string} [status]
 * @returns {{text: string, level: 'ok'|'warn'|'danger'|'info'}}
 */
export function relationStatusTag(status) {
  switch (status) {
    case RELATION_STATUS.PENDING:
      return { text: '待确认', level: 'warn' }
    case RELATION_STATUS.ACTIVE:
      return { text: '已生效', level: 'ok' }
    case RELATION_STATUS.REVOKED:
      return { text: '已解除', level: 'info' }
    default:
      return { text: status || '未知', level: 'info' }
  }
}

/**
 * 老人用户ID归一化：去首尾空白；空串 / 非字符串按空处理（页面据此禁用提交）。
 *
 * <p>**只做 trim，不做格式校验**：`userId` 的合法性由服务端判定（目标不是老人 → 40301），
 * 前端预校验格式反而会把合法 ID 挡在门外。
 *
 * @param {*} input 页面输入框的原始值
 * @returns {string} 归一化后的 elderId；无效输入返回空串
 */
export function normalizeElderId(input) {
  if (typeof input !== 'string') return ''
  return input.trim()
}

/**
 * 待确认列表响应容错解析（`GET /users/guardians/pending`）。
 *
 * <p>02 的 3.2 定义为裸数组；兼容 `{list}` / `{items}` 包装与 null，后端若调整形状只改这一处。
 *
 * @param {Array<Object>|Object|null} data 请求层兑现的 `data`
 * @returns {{list: Array<Object>, total: number}}
 */
export function parsePendingList(data) {
  if (Array.isArray(data)) return { list: data, total: data.length }
  const src = data || {}
  let list = []
  if (Array.isArray(src.list)) list = src.list
  else if (Array.isArray(src.items)) list = src.items
  return { list, total: list.length }
}

/**
 * 申请时间展示：相对时间（刚刚 / N 分钟前 / 昨天 / N 天前，超过一周回退绝对日期）。
 *
 * <p>`createdAt` 为 UTC（ISO-8601），本地化由 `utils/datetime.js` 处理（02 通用约定）。
 *
 * @param {*} createdAt 关系创建时间（UTC）
 * @returns {string} 解析失败返回 '-'
 */
export function relationTimeText(createdAt) {
  return formatRelative(createdAt)
}
