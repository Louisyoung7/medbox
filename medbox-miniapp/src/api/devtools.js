/**
 * 联调辅助调试端点（`feat/mp-devtools`，对应 medbox-spec/02 的 1.5、03 第 5 章）。
 *
 * <p>**这两个端点由后端 `medbox.devtools.enabled` 门禁控制**：该属性为 false 时
 * `DevtoolsController` 根本不装配，访问返回 404（详见 `WebMvcConfig`）。
 * 因此它们**只服务于本地联调**，页面在调用失败时要给出"后端未开启"的明确提示，
 * 而不是把 404 糊成一句"服务端内部错误"（见 `utils/devtools-sim.js` 的
 * {@link devtoolsErrorMessage}）。
 *
 * <p>**鉴权口径**：`WebMvcConfig` 把 `_debug/seed` 与 `_debug/mqtt/**` 排除在
 * `AuthInterceptor` 之外（免登录，理由见 `DevtoolsController` 的类注释）。这里仍按
 * 默认 `auth: true` 发请求 —— 有 token 就带上（与 02 §1.5 的 curl 示例一致，
 * 且后端若将来改回强制登录也无需改前端），未登录时请求层本就不注入头。
 *
 * ```js
 * import { seedDebugData, publishDeviceEvent } from '@/api/devtools.js'
 *
 * const seed = await seedDebugData()          // 一键播种，返回含 token 的 SeedResult
 * await publishDeviceEvent({                  // 服务端版 mosquitto_pub
 *   deviceId: 'BOXA-DEBUG-01',
 *   subTopic: 'event/dispense',
 *   payload: '{"msgId":"...","ts":...}',      // ⚠️ 透传 JSON **字符串**
 * })
 * ```
 */

import { post } from '@/api/request.js'

/**
 * 一键播种最小链路数据集（1 监护人 + 1 老人 + 药箱 + 传感器 + 药品/库存/计划）。
 *
 * <p>**幂等**：种子已存在时跳过写入（响应 `created=false`），token 仍会重新签发，
 * 方便长时间后重新拿到有效凭证。
 *
 * @param {boolean} [reset=false] true 时先清后种（破坏性，页面默认不暴露该入口）
 * @param {Object} [options] 透传请求选项（页面自绘错误时传 `silent: true`）
 * @returns {Promise<Object>} `SeedResult`：
 *   `{created, guardianUserId, guardianPhone, guardianToken, elderUserId, elderPhone,
 *     elderToken, deviceId, medicineIds, stockId, planId, planItemId}`
 * @throws {ApiError} 网络失败（-1）；后端未开启调试端点时见 {@link devtoolsErrorMessage}
 */
export function seedDebugData(reset = false, options) {
  // reset 只有显式 true 才进 query（后端 @RequestParam 默认 false；空串会被 stringify 丢弃）
  return post('/_debug/seed', undefined, { ...options, params: { reset: reset ? 'true' : '' } })
}

/**
 * 服务端模拟设备上报：把报文发到 `medbox/{productKey}/{deviceId}/up/{subTopic}`，
 * 等价于 `mosquitto_pub`，且会流经真实入站网关 → 各 `UpMessageHandler` → 落库 → WS 推送。
 *
 * <p>**`payload` 必须是 JSON 字符串**（后端 DTO 声明为 `String` 并原样 `getBytes` 透传，
 * 传对象会被 Jackson 反序列化失败）。用 `utils/devtools-sim.js` 的
 * {@link buildSimPayload} 生成，不要手拼。
 *
 * @param {Object} params
 * @param {string} params.deviceId 药箱主控设备 ID（topic 第 3 层，同时是 MQTT clientId）
 * @param {string} params.subTopic 上行子路径（02/03 第 2 章），如 `event/dispense` / `telemetry`
 * @param {string} params.payload 透传的 payload 原文（JSON 字符串）
 * @param {string} [params.productKey] 产品 key；空则由后端取 `medbox.device.default-product-key`
 * @param {Object} [options] 透传请求选项
 * @returns {Promise<null>} 成功即 `R.ok()`（`data` 为 null）
 * @throws {ApiError} MQTT 未启用 / 未连 broker → 50000（message 由后端给出，含"请先启动 EMQX"）
 */
export function publishDeviceEvent({ deviceId, subTopic, payload, productKey }, options) {
  return post('/_debug/mqtt/publish', { deviceId, subTopic, payload, productKey }, options)
}
