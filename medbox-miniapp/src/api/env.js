/**
 * 环境监测接口（medbox-spec/02_环境监测.md 第 9 章）—— 全部需鉴权（默认 `auth: true`，不带 Bearer 会 40101）。
 *
 * <p>`feat/mp-env` 落地「实时温湿光读数」与「历史曲线」；后端 `env-core`（`feat/env-core`）已落地（07），
 * `alarm-core` 的 `envThreshold` 阈值仅供前端**高亮读取**、不在此配置。本文件只消费，不告警、不推 WS。
 *
 * <p>权限矩阵（02 第 9 章）：`/env/realtime`、`/env/history` 走 `requireDevice(deviceId, READ_ENV)`，
 * `READ_ENV` 老人本人 + 监护人全角色只读，前端不拦。越权 / 设备查不到一律 40301（防设备编号枚举）；
 * 子设备编号（如 `BOXA1001-S-TH01`）当 `deviceId` 传也按 40301 处理。
 */

import { get } from '@/api/request.js'

/**
 * 最新温湿光读数（合并各传感器最新非空读数）。
 *
 * @param {string} deviceId 药箱主控（必填）
 * @param {{sensorId?:string}} [options] sensorId 可选，按传感器查
 * @returns {Promise<Object>} `{ temperature, humidity, lux, sampleTime }`（某传感器无数据则该字段 `null`）
 * @throws {ApiError} 40101 / 40301（越权/不存在）/ -1
 */
export function getEnvRealtime(deviceId, { sensorId } = {}) {
  return get('/env/realtime', { deviceId, sensorId })
}

/**
 * 历史曲线（小程序图表）。
 *
 * @param {string} deviceId 药箱主控（必填）
 * @param {{sensorId?:string, from:string, to:string, interval?:number}} options
 *   `from` / `to` 必填（ISO-8601 UTC，含端点）；`interval` 可选（分钟，1~1440，分桶 avg 聚合；缺省原始有序点）。
 * @returns {Promise<Object>} `{ points: [{ sampleTime, temperature, humidity, lux }] }`（单行可能只带一类数值、其余为 `null`）
 * @throws {ApiError} 40001（from/to 缺省 / from>to / interval 越界 / 跨度 >30 天）/ 40101 / 40301 / -1
 */
export function getEnvHistory(deviceId, { sensorId, from, to, interval } = {}) {
  return get('/env/history', { deviceId, sensorId, from, to, interval })
}
