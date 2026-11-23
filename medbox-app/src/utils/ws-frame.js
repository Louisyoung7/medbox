/**
 * WebSocket 帧解析（纯函数，对应 medbox-spec/04 第 2 / 3 章）。
 *
 * <p>只做「字符串 → 帧对象」与「帧 → 一行摘要」，不碰连接状态（编排在 `src/store/ws.js`）。
 * 帧结构：`{ type, id, topic, ts(epoch 毫秒), payload }`；`PONG` 不带 `topic` 与 `payload`。
 *
 * <p>固定 3 药格模型下：`ALARM` 帧无 `medicineIds`，message 只提格号与时间；
 * `REMINDER` 帧为 `{planId, planTime, cellNo}`，不含药品信息。
 */

/** 帧类型（与 04 第 3 章一致；AI_STREAM 本期不使用）。 */
export const WS_EVENT = {
  ALARM: 'ALARM',
  DEVICE_STATUS: 'DEVICE_STATUS',
  REMINDER: 'REMINDER',
  AI_STREAM: 'AI_STREAM',
  AI_INSIGHT: 'AI_INSIGHT',
  PONG: 'PONG',
  RESYNC: 'RESYNC',
}

/** 会推进 `lastEventId` 的业务帧（PONG 不在此列）。 */
const BUSINESS_TYPES = [WS_EVENT.ALARM, WS_EVENT.DEVICE_STATUS, WS_EVENT.REMINDER, WS_EVENT.AI_INSIGHT]

/** 子设备类型中文名。 */
const DEVICE_TYPE_LABEL = {
  MAIN: '药箱主控',
  TEMP_HUMI: '温湿度传感器',
  LIGHT: '光照传感器',
  CAMERA: '摄像头',
}

/** 固定格位中文标签（1 早间 / 2 午间 / 3 晚间）。 */
function cellLabel(cellNo) {
  const no = Number(cellNo)
  if (no === 1) return '1 号格（早间）'
  if (no === 2) return '2 号格（午间）'
  if (no === 3) return '3 号格（晚间）'
  return ''
}

/** 解析一帧；非法输入返回 null（不抛异常）。 */
export function parseFrame(raw) {
  let data = raw
  if (typeof raw === 'string') {
    try {
      data = JSON.parse(raw)
    } catch (e) {
      return null
    }
  }
  if (!data || typeof data !== 'object') return null
  const type = typeof data.type === 'string' ? data.type : ''
  if (!type) return null
  return {
    type,
    id: typeof data.id === 'string' ? data.id : '',
    topic: typeof data.topic === 'string' ? data.topic : '',
    ts: typeof data.ts === 'number' ? data.ts : null,
    payload: data.payload && typeof data.payload === 'object' ? data.payload : {},
  }
}

/** 是否业务帧（会推进 `lastEventId`）。 */
export function isBusinessType(type) {
  return BUSINESS_TYPES.indexOf(type) !== -1
}

/** 是否业务帧（帧对象版）。 */
export function isBusinessFrame(frame) {
  return !!frame && isBusinessType(frame.type)
}

/** 是否「补推窗口已过期」帧：客户端应改走 REST 拉全量。 */
export function isResyncFrame(frame) {
  return !!frame && frame.type === WS_EVENT.RESYNC
}

/** 取设备类型中文名（未知原样返回）。 */
export function deviceTypeLabel(deviceType) {
  return DEVICE_TYPE_LABEL[deviceType] || deviceType || '设备'
}

/**
 * 把一帧转成一行可读摘要（状态条 / 通知用）；只取展示字段，不打整包。
 * REMINDER 按格号提示，缺格号时兜底「请按时服药」。
 */
export function describeFrame(frame) {
  const parsed = frame && typeof frame === 'object' ? frame : parseFrame(frame)
  if (!parsed) return ''
  const payload = parsed.payload || {}
  switch (parsed.type) {
    case WS_EVENT.ALARM:
      return payload.message || `告警（${payload.level || '未分级'}）`
    case WS_EVENT.DEVICE_STATUS:
      return `${deviceTypeLabel(payload.deviceType)}${payload.online ? '已上线' : '已离线'}`
    case WS_EVENT.REMINDER: {
      const label = cellLabel(payload.cellNo)
      return label ? `服药提醒：${label}` : '服药提醒：请按时服药'
    }
    case WS_EVENT.AI_INSIGHT:
      return 'AI 健康洞察已更新'
    case WS_EVENT.RESYNC:
      return '离线期间事件已过期，请刷新列表'
    case WS_EVENT.PONG:
      return '心跳正常'
    default:
      return parsed.type
  }
}
