/**
 * 设备域接口（medbox-spec/02 第 4 章）—— 全部需鉴权（默认 `auth: true`，不带 Bearer 会 40101）。
 *
 * <p>`feat/mp-device` 落地列表与详情两个读取；`feat/mp-device-extra` 在此补事件流水（`/events`）
 * 与命令下发（`POST /commands`），实时状态（`/status`）与注册（`POST /devices/register`）本期页面
 * 用不到，故不在此导出。
 */

import { get, post } from '@/api/request.js'

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

/**
 * 设备事件流水（`feat/mp-device-extra`，对应 medbox-spec/02 第 4 章 `GET /devices/{deviceId}/events`）。
 *
 * <p>分页、按 `created_at` 倒序（最新在前）、`page` 从 1 起、默认 size 20（详见 `utils/device.js`
 * 的 `parseEventsPage` 容错 `{list,total}` / `{records,total}` / `{items,total}` / 裸数组）。
 *
 * @param {string} deviceId 药箱**主控**编号（子设备编号 → 40301）
 * @param {{page?:number, size?:number}} [query] 分页参数（`page`/`size` < 1 → 40001）
 * @returns {Promise<{list:Array, total:number}>}
 * @throws {ApiError} 40001 / 40101 / 40301（不存在、他人设备、子设备编号）/ 40302 / -1
 */
export function getDeviceEvents(deviceId, query) {
  return get(`/devices/${deviceId}/events`, query || {})
}

/**
 * 下发控制命令（`feat/mp-device-extra`，对应 medbox-spec/02 第 4 章 `POST /devices/{deviceId}/commands`）。
 *
 * <p>异步受理：响应只回 `{commandId, state:"PENDING"}`，回执结果（PENDING→SUCCESS/FAILED）经
 * `GET /devices/{deviceId}/events` 的 COMMAND 事件反映（后端无命令状态 GET、无命令 WS 推送帧，见 02 第 4 章）。
 * `cmd` ∈ 蜂鸣 BUZZ / 解锁 UNLOCK / 校准 CALIBRATE / 重启 REBOOT（03 第 2 章 `down/command`）；
 * 设备离线 → 40901。写操作，自动注入 `X-Request-Id`。
 *
 * @param {string} deviceId 药箱**主控**编号（子设备编号 → 40301）
 * @param {string} cmd 命令枚举
 * @param {Object} [params] 命令参数（如蜂鸣 `{durationSec:5}`）
 * @param {string} [operator] 操作人（当前用户 ID；后端只作留痕，缺省时按上下文补）
 * @returns {Promise<{commandId:string, state:string}>}
 * @throws {ApiError} 40001（cmd 非法）/ 40101 / 40301 / 40302 / 40901（设备离线）/ -1
 */
export function sendDeviceCommand(deviceId, cmd, params, operator) {
  return post(`/devices/${deviceId}/commands`, { cmd, params: params || {}, operator: operator || '' })
}
