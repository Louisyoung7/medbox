/**
 * 设备在线状态合并（纯函数，`feat/mp-device`）。
 *
 * <p>REST 用字符串 `onlineState`（`"ONLINE"`/`"OFFLINE"`），WS 的 `DEVICE_STATUS` 帧用
 * boolean `online` —— 这里把 WS 帧合并进 REST 的列表 / 详情，**统一成字符串口径**，
 * 页面拿到的永远是 `onlineState`。纯函数便于单测（与 `utils/ws-frame.js`、`datetime.js` 同理）。
 */

/**
 * boolean 在线状态 → REST 字符串口径。
 *
 * @param {boolean} online
 * @returns {'ONLINE'|'OFFLINE'}
 */
export function toOnlineState(online) {
  return online ? 'ONLINE' : 'OFFLINE'
}

/**
 * 把一条 `DEVICE_STATUS` 帧合并进设备数组 / 单个设备对象（**按 `deviceId` 匹配**，不要用
 * `deviceType` —— 同类型可有多台）。返回**新引用**、不原地改，便于页面
 * `this.devices = applyStatus(this.devices, frame)` 触发渲染。
 *
 * <p>未命中（帧里的设备不在这份数据里）或 `payload` 缺 `deviceId` 时**原样返回**，
 * 调用方无需判空。
 *
 * @param {Object|Array<Object>} target 设备（含 `deviceId` / `onlineState`）或其数组
 * @param {Object} frame `store/ws.js` 分发来的帧，`payload` 含 `deviceId` / `online`(boolean)
 * @returns {Object|Array<Object>}
 */
export function applyStatus(target, frame) {
  const payload = frame && frame.payload ? frame.payload : {}
  const deviceId = payload.deviceId
  if (deviceId === undefined || deviceId === null || deviceId === '') {
    return target
  }
  const onlineState = toOnlineState(!!payload.online)
  if (Array.isArray(target)) {
    return target.map((item) =>
      item && item.deviceId === deviceId ? { ...item, onlineState } : item,
    )
  }
  if (target && target.deviceId === deviceId) {
    return { ...target, onlineState }
  }
  return target
}
