/**
 * 模拟设备上报的报文构造（纯函数，对应 medbox-spec/03 第 3 / 5 章）。
 *
 * <p>固定 3 药格模型：服药事件由设备经 **HTTP captures** 上报（带 `cellNo` + `takenResult`），
 * 旧 MQTT `up/event/dispense` 入口已下线；本页的 MQTT 预设只用于验证消息到达后端，
 * **不再落服药记录**。库存是监护人手工台账，系统不按服药事件自动扣减。
 *
 * <p>业务 ID 与后端 `DevtoolsProperties` 的种子参数对齐（种子计划只排 1 号格）。
 */

import { CODE } from '@/utils/error.js'

/** 播种数据里的调试药箱主控 deviceId。 */
export const SIM_DEFAULT_DEVICE_ID = 'BOXA-DEBUG-01'

const SIM_PLAN_ID = 'p-3301'
/** 库存手工台账预设用的药品 ID（与格位无关）。 */
const SIM_STOCK_MEDICINE_ID = 'm-205'
/** 传感器 ID 由主控 deviceId 派生。 */
const SIM_SENSOR_TH = 'BOXA-DEBUG-01-S-TH01'
const SIM_SENSOR_LX = 'BOXA-DEBUG-01-S-LX01'
const SIM_SENSOR_CAM = 'BOXA-DEBUG-01-S-CAM01'

/** `msgId` 自增序号：`{deviceId}-{10位递增序号}`，取随机高位起点避免与后端持久化序号撞车。 */
let msgSeq = Math.floor(Math.random() * 9000000000) + 1000000000

/** 生成下一条模拟报文的 `msgId`。 */
export function nextSimMsgId(deviceId) {
  msgSeq += 1
  const seq = String(msgSeq).slice(-10)
  return `${deviceId}-${seq}`
}

/** 设备本地墙钟的时区偏移（固定 UTC+8 = Asia/Shanghai，与后端种子一致）。 */
const SIM_TZ_OFFSET_MINUTES = 8 * 60

/** 把「日历天偏移」格式化成设备本地的 `YYYY-MM-DD`（结果面板回显用）。 */
export function slotDateText(dayOffset) {
  const shifted = new Date(Date.now() + SIM_TZ_OFFSET_MINUTES * 60000)
  shifted.setUTCDate(shifted.getUTCDate() + dayOffset)
  const pad = (n) => String(n).padStart(2, '0')
  return `${shifted.getUTCFullYear()}-${pad(shifted.getUTCMonth() + 1)}-${pad(shifted.getUTCDate())}`
}

/**
 * 构造「设备本地墙钟」时刻的时间戳（毫秒）。
 *
 * <p>后端 `planTime` 由上报时刻按**同 `cellNo` 的 slots 就近槽**匹配得出，`onTime` 由后端
 * 按该槽 + 容忍窗口重算，报文里的 `onTime` 不生效。故「按时」预设必须把时刻落在开格时间之后、
 * 容忍时长之内。
 */
function wallClockTs(dayOffset, hour, minute) {
  const shifted = new Date(Date.now() + SIM_TZ_OFFSET_MINUTES * 60000)
  shifted.setUTCDate(shifted.getUTCDate() + dayOffset)
  shifted.setUTCHours(hour, minute, 0, 0)
  return shifted.getTime() - SIM_TZ_OFFSET_MINUTES * 60000
}

/**
 * 模拟事件预设表（驱动页面里的预设列表）。
 *
 * <p>`effect` 区分「仅记日志」与「真落库」：当前服药事件已改走 HTTP captures，
 * MQTT 预设全部只被日志兜底处理器记录、不改数据；库存台账也不随上报变化。
 *
 * <p>服药类 `dayOffset` 由调用方每次递增传入：后端有第二层业务幂等
 * `uk_med_record_cell_plan_time`（`(plan_id, cell_no, plan_time)` 部分唯一索引），
 * 而种子只排 1 号格 × 每日 08:00 —— 固定槽会被幂等吃掉，故页面用持久化计数器每次移一天。
 */
export const SIM_EVENT_PRESETS = [
  {
    key: 'dispense-on-time',
    label: '服药·按时',
    subTopic: 'event/dispense',
    desc: '构造 1 号格的按时取药报文（每次点击换新槽）',
    effect: 'log',
    effectNote: '服药事件现由设备经 HTTP captures 上报；此 MQTT 预设仅记日志，不落记录',
    build: (deviceId, dayOffset) => ({
      msgId: nextSimMsgId(deviceId),
      // 1 号格 08:00 开格后 5 分钟：落在容忍时长（默认 15 分钟）内
      ts: wallClockTs(dayOffset, 8, 5),
      planId: SIM_PLAN_ID,
      cellNo: 1,
      takenResult: 'TAKEN',
      source: 'CAMERA',
    }),
  },
  {
    key: 'dispense-late',
    label: '服药·迟到',
    subTopic: 'event/dispense',
    desc: '构造 1 号格的迟到取药报文',
    effect: 'log',
    effectNote: '服药事件现由设备经 HTTP captures 上报；此 MQTT 预设仅记日志，不落记录',
    build: (deviceId, dayOffset) => ({
      msgId: nextSimMsgId(deviceId),
      // 开格后 1 小时：超出容忍时长
      ts: wallClockTs(dayOffset, 9, 0),
      planId: SIM_PLAN_ID,
      cellNo: 1,
      takenResult: 'TAKEN',
      source: 'CAMERA',
    }),
  },
  {
    key: 'telemetry',
    label: '环境遥测（温湿度）',
    subTopic: 'telemetry',
    desc: '证明消息到达后端（本端无展示位）',
    effect: 'log',
    effectNote: '后端仅记一行日志，不写任何数据',
    build: (deviceId) => ({
      msgId: nextSimMsgId(deviceId),
      ts: Date.now(),
      sensorId: SIM_SENSOR_TH,
      temperature: 24.6,
      humidity: 52.1,
    }),
  },
  {
    key: 'sensor-heartbeat',
    label: '传感器心跳',
    subTopic: 'sensor/heartbeat',
    desc: '汇总上报各子设备在线状态（摄像头置为离线）',
    effect: 'log',
    effectNote: '后端仅记日志，不写任何数据',
    build: (deviceId) => ({
      msgId: nextSimMsgId(deviceId),
      ts: Date.now(),
      sensors: [
        { sensorId: SIM_SENSOR_TH, online: true },
        { sensorId: SIM_SENSOR_LX, online: true },
        { sensorId: SIM_SENSOR_CAM, online: false },
      ],
    }),
  },
  {
    key: 'event-error',
    label: '设备异常',
    subTopic: 'event/error',
    desc: '记入设备事件流水（仅记日志）',
    effect: 'log',
    effectNote: '后端仅记日志，不生成 DEVICE_FAULT 告警',
    build: (deviceId) => ({
      msgId: nextSimMsgId(deviceId),
      ts: Date.now(),
      type: 'DOOR_TIMEOUT',
      detail: '仓门开启超过 60s 未关闭（模拟上报）',
    }),
  },
  {
    key: 'inventory',
    label: '库存变更',
    subTopic: 'inventory',
    desc: '构造库存变更报文（仅记日志）',
    effect: 'log',
    effectNote: '库存是监护人手工台账，系统不按服药事件自动扣减；此预设不改库存数量',
    build: (deviceId) => ({
      msgId: nextSimMsgId(deviceId),
      ts: Date.now(),
      medicineId: SIM_STOCK_MEDICINE_ID,
      quantity: 13,
      delta: 1,
    }),
  },
]

/** 按预设 key 取预设。 */
export function getSimPreset(key) {
  return SIM_EVENT_PRESETS.find((item) => item.key === key)
}

/**
 * 构造一条模拟上报的完整请求要素（`POST /_debug/mqtt/publish` 用）。
 *
 * @param {string} key 预设 key
 * @param {string} deviceId 药箱主控设备 ID
 * @param {number} [dayOffset=0] 服药类的日历天偏移
 * @returns {{subTopic: string, msgId: string, payload: string, slotDate: string}}
 * @throws {Error} 预设 key 不存在时抛出
 */
export function buildSimRequest(key, deviceId, dayOffset = 0) {
  const preset = getSimPreset(key)
  if (!preset) {
    throw new Error(`未知的模拟事件类型: ${key}`)
  }
  const body = preset.build(deviceId, dayOffset)
  const isDispense = preset.subTopic === 'event/dispense'
  return {
    subTopic: preset.subTopic,
    msgId: body.msgId,
    payload: JSON.stringify(body),
    slotDate: isDispense ? slotDateText(dayOffset) : '',
  }
}

/** 构造一条模拟上报的 payload JSON 字符串。 */
export function buildSimPayload(key, deviceId, dayOffset = 0) {
  return buildSimRequest(key, deviceId, dayOffset).payload
}

/** 把调试端点失败的 `ApiError` 归一成可操作的提示。 */
export function devtoolsErrorMessage(err) {
  if (!err) return '操作失败，请重试'
  if (err.httpStatus === 404) {
    return '后端未开启调试端点：需在 medbox-server 置 medbox.devtools.enabled=true 并重启'
  }
  if (err.code === CODE.INTERNAL_ERROR && err.message) return err.message
  return err.message || '操作失败，请重试'
}
