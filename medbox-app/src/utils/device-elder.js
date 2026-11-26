/**
 * 设备列表的「老人区分」纯函数（本分支：登记输入修复 + 老人姓名展示）。
 *
 * <p>只做数据与文案编排，不发请求：请求由页面调 `api/user.js` 的 `getElderProfile`，
 * 单条失败由页面静默降级；本模块复用 `utils/profile.js` 的 `elderNameText` 做最终展示。
 */

import { elderNameText } from '@/utils/profile.js'

/**
 * 从设备列表归纳去重后的老人 ID（去首尾空白，忽略空值）。
 *
 * @param {Array<{elderId?: string}>} devices `GET /devices` 返回的纯数组
 * @returns {string[]} 去重后的 elderId，顺序按首次出现
 */
export function collectElderIds(devices = []) {
  const ids = []
  ;(devices || []).forEach((device) => {
    const id = device && device.elderId ? String(device.elderId).trim() : ''
    if (id && ids.indexOf(id) === -1) ids.push(id)
  })
  return ids
}

/**
 * 把并发请求结果整理成 `{ elderId: profile|null }`。
 *
 * <p>失败项（40301 / 40302 / 网络）统一记 null，由 `elderNameText` 回退成 elderId 原文。
 *
 * @param {string[]} elderIds 与 settledResults 一一对应的 ID 列表
 * @param {Array<{status: string, value?: Object}>} settledResults Promise 包装结果
 * @returns {Object<string, Object|null>}
 */
export function buildElderProfiles(elderIds = [], settledResults = []) {
  const profiles = {}
  ;(elderIds || []).forEach((elderId, index) => {
    const result = settledResults[index]
    profiles[elderId] = result && result.status === 'fulfilled' && result.value ? result.value : null
  })
  return profiles
}

/**
 * 设备列表项的老人文案：有姓名 → "老人 张桂英（u-1001）"，否则 → "老人 u-1001"。
 *
 * @param {{elderId?: string}} device 设备列表项
 * @param {Object<string, Object|null>} profiles elderId → 老人资料（失败为 null）
 * @returns {string}
 */
export function deviceElderLabel(device, profiles = {}) {
  if (!device || !device.elderId) return '药箱主控'
  const profile = profiles[device.elderId] || null
  return `老人 ${elderNameText(profile, device.elderId)}`
}
