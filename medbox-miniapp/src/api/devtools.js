/**
 * 联调辅助调试端点（对应 medbox-spec/02 的 1.5、03 第 5 章）。
 *
 * <p>**由后端 `medbox.devtools.enabled` 门禁控制**：false 时 `DevtoolsController` 不装配，
 * 访问返回 404，调用方要给出「后端未开启」的明确提示（见 `utils/devtools-sim.js` 的
 * `devtoolsErrorMessage`）。端点免登录，这里仍按默认 `auth: true` 发请求。
 *
 * <p>固定 3 药格模型：种子计划只含 `slots`（格号 → 开格时间），SeedResult 不再返回
 * 计划项 ID；服药事件由设备经 HTTP captures 上报，MQTT 预设只用于验证消息到达。
 */

import { post } from '@/api/request.js'

/**
 * 一键播种最小链路数据集（监护人 + 老人 + 药箱 + 传感器 + 药品/库存 + 按格计划）。
 *
 * <p>**幂等**：种子已存在时跳过写入（`created=false`），token 仍重新签发。
 *
 * @param {boolean} [reset=false] true 时先清后种（破坏性，页面默认不暴露该入口）
 * @param {Object} [options] 透传请求选项
 * @returns {Promise<Object>} `SeedResult`：
 *   `{created, guardianUserId, guardianPhone, guardianToken, elderUserId, elderPhone,
 *     elderToken, deviceId, medicineIds, stockId, planId}`
 * @throws {ApiError} 网络失败（-1）；后端未开启调试端点时见 `devtoolsErrorMessage`
 */
export function seedDebugData(reset = false, options) {
  return post('/_debug/seed', undefined, { ...options, params: { reset: reset ? 'true' : '' } })
}

/**
 * 服务端模拟设备上报：把报文发到 `medbox/{productKey}/{deviceId}/up/{subTopic}`，
 * 等价于 `mosquitto_pub`，流经真实入站网关。
 *
 * <p>**`payload` 必须是 JSON 字符串**（后端 DTO 为 `String` 并原样透传），用
 * `utils/devtools-sim.js` 的 `buildSimPayload` 生成。
 *
 * @param {Object} params `{deviceId, subTopic, payload, productKey?}`
 * @param {Object} [options] 透传请求选项
 * @returns {Promise<null>} 成功即 `R.ok()`（data 为 null）
 */
export function publishDeviceEvent({ deviceId, subTopic, payload, productKey }, options) {
  return post('/_debug/mqtt/publish', { deviceId, subTopic, payload, productKey }, options)
}
