<template>
  <view class="env-chart">
    <canvas
      type="2d"
      id="envChart"
      class="env-chart__canvas"
      :style="{ width: widthPx + 'px', height: heightPx + 'px' }"
    ></canvas>
  </view>
</template>

<script>
/**
 * 环境监测折线图（feat/mp-env）：uni-app `<canvas type="2d">` 自绘，H5 / 微信小程序通用。
 *
 * <p>不引入第三方图表库（项目无图表依赖、微信端不支持内联 SVG）。业务逻辑全在 `utils/env.js`
 * 的 `computeEnvChartGeometry`（纯函数、单测覆盖），本组件仅把几何画到 canvas：
 * - 每个指标一段独立纵向区域（small-multiple），各自缩放，避免温/湿/光量级差异互相压扁；
 * - 阈值带上/下界画虚线（缺边不画），合规区天然落在两线之间；
 * - 折线跳过 `null` 点（原始模式下温湿度/光照分属不同传感器，单行只带一类值）。
 *
 * <p>宽高由父组件按屏幕宽度传入（px），dpr 自适应；数据/指标/阈值变化时只重绘、不重建 canvas。
 */
import { computeEnvChartGeometry } from '@/utils/env.js'

const COLORS = {
  temperature: '#E8833A',
  humidity: '#0E9F8E',
  lux: '#2563EB',
}

export default {
  name: 'EnvLineChart',
  props: {
    points: { type: Array, default: () => [] },
    metrics: { type: Array, default: () => ['temperature', 'humidity', 'lux'] },
    thresholds: { type: Object, default: () => ({}) },
    widthPx: { type: Number, default: 330 },
    heightPx: { type: Number, default: 240 },
  },
  watch: {
    points() {
      this.draw()
    },
    metrics() {
      this.draw()
    },
    thresholds() {
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
        .select('#envChart')
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
          if (!this.points.length) return

          const geo = computeEnvChartGeometry({
            points: this.points,
            metrics: this.metrics,
            thresholds: this.thresholds,
            width: w,
            height: h,
          })

          geo.bands.forEach((band) => {
            const color = COLORS[band.key] || '#888'
            const top = band.bandTop
            const bottom = top + band.bandH

            // 段分隔基线（最后一段不画）
            ctx.strokeStyle = '#EEF1F4'
            ctx.lineWidth = 1
            ctx.beginPath()
            ctx.moveTo(0, bottom)
            ctx.lineTo(w, bottom)
            ctx.stroke()

            // 阈值虚线（max 上界 / min 下界）
            if (band.threshold) {
              ctx.save()
              ctx.setLineDash([4, 4])
              ctx.strokeStyle = 'rgba(220,38,38,0.55)'
              if (band.threshold.max != null) {
                ctx.beginPath()
                ctx.moveTo(0, band.threshold.max)
                ctx.lineTo(w, band.threshold.max)
                ctx.stroke()
              }
              if (band.threshold.min != null) {
                ctx.beginPath()
                ctx.moveTo(0, band.threshold.min)
                ctx.lineTo(w, band.threshold.min)
                ctx.stroke()
              }
              ctx.restore()
            }

            // 折线（跳过 null 点，断点重起）
            ctx.strokeStyle = color
            ctx.lineWidth = 2
            ctx.lineJoin = 'round'
            ctx.beginPath()
            let pen = false
            band.line.forEach((pt) => {
              if (pt.value == null) {
                pen = false
                return
              }
              if (!pen) {
                ctx.moveTo(pt.x, pt.y)
                pen = true
              } else {
                ctx.lineTo(pt.x, pt.y)
              }
            })
            ctx.stroke()

            // 末点实心圆
            for (let i = band.line.length - 1; i >= 0; i -= 1) {
              if (band.line[i].value != null) {
                ctx.fillStyle = color
                ctx.beginPath()
                ctx.arc(band.line[i].x, band.line[i].y, 3, 0, Math.PI * 2)
                ctx.fill()
                break
              }
            }
          })
        })
    },
  },
}
</script>

<style lang="scss" scoped>
.env-chart {
  width: 100%;
  display: flex;
  justify-content: center;

  &__canvas {
    display: block;
  }
}
</style>
