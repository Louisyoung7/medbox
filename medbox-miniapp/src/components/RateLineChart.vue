<template>
  <view class="rate-chart">
    <canvas
      type="2d"
      id="rateChart"
      class="rate-chart__canvas"
      :style="{ width: widthPx + 'px', height: heightPx + 'px' }"
    ></canvas>
  </view>
</template>

<script>
/**
 * 单序列折线图（feat/mp-report）：按时率趋势，x = 本地日期、y = 按时率 0~100%。
 *
 * <p>uni-app `<canvas type="2d">` 自绘，H5 / 微信小程序通用，不引入第三方图表库
 * （与 `EnvLineChart` 同一技术路线，微信端不支持内联 SVG）。
 *
 * <p>宽高由父组件按屏幕宽度传入（px），dpr 自适应；数据/尺寸变化时只重绘、不重建 canvas。
 * 空数据（无服药记录）时清空并交父组件空态文案处理。
 */

const PRIMARY = '#0E9F8E'
const PRIMARY_SOFT = 'rgba(14,159,142,0.12)'
const AXIS = '#E5EAF0'
const TEXT = '#9AA5B1'

export default {
  name: 'RateLineChart',
  props: {
    points: { type: Array, default: () => [] },
    widthPx: { type: Number, default: 330 },
    heightPx: { type: Number, default: 220 },
  },
  watch: {
    points() {
      this.draw()
    },
  },
  mounted() {
    this.draw()
  },
  methods: {
    draw() {
      if (typeof uni === 'undefined' || !uni.createSelectorQuery) return
      uni
        .createSelectorQuery()
        .in(this)
        .select('#rateChart')
        .fields({ node: true, size: true })
        .exec((res) => {
          if (!res || !res[0] || !res[0].node) return
          const canvas = res[0].node
          const ctx = canvas.getContext('2d')
          if (!ctx) return
          const dpr =
            (uni.getSystemInfoSync && uni.getSystemInfoSync().pixelRatio) || 1
          const w = this.widthPx
          const h = this.heightPx
          canvas.width = w * dpr
          canvas.height = h * dpr
          ctx.scale(dpr, dpr)
          ctx.clearRect(0, 0, w, h)

          const padL = 34
          const padR = 12
          const padT = 14
          const padB = 24
          const plotW = w - padL - padR
          const plotH = h - padT - padB

          // 横向网格 + y 轴标签（0 / 50 / 100）
          ctx.strokeStyle = AXIS
          ctx.lineWidth = 1
          ctx.fillStyle = TEXT
          ctx.font = '10px sans-serif'
          ctx.textAlign = 'right'
          ctx.textBaseline = 'middle'
          ;[0, 50, 100].forEach((v) => {
            const y = padT + plotH * (1 - v / 100)
            ctx.beginPath()
            ctx.moveTo(padL, y)
            ctx.lineTo(w - padR, y)
            ctx.stroke()
            ctx.fillText(`${v}`, padL - 6, y)
          })

          const pts = Array.isArray(this.points) ? this.points : []
          if (!pts.length) {
            ctx.fillStyle = TEXT
            ctx.textAlign = 'center'
            ctx.textBaseline = 'middle'
            ctx.fillText('暂无数据', w / 2, padT + plotH / 2)
            return
          }

          const n = pts.length
          const xAt = (i) => (n === 1 ? padL + plotW / 2 : padL + (plotW * i) / (n - 1))
          const yAt = (rate) => padT + plotH * (1 - Math.max(0, Math.min(100, rate)) / 100)

          // 面积填充
          const grad = ctx.createLinearGradient(0, padT, 0, padT + plotH)
          grad.addColorStop(0, PRIMARY_SOFT)
          grad.addColorStop(1, 'rgba(14,159,142,0)')
          ctx.beginPath()
          ctx.moveTo(xAt(0), padT + plotH)
          pts.forEach((p, i) => ctx.lineTo(xAt(i), yAt(p.rate)))
          ctx.lineTo(xAt(n - 1), padT + plotH)
          ctx.closePath()
          ctx.fillStyle = grad
          ctx.fill()

          // 折线
          ctx.strokeStyle = PRIMARY
          ctx.lineWidth = 2
          ctx.lineJoin = 'round'
          ctx.beginPath()
          pts.forEach((p, i) => {
            const x = xAt(i)
            const y = yAt(p.rate)
            if (i === 0) ctx.moveTo(x, y)
            else ctx.lineTo(x, y)
          })
          ctx.stroke()

          // 末点实心圆
          const last = pts[n - 1]
          ctx.fillStyle = PRIMARY
          ctx.beginPath()
          ctx.arc(xAt(n - 1), yAt(last.rate), 3, 0, Math.PI * 2)
          ctx.fill()

          // x 轴标签：首 / 中 / 尾（避免拥挤）
          ctx.fillStyle = TEXT
          ctx.font = '10px sans-serif'
          ctx.textBaseline = 'top'
          const labelIdx = n === 1 ? [0] : [0, Math.floor((n - 1) / 2), n - 1]
          labelIdx.forEach((i) => {
            const short = (pts[i].date || '').slice(5) // MM-DD
            ctx.textAlign = i === 0 ? 'left' : i === n - 1 ? 'right' : 'center'
            ctx.fillText(short, xAt(i), padT + plotH + 6)
          })
        })
    },
  },
}
</script>

<style lang="scss" scoped>
.rate-chart {
  width: 100%;
  display: flex;
  justify-content: center;

  &__canvas {
    display: block;
  }
}
</style>
