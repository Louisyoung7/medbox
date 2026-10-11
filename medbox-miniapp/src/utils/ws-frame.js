/**
 * WebSocket 帧解析（纯函数，`feat/mp-ws`，对应 medbox-spec/04 第 2 / 3 章）。
 *
 * <p>只做"字符串 → 帧对象"与"帧 → 一行摘要"，**不碰连接状态**（连接编排在
 * `src/store/ws.js`）。拆成纯函数是为了能直接单测：帧格式是前后端唯一契约，
 * 写错不会报错、只会静默丢事件。
 *
 * <p>帧结构（04 第 2 章）：`{ type, id, topic, ts, payload }`，`ts` 是 epoch **毫秒**
 * （与 REST 响应体 `R.timestamp` 的秒不同）；`PONG` 是控制帧，**不带 `topic` 与 `payload`**。
 */

/**
 * 帧类型（与 04 第 3 章一致）。
 *
 * <p>`AI_STREAM` 本期不使用（流式问答统一走 HTTP SSE，见文档 05），这里只登记常量，
 * 收到时不分发业务。
 */
export const WS_EVENT = {
  ALARM: 'ALARM',
  DEVICE_STATUS: 'DEVICE_STATUS',
  REMINDER: 'REMINDER',
  AI_STREAM: 'AI_STREAM',
  PONG: 'PONG',
  RESYNC: 'RESYNC',
}

/**
 * 会推进 `lastEventId` 的**业务帧**：告警 / 设备状态 / 服药提醒。
 *
 * <p>`PONG` 也带 `id`，但服务端**不把它放进补推窗口**（见 `MedboxWebSocketHandler`），
 * 客户端若拿 PONG 的 id 去补推，会请求到一条不存在的序号。
 */
const BUSINESS_TYPES = [WS_EVENT.ALARM, WS_EVENT.DEVICE_STATUS, WS_EVENT.REMINDER]

/** 子设备类型中文名（04 第 3 章 `deviceType`）。 */
const DEVICE_TYPE_LABEL = {
  MAIN: '药箱主控',
  TEMP_HUMI: '温湿度传感器',
  LIGHT: '光照传感器',
  CAMERA: '摄像头',
}

/**
 * 解析一帧；**非法输入返回 `null`**（非法 JSON / 不是对象 / 缺 `type` / `type` 不是字符串）。
 *
 * <p>不抛异常：连接回调里抛异常只会打进容器日志并可能搞挂整条连接，
 * 与 `src/api/request.js` 的兜底纪律一致。
 *
 * @param {string|Object} raw `uni.onSocketMessage` 给的 `res.data`（字符串），也兼容已解析的对象
 * @returns {?{type:string, id:string, topic:string, ts:?number, payload:Object}}
 */
export function parseFrame(raw) {
  let data = raw
  if (typeof raw === 'string') {
    try {
      data = JSON.parse(raw)
    } catch (e) {
      return null
    }
  }
  if (!data || typeof data !== 'object') {
    return null
  }
  const type = typeof data.type === 'string' ? data.type : ''
  if (!type) {
    return null
  }
  return {
    type,
    id: typeof data.id === 'string' ? data.id : '',
    topic: typeof data.topic === 'string' ? data.topic : '',
    ts: typeof data.ts === 'number' ? data.ts : null,
    payload: data.payload && typeof data.payload === 'object' ? data.payload : {},
  }
}

/**
 * 是否业务帧（会推进 `lastEventId`）。
 * @param {string} type 见 {@link WS_EVENT}
 * @returns {boolean}
 */
export function isBusinessType(type) {
  return BUSINESS_TYPES.indexOf(type) !== -1
}

/**
 * 是否业务帧（帧对象版）。
 * @param {?Object} frame {@link parseFrame} 的结果
 * @returns {boolean}
 */
export function isBusinessFrame(frame) {
  return !!frame && isBusinessType(frame.type)
}

/**
 * 是否"补推窗口已过期"帧：客户端应改走 REST 拉全量。
 * @param {?Object} frame
 * @returns {boolean}
 */
export function isResyncFrame(frame) {
  return !!frame && frame.type === WS_EVENT.RESYNC
}

/** 取设备类型中文名（未知类型原样返回）。 */
export function deviceTypeLabel(deviceType) {
  return DEVICE_TYPE_LABEL[deviceType] || deviceType || '设备'
}

/**
 * 把一帧转成一行可读摘要（状态条 / 通知用）。
 *
 * <p>只取 `payload` 里的展示字段，**不打整包**（消息内容可能含老人姓名等隐私）；
 * 解析失败返回空串，由调用方决定要不要显示。
 *
 * @param {Object|string} frame 帧对象或原始字符串
 * @returns {string}
 */
export function describeFrame(frame) {
  const parsed = frame && typeof frame === 'object' ? frame : parseFrame(frame)
  if (!parsed) {
    return ''
  }
  const payload = parsed.payload || {}
  switch (parsed.type) {
    case WS_EVENT.ALARM:
      return payload.message || `告警（${payload.level || '未分级'}）`
    case WS_EVENT.DEVICE_STATUS:
      return `${deviceTypeLabel(payload.deviceType)}${payload.online ? '已上线' : '已离线'}`
    case WS_EVENT.REMINDER:
      return `服药提醒：${medicineSummary(payload.medicines)}`
    case WS_EVENT.RESYNC:
      return '离线期间事件已过期，请刷新列表'
    case WS_EVENT.PONG:
      return '心跳正常'
    default:
      return parsed.type
  }
}

/** 一次提醒可能含多种药（04 第 3 章 `medicines[]`）：有名字就列名字，否则退化为数量。 */
function medicineSummary(medicines) {
  if (!Array.isArray(medicines) || medicines.length === 0) {
    return '请按时服药'
  }
  const names = medicines.map((item) => item && item.medicineName).filter(Boolean)
  if (names.length === 0) {
    return `${medicines.length} 种药品`
  }
  return names.join('、')
}
