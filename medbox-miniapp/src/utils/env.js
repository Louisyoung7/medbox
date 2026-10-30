/**
 * 环境监测纯函数（feat/mp-env）：解析 env-core 响应、老人级阈值容错、超标判定、自绘折线图几何。
 *
 * <p>阈值形状兼容两套（见 medbox-spec/06 §2.10 与已落代码 utils/alarm.js）：
 * - 嵌套（已落代码 / Mock）：`{ temp:{min,max}, humidity:{min,max}, light:{min,max} }`
 * - 扁平（06 §2.10 注释）：`{ tempMin, tempMax, humiMin, humiMax, luxMin?, luxMax }`
 * 统一输出嵌套 `{ temp, humidity, light }.{min,max}`，使高亮既兼容当前 Mock 也兼容文档未来扁平形态，
 * 且**不改动已落地的 mp-alarm**。
 */

import { toAlarmSettingsView } from '@/utils/alarm.js'

/** 三个监测指标的展示元信息（顺序即卡片/图例顺序）。 */
export const ENV_METRICS = [
  { key: 'temperature', label: '温度', unit: '℃' },
  { key: 'humidity', label: '湿度', unit: '%' },
  { key: 'lux', label: '光照', unit: 'lux' },
]

/** 按 key 取指标元信息。 */
export function envMetricByKey(key) {
  return ENV_METRICS.find((m) => m.key === key) || null
}

/**
 * 解析实时读数（容错）：`{ temperature, humidity, lux, sampleTime }`，非数字字段置 `null`。
 *
 * @param {*} data 后端 `EnvRealtimeResponse`，可能缺失或为 null
 * @returns {{temperature:?number, humidity:?number, lux:?number, sampleTime:?string}}
 */
export function parseEnvRealtime(data) {
  const d = data && typeof data === 'object' ? data : {}
  return {
    temperature: typeof d.temperature === 'number' ? d.temperature : null,
    humidity: typeof d.humidity === 'number' ? d.humidity : null,
    lux: typeof d.lux === 'number' ? d.lux : null,
    sampleTime: d.sampleTime || null,
  }
}

/**
 * 解析历史曲线（容错）：兼容 `{ points:[...] }` 外壳或裸数组，一处收敛。
 *
 * @param {*} data 后端 `EnvHistoryResponse` 或裸数组
 * @returns {Array<{sampleTime:?string, temperature:?number, humidity:?number, lux:?number}>}
 */
export function parseEnvHistory(data) {
  if (!data) return []
  const list = Array.isArray(data) ? data : Array.isArray(data.points) ? data.points : []
  return list.map((p) => ({
    sampleTime: p && p.sampleTime ? p.sampleTime : null,
    temperature: typeof (p && p.temperature) === 'number' ? p.temperature : null,
    humidity: typeof (p && p.humidity) === 'number' ? p.humidity : null,
    lux: typeof (p && p.lux) === 'number' ? p.lux : null,
  }))
}

/**
 * 老人级环境阈值 → 归一化的嵌套视图 `{ temp, humidity, light }.{min,max}`。
 *
 * <p>同时接受扁平（`tempMin/tempMax/humiMin/humiMax/luxMin/luxMax`）与嵌套（`temp/humidity/light`）
 * 两种来源；缺字段用 `toAlarmSettingsView` 的默认值兜底（温度 15~30 / 湿度 30~70 / 光照 50~500）。
 *
 * @param {*} raw `GET /alarm-settings` 的 `envThreshold`（可能嵌套 / 扁平 / 整体 settings / null）
 * @returns {{temp:{min:number,max:number}, humidity:{min:number,max:number}, light:{min:number,max:number}}}
 */
export function toEnvThresholdView(raw) {
  let src = raw || {}
  // 优先级：嵌套 envThreshold（结构正确，优先）> 扁平（06 §2.10 注释）> 默认
  const nested = src.envThreshold
  const nestedOk = nested && (nested.temp || nested.humidity || nested.light)
  const hasFlat = ['tempMin', 'tempMax', 'humiMin', 'humiMax', 'luxMin', 'luxMax'].some(
    (k) => src[k] !== undefined,
  )
  if (!nestedOk && hasFlat) {
    // 扁平 → 包装成嵌套 envThreshold，交给 toAlarmSettingsView 统一合并兜底
    src = {
      ...src,
      envThreshold: {
        temp: { min: src.tempMin, max: src.tempMax },
        humidity: { min: src.humiMin, max: src.humiMax },
        light: { min: src.luxMin, max: src.luxMax },
      },
    }
  }
  return toAlarmSettingsView(src).envThreshold
}

/**
 * 单指标超标判定。
 *
 * @param {?number} value 当前读数（null = 无数据，按正常处理、不高亮）
 * @param {{min?:number, max?:number}} range 该指标阈值区间（缺边即不卡该边）
 * @returns {'ok'|'low'|'high'} low = 偏低、high = 偏高、ok = 正常
 */
export function envMetricStatus(value, range) {
  if (value == null) return 'ok'
  const min = range && typeof range.min === 'number' ? range.min : null
  const max = range && typeof range.max === 'number' ? range.max : null
  if (min != null && value < min) return 'low'
  if (max != null && value > max) return 'high'
  return 'ok'
}

/**
 * 自绘折线图的几何计算（纯函数、可单测）：把历史点按指标切分为多段（small-multiple），
 * 每段在各自纵向区域内独立缩放，并给出阈值线 y 坐标（无阈值边则 null）。
 *
 * <p>组件 `EnvLineChart.vue` 仅负责把本结果画到 canvas，无任何业务逻辑。
 *
 * @param {Object} args
 * @param {Array} args.points parseEnvHistory 的结果
 * @param {Array<string>} args.metrics 要绘制的指标 key（如 `['temperature','humidity','lux']`）
 * @param {Object} [args.thresholds] toEnvThresholdView 的结果
 * @param {number} args.width 画布 CSS 宽（px）
 * @param {number} args.height 画布 CSS 高（px）
 * @param {Object} [args.pad] 四周边距
 * @returns {{bands:Array, width:number, height:number}}
 */
export function computeEnvChartGeometry({
  points = [],
  metrics = [],
  thresholds = {},
  width = 300,
  height = 200,
  pad = { top: 12, bottom: 12, left: 12, right: 12 },
} = {}) {
  const n = Math.max(1, metrics.length)
  const bandH = (height - pad.top - pad.bottom) / n
  const innerW = Math.max(1, width - pad.left - pad.right)
  const count = points.length

  // 指标 key（temperature/humidity/lux）→ 阈值视图 key（temp/humidity/light）
  const THRESHOLD_KEY = { temperature: 'temp', humidity: 'humidity', lux: 'light' }

  const bands = metrics.map((key, bi) => {
    const meta = envMetricByKey(key) || { key, label: key, unit: '' }
    const range = thresholds[THRESHOLD_KEY[key] || key] || {}
    const values = points.map((p) => p[key]).filter((v) => typeof v === 'number')

    // 定义域 = 数据 + 阈值区间，留 10% 余量；无数据时用阈值或 [0,1]
    let dMin = Math.min(...values, range.min != null ? range.min : Infinity)
    let dMax = Math.max(...values, range.max != null ? range.max : -Infinity)
    if (!Number.isFinite(dMin)) dMin = range.min != null ? range.min : 0
    if (!Number.isFinite(dMax)) dMax = range.max != null ? range.max : 1
    if (dMin === dMax) dMax = dMin + 1
    const margin = (dMax - dMin) * 0.1
    dMin -= margin
    dMax += margin

    const top = pad.top + bi * bandH
    const yOf = (v) => top + bandH * (1 - (v - dMin) / (dMax - dMin))
    const xOf = (i) => pad.left + (count <= 1 ? innerW / 2 : (i / (count - 1)) * innerW)

    const line = points.map((p, i) => {
      const v = p[key]
      return {
        x: xOf(i),
        y: typeof v === 'number' ? yOf(v) : null,
        value: typeof v === 'number' ? v : null,
      }
    })

    // 阈值线 y（缺边 → null，不画该线）
    const threshold =
      range.min != null || range.max != null
        ? {
            min: range.min != null ? yOf(range.min) : null,
            max: range.max != null ? yOf(range.max) : null,
          }
        : null

    // 取最后一个非空值用于图例/标签
    let lastValue = null
    for (let i = count - 1; i >= 0; i -= 1) {
      if (typeof points[i][key] === 'number') {
        lastValue = points[i][key]
        break
      }
    }

    return {
      key,
      label: meta.label,
      unit: meta.unit,
      domainMin: dMin,
      domainMax: dMax,
      bandTop: top,
      bandH,
      line,
      threshold,
      lastValue,
    }
  })

  return { bands, width, height, pad }
}
