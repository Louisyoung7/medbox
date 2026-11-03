<template>
  <div ref="el" class="echart" :style="{ width, height }"></div>
</template>

<script setup>
import { ref, shallowRef, onMounted, onBeforeUnmount, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import {
  TooltipComponent,
  LegendComponent,
  GridComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

// 仅注册看板用到的图表/组件，按需打包，显著减小 bundle。
echarts.use([
  LineChart,
  BarChart,
  TooltipComponent,
  LegendComponent,
  GridComponent,
  CanvasRenderer,
])

const props = defineProps({
  option: { type: Object, required: true },
  width: { type: String, default: '100%' },
  height: { type: String, default: '320px' },
})

const el = ref(null)
const chart = shallowRef(null)
let ro = null

function render() {
  if (chart.value && props.option) {
    // notMerge: false 复用实例、增量合并，避免重复 init 造成的 canvas 泄漏与闪烁
    chart.value.setOption(props.option, { notMerge: false, lazyUpdate: true })
  }
}

onMounted(() => {
  chart.value = echarts.init(el.value, null, { renderer: 'canvas' })
  render()
  ro = new ResizeObserver(() => chart.value && chart.value.resize())
  ro.observe(el.value)
})

watch(() => props.option, render, { deep: true })

onBeforeUnmount(() => {
  if (ro && el.value) ro.unobserve(el.value)
  ro = null
  if (chart.value) {
    chart.value.dispose()
    chart.value = null
  }
})

// 暴露 resize 给父组件（如 Tab 切换后手动触发）
defineExpose({
  resize: () => chart.value && chart.value.resize(),
})
</script>

<style scoped>
.echart {
  display: block;
}
</style>
