/**
 * TabBar 入口集中配置（`feat/mp-ui-kit`，驱动 `MbTabBar`）。
 *
 * <p>增删入口 / 调顺序 / 调角色可见性只改这里（角色限制走 `utils/permission.js` 的
 * `ENTRY_ROLES`，本配置只描述入口本身）。`key` 须与 `ENTRY_ROLES` 的键对齐，
 * `path` 须是 `pages.json` 已注册的页面。
 */

import { HOME_PATH } from '@/store/guard.js'

/** TabBar 入口：key / 标题 / 路径 / 图标名（图标 SVG 见 `MbTabBar.vue`）。 */
export const TAB_ENTRIES = [
  { key: 'home', title: '首页', path: HOME_PATH, icon: 'home' },
  { key: 'device', title: '设备', path: '/pages/device/device', icon: 'device' },
  { key: 'medicine', title: '药品', path: '/pages/medicine/medicine', icon: 'medicine' },
  { key: 'plan', title: '计划', path: '/pages/plan/plan', icon: 'plan' },
  { key: 'record', title: '记录', path: '/pages/record/record', icon: 'record' },
  { key: 'profile', title: '我的', path: '/pages/profile/profile', icon: 'profile' },
]

export { HOME_PATH }
