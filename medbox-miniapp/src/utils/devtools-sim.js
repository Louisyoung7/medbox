/**
 * 模拟设备上报的报文构造（纯函数，`feat/mp-devtools`，对应 medbox-spec/03 第 3 章 / 第 5 章）。
 *
 * <p>只放与页面解耦的可单测逻辑：**事件预设 + payload 组装 + 错误文案归一**。
 * 实际发请求在 `src/api/devtools.js`，页面只负责把它们接起来。
 *
 * <p>业务 ID 与 `DevtoolsProperties` 的种子参数对齐，全部落在 `debug` 命名空间
 * （`BOXA-DEBUG-01` / `p-debug-001` / `pi-debug-001` / `m-debug-001` / `m-debug-002`），
 * 与业务序列生成的真实 ID 物理隔离。使 03 §5.5 的 `mosquitto_pub` 示例与这里的预设
 * **产出同一形态的报文**，在真后端上跑出来的效果与文档一致。
 */

import { CODE } from '@/utils/error.js'

/** 播种数据里的调试药箱主控 deviceId（与后端 `medbox.devtools.seed.device-id` 默认值一致）。 */
export const SIM_DEFAULT_DEVICE_ID = 'BOXA-DEBUG-01'

/**
 * 播种数据里的计划 / 计划项 / 药品 ID（03 §5.5 示例同款）。
 *
 * <p>这几个**不需要** `debug` 段：业务序列从 `m-1001` / `p-1001` / `pi-1001` 起，
 * `m-205` / `p-3301` / `pi-9001` 永远不在生成值域内，天然不会与真实数据撞。
 */
const SIM_PLAN_ID = 'p-3301'
const SIM_PLAN_ITEM_ID = 'pi-9001'
const SIM_MEDICINE_ID = 'm-205'
const SIM_MEDICINE_PRIVATE_ID = 'm-206'
/** 传感器 ID 由主控 deviceId 派生，必须跟 `SIM_DEFAULT_DEVICE_ID` 一起变。 */
const SIM_SENSOR_TH = 'BOXA-DEBUG-01-S-TH01'
const SIM_SENSOR_LX = 'BOXA-DEBUG-01-S-LX01'
const SIM_SENSOR_CAM = 'BOXA-DEBUG-01-S-CAM01'

/**
 * `msgId` 自增序号（03 第 1 章：`{deviceId}-{10位递增序号}`，重启不归零由后端持久化）。
 *
 * <p>这里取一个**随机高位起点**再自增：既满足"10 位"格式，又避免与后端已持久化的序号撞车
 * （撞了会被幂等去重吃掉，表现为"发了却没反应"，极难排查）。进程内单调即可，
 * 刷新页面重启无所谓 —— 去重是后端的事。
 */
let msgSeq = Math.floor(Math.random() * 9000000000) + 1000000000

/**
 * 生成下一条模拟报文的 `msgId`。
 *
 * @param {string} deviceId 药箱主控设备 ID
 * @returns {string} `{deviceId}-{10位序号}`
 */
export function nextSimMsgId(deviceId) {
  msgSeq += 1
  const seq = String(msgSeq).slice(-10)
  return `${deviceId}-${seq}`
}

/**
 * 设备本地墙钟的时区偏移（分钟）。**固定 UTC+8 = Asia/Shanghai**，与后端 devtools 种子
 * 给设备配的 `timezone` 一致（`DevtoolsProperties.Seed.timezone`）。换时区要连这里一起改。
 */
const SIM_TZ_OFFSET_MINUTES = 8 * 60

/**
 * 把"日历天偏移"格式化成设备本地的 `YYYY-MM-DD`（结果面板回显用）。
 *
 * @param {number} dayOffset 相对今天的日历天偏移（0=今天 / -1=昨天）
 * @returns {string}
 */
export function slotDateText(dayOffset) {
  const shifted = new Date(Date.now() + SIM_TZ_OFFSET_MINUTES * 60000)
  shifted.setUTCDate(shifted.getUTCDate() + dayOffset)
  const pad = (n) => String(n).padStart(2, '0')
  return `${shifted.getUTCFullYear()}-${pad(shifted.getUTCMonth() + 1)}-${pad(shifted.getUTCDate())}`
}

/**
 * 构造"设备本地墙钟"时刻的时间戳（毫秒）。
 *
 * <p>**为什么不能直接用 `Date.now()`**：后端的 `onTime` 是**拿 `ts` 现算的** ——
 * `DispenseConsumeService` 里 `onTime = !actualTime.isAfter(planTime.plusMinutes(tolerance))`，
 * payload 里的 `onTime` 字段**被直接忽略**（03 第 4 章"后端算法"段：单片机本地算的 onTime
 * 仅用于即时声光反馈，不作为入库依据）。而 `planTime` 由 `ts` 就近匹配计划 `times` 得出。
 *
 * <p>于是 `Date.now()` 发出去的实际效果是：planTime = 今天 08:00、actualTime = 下午 → 算出来
 * `onTime=false`，"按时"预设名不副实。要真"按时"，`ts` 必须落在计划槽之后、容忍时长之内。
 *
 * @param {number} dayOffset 相对今天的日历天偏移（0=今天 / -1=昨天 / -2=前天）
 * @param {number} hour 设备本地小时（0-23）
 * @param {number} minute 设备本地分钟
 * @returns {number} epoch 毫秒
 */
function wallClockTs(dayOffset, hour, minute) {
  // 先拨到 +8 视角，用 UTC 字段写墙钟，再拨回来
  const shifted = new Date(Date.now() + SIM_TZ_OFFSET_MINUTES * 60000)
  shifted.setUTCDate(shifted.getUTCDate() + dayOffset)
  shifted.setUTCHours(hour, minute, 0, 0)
  return shifted.getTime() - SIM_TZ_OFFSET_MINUTES * 60000
}

/**
 * 模拟事件预设表（驱动页面里的预设列表）。
 *
 * <p>每项：
 * - `key`：唯一标识，{@link buildSimRequest} 按它取预设
 * - `label`：列表里显示的中文名（宜短）
 * - `subTopic`：上行子路径（03 第 2 章）
 * - `desc`：页面上的效果说明（发出去后会看到什么）
 * - `effect` / `effectNote`：会不会落库、为什么（见下方说明）
 * - `build(deviceId, dayOffset)`：产出 payload **对象**（序列化由 {@link buildSimRequest} 统一做）
 *
 * <p>⚠️ **`effect` 字段区分"真落库"与"仅记日志"**：目前只有 `event/dispense` 有专属处理器
 * （`feat/dispense-consume` 已落地，会写 `med_record` 并扣库存）；`telemetry` /
 * `sensor/heartbeat` / `event/error` / `inventory` 四个 topic 的专属分支
 * （`env-core` / `device-sensor` / `device-fault` / `inventory-consume`）**尚未落地**，
 * 消息只被 `LoggingUpMessageHandler` 兜底记一行日志，**不改任何数据**。
 * 不在 UI 上点明这件事，用户点了看不到变化就会以为功能坏了。
 *
 * <p>⚠️ **服药类的 `dayOffset` 由调用方每次递增传入，不要写死**：后端有第二层业务幂等
 * `uk_med_record_item_plan_time`（`med_record` 上 `(plan_item_id, plan_time)` 的部分唯一索引，
 * 见 06 的 2.7 规则 6 与 `V10__dispense.sql`），**同一计划项的同一计划时间只落一条记录**。
 * 而 devtools 种子只建了 1 个计划项（`pi-9001`）× 1 个每日 08:00 的时间 —— 槽位是**有限资源**，
 * 写死"今天 / 昨天 / 前天"三个值意味着每个预设一辈子只能成功一次（实测踩过：点完三轮后
 * 再怎么点都是幂等命中）。所以页面用一个持久化计数器，每次发送前移一天，
 * 保证每次点击都落在还没用过的槽上。
 */
export const SIM_EVENT_PRESETS = [
  {
    key: 'dispense-on-time',
    label: '服药·按时',
    subTopic: 'event/dispense',
    desc: '落一个按时槽的记录（onTime=true，每次点击换新槽）',
    effect: 'record',
    effectNote: '记录页可见（该页 onShow 会刷新，可直接跳过去看）',
    build: (deviceId, dayOffset) => ({
      msgId: nextSimMsgId(deviceId),
      // 计划槽后 5 分钟：落在容忍时长（默认 15 分钟）内 → 后端算出 onTime=true
      ts: wallClockTs(dayOffset, 8, 5),
      planId: SIM_PLAN_ID,
      planItemId: SIM_PLAN_ITEM_ID,
      medicineId: SIM_MEDICINE_ID,
      actualDose: '1',
      unit: '片',
      onTime: true, // 仅作示意，入库值由后端用 ts 现算，见 wallClockTs 注释
      confidence: 0.93,
      source: 'CAMERA',
      imageId: null,
    }),
  },
  {
    key: 'dispense-late',
    label: '服药·迟到',
    subTopic: 'event/dispense',
    desc: '落前日 08:00 槽的迟到记录（onTime=false）',
    effect: 'record',
    effectNote: '记录页可见（列表里该条带"未按时"标签）',
    build: (deviceId, dayOffset) => ({
      msgId: nextSimMsgId(deviceId),
      // 计划槽后 1 小时：超出容忍时长 → 后端算出 onTime=false
      ts: wallClockTs(dayOffset, 9, 0),
      planId: SIM_PLAN_ID,
      planItemId: SIM_PLAN_ITEM_ID,
      medicineId: SIM_MEDICINE_ID,
      actualDose: '1',
      unit: '片',
      onTime: false, // 仅作示意，入库值由后端用 ts 现算
      confidence: 0.91,
      source: 'CAMERA',
      imageId: null,
    }),
  },
  {
    key: 'telemetry',
    label: '环境遥测（温湿度）',
    subTopic: 'telemetry',
    desc: '证明消息到达后端（本端无展示位）',
    effect: 'log',
    effectNote: 'env-core 未落地：后端仅记一行日志，不写任何数据',
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
    desc: '证明消息到达后端（子设备在线状态暂不更新）',
    effect: 'log',
    effectNote: 'device-sensor 未落地：后端仅记日志，设备详情的在线状态不会变',
    desc: '汇总上报各子设备在线状态（摄像头置为离线）',
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
    desc: '证明消息到达后端（暂不生成告警）',
    effect: 'log',
    effectNote: 'device-fault 未落地：后端仅记日志，不生成 DEVICE_FAULT 告警',
    desc: '记入设备事件流水并生成 DEVICE_FAULT 告警',
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
    desc: '证明消息到达后端（库存数量暂不更新）',
    effect: 'log',
    effectNote: 'inventory-consume 未落地：后端仅记日志，库存清单的数量不会变',
    build: (deviceId) => ({
      msgId: nextSimMsgId(deviceId),
      ts: Date.now(),
      medicineId: SIM_MEDICINE_ID,
      quantity: 13,
      delta: 1,
    }),
  },
]

/**
 * 按预设 key 取预设。
 *
 * @param {string} key
 * @returns {Object|undefined} 预设项；key 不存在返回 undefined（调用方自行提示）
 */
export function getSimPreset(key) {
  return SIM_EVENT_PRESETS.find((item) => item.key === key)
}

/**
 * 构造一条模拟上报的完整请求要素（`POST /_debug/mqtt/publish` 用）。
 *
 * <p>比 {@link buildSimPayload} 多返回 `subTopic` / `msgId` / `slotDate`：页面要把这几个
 * **回显给用户**（msgId 是去后端日志里 grep 的钥匙，slotDate 是去记录页里找那条的钥匙），
 * 只给 payload 字符串不够。
 *
 * @param {string} key 预设 key（见 {@link SIM_EVENT_PRESETS}）
 * @param {string} deviceId 药箱主控设备 ID
 * @param {number} [dayOffset=0] 服药类的日历天偏移（0=今天 / -1=昨天 ...）；
 *        每次发送应由调用方递增，保证撞上没用过的计划时间槽（见预设表注释）。非服药预设忽略
 * @returns {{subTopic: string, msgId: string, payload: string, slotDate: string}}
 *          `slotDate` 为设备本地 `YYYY-MM-DD`；非服药预设为空串
 * @throws {Error} 预设 key 不存在时抛出（页面应先校验，不会走到）
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

/**
 * 构造一条模拟上报的 payload **JSON 字符串**（`POST /_debug/mqtt/publish` 的 `payload`）。
 *
 * <p>**为什么统一在这里序列化**：后端 DTO 把 `payload` 声明为 `String` 并原样透传，
 * 页面若直接传对象会被 Jackson 反序列化失败（40001），且报错信息离现场很远。
 *
 * @param {string} key 预设 key（见 {@link SIM_EVENT_PRESETS}）
 * @param {string} deviceId 药箱主控设备 ID
 * @param {number} [dayOffset=0] 服药类的日历天偏移，见 {@link buildSimRequest}
 * @returns {string} JSON 字符串
 * @throws {Error} 预设 key 不存在时抛出（页面应先校验，不会走到）
 */
export function buildSimPayload(key, deviceId, dayOffset = 0) {
  return buildSimRequest(key, deviceId, dayOffset).payload
}

/**
 * 把调试端点失败的 `ApiError` 归一成**可操作的**提示。
 *
 * <p>为什么需要它：后端没开 `medbox.devtools.enabled` 时 `DevtoolsController` 不装配，
 * 请求打到未注册路径上，响应不是统一 `R` 结构 —— 请求层只能给出通用的
 * "服务端内部错误"（50000），用户看了完全不知道要改哪里。
 *
 * @param {Object} [err] `ApiError`（或任意错误对象）
 * @returns {string} 面向开发者的提示
 */
export function devtoolsErrorMessage(err) {
  if (!err) {
    return '操作失败，请重试'
  }
  // 后端未装配 DevtoolsController：路径不存在 → 404（响应体非统一结构，code 被兜底成 50000）
  if (err.httpStatus === 404) {
    return '后端未开启调试端点：需在 medbox-server 置 medbox.devtools.enabled=true 并重启'
  }
  if (err.code === CODE.INTERNAL_ERROR && err.message) {
    // 后端 50000 的 message 往往就是原因（如"MQTT 未连接 broker，请先启动 EMQX"），优先透出
    return err.message
  }
  return err.message || '操作失败，请重试'
}
