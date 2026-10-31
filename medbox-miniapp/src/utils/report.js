/**
 * 依从性报告域纯函数（`feat/mp-report`，对应 medbox-spec/02 第 7 章 `GET /adherence`）。
 *
 * <p>只放与页面解耦的可单测逻辑：响应体容错归一化、按时率格式化、周期文案、
 * 趋势折线数据点、导出权限判定。时间字段（windowStart/End、dailyBreakdown.date）一律原样透传，
 * 仅做本地化展示（见 `pages/report/report.vue`），不做时区换算。
 *
 * <p>**非判定型**：08 的 `feat/mp-report` 未标 🧭，统计口径完全由后端 `record-report` 算好返回
 * （`onTimeRate`/`missCount` 等），本模块只做"缺字段兜底"，不重算任何业务规则。
 */

import { ROLE } from '@/store/user.js'

/** 合法的统计周期（02 第 7 章；非法 → 后端 40001）。 */
export const ADHERENCE_PERIODS = ['day', 'week', 'month']

/** 周期 → 中文短标签（页面分段选择器与副标题用）。 */
const PERIOD_LABEL = {
  day: '日',
  week: '周',
  month: '月',
}

/**
 * `GET /adherence` 响应容错归一化。
 *
 * <p>⚠️ 后端 `record-report` 已落地，响应形状稳定；此处仅在"缺字段/脏数据"时兜底，
 * 避免空响应导致页面 NaN：
 * - `totalDoses=0` → `onTimeRate=0.0`（不出现除零）；
 * - 后端缺失 `onTimeRate` 时按 `onTimeCount/totalDoses` 兜底；
 * - `dailyBreakdown` 缺省 `[]`，单项同样兜底 `totalDoses=0→rate=0`、缺失 `onTimeRate` 按 `onTimeCount/dayTotal` 兜底；
 * - 各项计数缺省 0。
 *
 * @param {Object|null} data 请求层兑现的 `data`
 * @returns {{
 *   elderId: string, period: string, windowStart: ?string, windowEnd: ?string,
 *   totalDoses: number, onTimeCount: number, lateCount: number, unjudgedCount: number,
 *   onTimeRate: number, missCount: number,
 *   dailyBreakdown: Array<{date:string, onTimeRate:number, totalDoses:number, onTimeCount:number, missCount:number}>
 * }}
 */
export function parseAdherence(data) {
  const src = data || {}
  const totalDoses = Number(src.totalDoses) || 0
  const onTimeCount = Number(src.onTimeCount) || 0
  const lateCount = Number(src.lateCount) || 0
  const unjudgedCount = Number(src.unjudgedCount) || 0
  const missCount = Number(src.missCount) || 0

  let onTimeRate = 0
  if (totalDoses === 0) {
    onTimeRate = 0.0
  } else if (typeof src.onTimeRate === 'number' && isFinite(src.onTimeRate)) {
    onTimeRate = src.onTimeRate
  } else {
    onTimeRate = onTimeCount / totalDoses
  }

  const dailyBreakdown = Array.isArray(src.dailyBreakdown)
    ? src.dailyBreakdown.map((d) => {
        const dayTotal = Number(d.totalDoses) || 0
        const dayOnTime = Number(d.onTimeCount) || 0
        let dayRate = 0
        if (dayTotal === 0) {
          dayRate = 0.0
        } else if (typeof d.onTimeRate === 'number' && isFinite(d.onTimeRate)) {
          dayRate = d.onTimeRate
        } else {
          dayRate = dayOnTime / dayTotal
        }
        return {
          date: d.date || '',
          onTimeRate: dayRate,
          totalDoses: dayTotal,
          onTimeCount: dayOnTime,
          missCount: Number(d.missCount) || 0,
        }
      })
    : []

  return {
    elderId: src.elderId || '',
    period: src.period || '',
    windowStart: src.windowStart || null,
    windowEnd: src.windowEnd || null,
    totalDoses,
    onTimeCount,
    lateCount,
    unjudgedCount,
    onTimeRate,
    missCount,
    dailyBreakdown,
  }
}

/**
 * 按时率（0.0~1.0）→ 百分比文案（默认一位小数：`85.0%`）。
 * 非数字一律按 0 处理，避免 NaN 上屏。
 *
 * @param {number} rate
 * @param {number} [digits=1]
 * @returns {string}
 */
export function formatRate(rate, digits = 1) {
  const r = typeof rate === 'number' && isFinite(rate) ? rate : 0
  return `${(r * 100).toFixed(digits)}%`
}

/**
 * 周期中文短标签。
 * @param {string} period `day`/`week`/`month`
 * @returns {string} 日/周/月（未知回退空串）
 */
export function adherencePeriodLabel(period) {
  return PERIOD_LABEL[period] || ''
}

/**
 * 归一化视图（直接喂页面渲染）。
 *
 * @param {Object|null} data `GET /adherence` 的 `data`
 * @returns {Object} 含 `parseAdherence` 全部字段 + `periodLabel` + `rateText` + `hasData`
 */
export function toAdherenceView(data) {
  const parsed = parseAdherence(data)
  return {
    ...parsed,
    periodLabel: adherencePeriodLabel(parsed.period),
    rateText: formatRate(parsed.onTimeRate),
    hasData: parsed.totalDoses > 0,
  }
}

/**
 * 趋势折线数据点：把 `dailyBreakdown` 映射为 `[{ date, rate }]`，
 * `rate` 为 0~100 的按时率（便于 canvas y 轴 0~100 绘制）。按日期升序。
 *
 * @param {Array} dailyBreakdown `parseAdherence` 后的 `dailyBreakdown`
 * @returns {Array<{date:string, rate:number}>}
 */
export function buildChartPoints(dailyBreakdown) {
  if (!Array.isArray(dailyBreakdown)) return []
  return dailyBreakdown
    .map((d) => ({
      date: d.date || '',
      rate: typeof d.onTimeRate === 'number' && isFinite(d.onTimeRate) ? d.onTimeRate * 100 : 0,
    }))
    .sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0))
}

/**
 * 当前角色是否可见导出入口（`EXPORT_RECORD`）。
 *
 * <p>02 的 1.3 权限矩阵：`EXPORT_RECORD` 仅 GUARDIAN（老人不可），后端强校验；
 * 小程序注册角色只有 `ELDER`/`GUARDIAN`，社区护理/医生是 `guardian_relation.role` 不暴露，
 * 故以 `GUARDIAN` 代理（同 `canWriteRecord` 口径），精细权限交给后端 `EXPORT_RECORD` 兜底。
 *
 * @param {string} role `store/user.js` 的角色
 * @returns {boolean}
 */
export function canExportRecord(role) {
  return role === ROLE.GUARDIAN
}
