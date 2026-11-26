/**
 * 环境监测接口（medbox-spec/02g_环境监测.md 第 1 章）—— 全部需鉴权（默认 `auth: true`，不带 Bearer 会 40101）。
 *
 * <p>环境阈值仅供前端高亮读取、不在此配置。系统只建模药箱主控一种设备：
 * 温湿度 / 光照由主控直接上报，查询按主控 `deviceId`，不再有 `sensorId`。
 *
 * <p>权限：`/env/realtime`、`/env/history` 走 `requireDevice(deviceId, READ_ENV)`；
 * 越权 / 设备查不到一律 40301。
 */

import { get } from '@/api/request.js'

/**
 * 最新温湿光读数（合并最新非空读数）。
 *
 * @param {string} deviceId 药箱主控（必填）
 * @returns {Promise<Object>} `{ temperature, humidity, lux, sampleTime }`（无数据的字段为 `null`）
 * @throws {ApiError} 40101 / 40301 / -1
 */
export function getEnvRealtime(deviceId) {
  return get('/env/realtime', { deviceId })
}

/**
 * 历史曲线（环境监测图表）。
 *
 * @param {string} deviceId 药箱主控（必填）
 * @param {{from:string, to:string, interval?:number}} options
 *   `from` / `to` 必填（ISO-8601 UTC，含端点）；`interval` 可选（分钟，1~1440，分桶 avg 聚合；缺省原始有序点）。
 * @returns {Promise<Object>} `{ points: [{ sampleTime, temperature, humidity, lux }] }`
 * @throws {ApiError} 40001 / 40101 / 40301 / -1
 */
export function getEnvHistory(deviceId, { from, to, interval } = {}) {
  return get('/env/history', { deviceId, from, to, interval })
}
