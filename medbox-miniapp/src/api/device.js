/**
 * 设备域接口（medbox-spec/02 第 4 章）—— 全部需鉴权（默认 `auth: true`，不带 Bearer 会 40101）。
 *
 * <p>`feat/mp-device` 只落地列表与详情两个读取；事件流水（`/events`）归 P1 `mp-device-extra`，
 * 实时状态（`/status`）与注册（`POST /devices/register`）本期页面用不到，故不在此导出。
 */

import { get } from '@/api/request.js'

/**
 * 设备列表：当前用户可见的药箱主控（**纯数组**，后端未分页，不要按 PageResult 解析）。
 *
 * @returns {Promise<Array<Object>>} `DeviceSummaryResponse[]`：`deviceId` / `deviceType`(恒 MAIN) /
 *   `elderId` / `productKey` / `timezone` / `onlineState`("ONLINE"|"OFFLINE") /
 *   `lastHeartbeat`(UTC，从未上线则**整个字段缺失**) / `sensorCount`
 * @throws {ApiError} 40101 / -1（网络）
 */
export function listDevices() {
  return get('/devices')
}

/**
 * 设备详情：含传感器子设备数组（**后端没有 `GET /devices/{id}/sensors`**，子设备从这里取）。
 *
 * @param {string} deviceId 药箱**主控**编号（传子设备编号会 40301）
 * @returns {Promise<Object>} `DeviceDetailResponse`：列表字段 + `createdAt` + `sensors[]`
 *   （每项 `deviceId` / `deviceType`("TEMP_HUMI"|"LIGHT"|"CAMERA") / `onlineState` / `lastHeartbeat`）
 * @throws {ApiError} 40001 / 40101 / 40301（不存在、他人设备、子设备编号）/ 40302 / -1
 */
export function getDevice(deviceId) {
  return get(`/devices/${deviceId}`)
}
