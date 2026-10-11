/**
 * 入口角色可见性（`feat/mp-ui-kit`，对应 08 的"按角色切换可见入口"与 01 第 3 章）。
 *
 * <p>当前小程序注册角色仅 `ELDER` / `GUARDIAN`（见 `src/store/user.js` 的 {@link ROLE} 与
 * 06 的 2.1）；护理 / 医生是 `guardian_relation.role`（FAMILY / DOCTOR / NURSE），非注册角色，
 * 本模块预留扩展位（下游分支细化）。
 *
 * <p>**白名单式**：`ENTRY_ROLES` 未登记的入口对所有角色可见；只把"需要限制"的入口登记进去，
 * 改入口可见性只改这份配置，组件与页面不写死角色判断。
 *
 * <p>纯函数、可单测（见 `__tests__/permission.test.js`）。
 */

import { ROLE } from '@/store/user.js'

/**
 * 各入口允许的角色白名单（key 与 `config/tabbar.js` 的 `TAB_ENTRIES[].key` 对齐）。
 *
 * <p>本期骨架先放开（告警设置 / 计划管理等下游分支再细化），仅作示例占位。
 *
 * @example
 * alarmSettings: [ROLE.GUARDIAN],
 * planManage:    [ROLE.GUARDIAN],
 */
export const ENTRY_ROLES = {
  // 示例（下游分支取消注释并细化）：
  // alarmSettings: [ROLE.GUARDIAN],
  // planManage:    [ROLE.GUARDIAN],
}

/**
 * 某角色能否访问某入口。
 *
 * @param {string} role 当前用户角色（ELDER / GUARDIAN / 空串 = 未登录）
 * @param {string} entryKey 入口 key
 * @returns {boolean}
 */
export function canAccess(role, entryKey) {
  const allowed = ENTRY_ROLES[entryKey]
  if (!allowed || allowed.length === 0) return true
  if (!role) return false
  return allowed.includes(role)
}

/**
 * 过滤出当前角色可见的入口集合（供 TabBar / 列表批量用，保持原序）。
 *
 * @param {string} role 当前用户角色
 * @param {Array<{key:string}>} entries 含 `key` 的入口数组
 * @returns {Array} 过滤后的数组（非数组返回空）
 */
export function visibleEntries(role, entries) {
  if (!Array.isArray(entries)) return []
  return entries.filter((e) => canAccess(role, e && e.key))
}
