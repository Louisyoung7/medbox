/**
 * 开发调试辅助（纯薄封装，`feat/mp-devtools`，对应 medbox-spec/08 第四章）。
 *
 * <p>配置出口 `src/config/index.js` 已提供 HOST 的读写 / 归一化（并各自带单测），
 * 本模块只是把它们收拢成调试页要用的三个动作：读当前覆盖、写、恢复默认。
 *
 * <p>**为什么要单独抽一层**：可测的逻辑不写在 `.vue` 里 —— 组件测试要挂载 DOM，
 * 而这里三个函数都是"调 config + 返回结果"，抽出来即可用 `stubUni` 内存 storage
 * 直接单测（复用 `config/__tests__/index.test.js` 的模式）。
 */

import {
  getServerHost,
  setServerHost,
  clearServerHost,
} from '@/config/index.js'

/**
 * 读取当前已设置的 HOST（运行时覆盖），未设置时返回空串。
 *
 * @returns {string} 空串表示仍走 `.env.local` / `.env` 的编译期默认值
 */
export function readServerHost() {
  return getServerHost()
}

/**
 * 写入 HOST 覆盖，内部先归一化（容忍粘贴 `http://192.168.1.23:8080/medbox/` 整串）。
 *
 * @param {string} input 用户输入；空串 / 非字符串 / 纯协议等非法输入不会写入缓存
 * @returns {boolean} 是否写入成功 —— 页面据此决定提示"已保存"还是"地址无效"
 */
export function saveServerHost(input) {
  return setServerHost(input)
}

/** 清除运行时覆盖，回落到编译期默认值（`VITE_SERVER_HOST` → 兜底 `127.0.0.1`）。 */
export function resetServerHost() {
  clearServerHost()
}

/**
 * 服药类预设的"计划时间槽"计数器 key。
 *
 * <p>**为什么必须持久化**：后端 `med_record` 有 `(plan_item_id, plan_time)` 部分唯一索引，
 * 同槽只落一条（06 的 2.7 规则 6），而 devtools 种子只有 1 个计划项 × 每日 08:00 ——
 * 槽位是有限资源。计数器每次发送前移一天，刷新小程序后若归零，又会从今天开始撞已占用的槽。
 */
const SLOT_SEQ_KEY = 'simSlotSeq'

/**
 * 取下一个还没用过的日历天偏移，并把计数器前移（持久化到本地缓存）。
 *
 * <p>**每次调用一定返回一个新值**：即使上一条被后端幂等吃掉（页面无从得知），
 * 计数器也已经前移，下一次点击不会重复撞同一个槽 —— 绝不会"点一次就废"。
 *
 * @returns {number} 日历天偏移（0=今天 / -1=昨天 / -2=前天 ...）
 */
export function takeNextSlotOffset() {
  const current = Number(uni.getStorageSync(SLOT_SEQ_KEY)) || 0
  uni.setStorageSync(SLOT_SEQ_KEY, current + 1)
  // 避开 -0：`Object.is(-0, 0)` 为 false，调用方与测试用 toBe / === 比较都会莫名失败
  return current === 0 ? 0 : -current
}

/** 当前槽计数器值（仅供调试页展示"下一个槽会是哪天"）。 */
export function readSlotSeq() {
  return Number(uni.getStorageSync(SLOT_SEQ_KEY)) || 0
}
