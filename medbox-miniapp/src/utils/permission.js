/**
 * 入口角色可见性（`feat/mp-ui-kit`，对应 08 的"按角色切换可见入口"与 01 第 3 章）。
 *
 * <p>当前小程序注册角色仅 `ELDER` / `GUARDIAN`（见 `src/store/user.js` 的 {@link ROLE} 与
 * 06 的 2.1）；监护关系已统一为单一 `GUARDIAN` 角色，不再区分护理 / 医生子角色。
 *
 * <p>**白名单式**：`ENTRY_ROLES` 未登记的入口对所有角色可见；只把"需要限制"的入口登记进去，
 * 改入口可见性只改这份配置，组件与页面不写死角色判断。
 *
 * <p>纯函数、可单测（见 `__tests__/permission.test.js`）。
 */

import { ROLE } from '@/store/user.js'

/**
 * 各入口允许的角色白名单（key 与 `config/tabbar.js` 的 `TAB_ENTRIES[].key` 对齐，
 * 页面内卡片用的 `RoleGate entry` 也走同一份配置）。
 *
 * <p>`feat/mp-profile` 登记首批两项（此前为空的占位，导致页面里 `RoleGate` 包着的卡片
 * 实际对所有角色可见）：
 *
 * - `alarmSettings`「监护台」（告警设置 / 环境阈值 / AI Key，归 `feat/mp-alarm` 等后续分支）：
 *   仅监护人；
 * - `elderBindGuide`「告诉监护人」绑定引导卡：仅老人（监护人不需要被引导去绑定自己）；
 * - `deviceCommand`「远程控制」（设备命令下发，归 `feat/mp-device-extra`）：仅监护人
 *   （02 的 1.3 矩阵 `WRITE_DEVICE` 仅 GUARDIAN）；
 * - `alarmHandle`「处理/确认告警」（归 `feat/mp-alarm`）：仅监护人（02 第 8 章 `HANDLE_ALARM`
 *   仅 GUARDIAN，真后端仍按 `HANDLE_ALARM` 强校验）。
 *
 * @example
 * alarmSettings: [ROLE.GUARDIAN],
 * elderBindGuide: [ROLE.ELDER],
 * deviceCommand: [ROLE.GUARDIAN],
 * alarmHandle: [ROLE.GUARDIAN],
 */
export const ENTRY_ROLES = {
  alarmSettings: [ROLE.GUARDIAN],
  elderBindGuide: [ROLE.ELDER],
  deviceCommand: [ROLE.GUARDIAN],
  alarmHandle: [ROLE.GUARDIAN],
  // 抓拍删除（02 §4.1.2 ⑥）：WRITE_CAPTURE 仅 GUARDIAN，老人本人 / 护理 / 医生 40301
  writeCapture: [ROLE.GUARDIAN],
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
