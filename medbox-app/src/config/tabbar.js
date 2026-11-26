/**
 * 导航入口集中配置（`feat/mp-nav-drawer`）：底部栏只放主入口，其余收进右上「三横杠」抽屉。
 *
 * <p>增删入口 / 调顺序 / 调角色可见性只改这里（角色限制走 `utils/permission.js` 的
 * `ENTRY_ROLES`，本配置只描述入口本身）。`key` 须与 `ENTRY_ROLES` 的键对齐，
 * `path` 须是 `pages.json` 已注册的页面；`tab: true` 表示一级页（底部栏 / 抽屉切换用
 * `reLaunch`，页面栈只留一个），省略则按二级页 `navigateTo` 入栈（保留返回）。
 * **首页「功能入口」卡片不走此语义**：4 个主入口统一 `navigateTo` 入栈，确保有返回键。
 */

import { HOME_PATH } from '@/store/guard.js'

/** 底部栏主入口（4 个）：日常高频，必须一屏可点。 */
export const TAB_ENTRIES = [
  { key: 'home', title: '首页', path: HOME_PATH, icon: 'home', tab: true },
  { key: 'medicine', title: '药品', path: '/pages/medicine/medicine', icon: 'medicine', tab: true },
  { key: 'plan', title: '计划', path: '/pages/plan/plan', icon: 'plan', tab: true },
  { key: 'record', title: '记录', path: '/pages/record/record', icon: 'record', tab: true },
]

/** 侧边栏次级入口：从右上「三横杠」抽屉进入，不占底部空间。 */
export const DRAWER_ENTRIES = [
  { key: 'device', title: '我的设备', path: '/pages/device/device', icon: 'device', tab: true },
  { key: 'ai', title: 'AI 健康洞察', path: '/pages/ai/insight', icon: 'ai', tab: true },
  { key: 'alarm', title: '告警', path: '/pages/alarm/list', icon: 'alarm' },
  { key: 'guardian', title: '监护关系', path: '/pages/guardian/guardian', icon: 'guardian' },
  { key: 'profile', title: '我的', path: '/pages/profile/profile', icon: 'profile', tab: true },
]

export { HOME_PATH }
