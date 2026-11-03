<template>
  <div class="dash">
    <!-- 顶栏 -->
    <header class="topbar glass">
      <div class="brand">
        <span class="logo">📡</span>
        <span class="brand-name">MedBox 运营监控大屏</span>
        <span class="brand-tag">实时监控 · 只读聚合</span>
      </div>
      <div class="top-right">
        <span class="clock">{{ clock }}</span>
        <span class="admin">
          <el-icon><UserFilled /></el-icon>
          {{ auth.userId || auth.role || 'admin' }}
        </span>
        <el-button size="small" class="logout-btn" @click="onLogout">退出</el-button>
      </div>
    </header>

    <!-- 设备网络状态面板 -->
    <section class="panel glass">
      <div class="panel-title">
        <span class="dot" /> 设备网络状态
        <span class="sub">物联网 / 网络系统部署与管理</span>
      </div>
      <div class="metric-row">
        <div class="metric" v-for="m in deviceMetrics" :key="m.label">
          <div class="metric-icon" :style="{ color: m.color, background: m.bg }">
            <el-icon :size="22"><component :is="m.icon" /></el-icon>
          </div>
          <div class="metric-body">
            <div class="metric-value" :style="{ color: m.color }">{{ fmt(m.value) }}</div>
            <div class="metric-label">{{ m.label }}</div>
          </div>
        </div>
      </div>
      <el-table :data="devices" size="small" class="dev-table" :max-height="260" stripe>
        <el-table-column prop="deviceId" label="设备ID" width="150" />
        <el-table-column label="归属老人" min-width="110">
          <template #default="{ row }">{{ row.elderName || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ONLINE' ? 'success' : 'info'" size="small" effect="dark">
              {{ row.status === 'ONLINE' ? '在线' : '离线' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近心跳" min-width="170">
          <template #default="{ row }">{{ relTime(row.lastHeartbeat) }}</template>
        </el-table-column>
      </el-table>
    </section>

    <!-- 实时服药概览 -->
    <section class="panel glass">
      <div class="panel-title">
        <span class="dot dot-live" /> 实时服药概览
        <span class="refresh-hint">每 10s 自动刷新</span>
      </div>
      <div class="dose-row">
        <div class="dose" v-for="d in doseCards" :key="d.label">
          <div class="dose-value" :style="{ color: d.color, textShadow: `0 0 18px ${d.glow}` }">
            {{ fmt(d.value) }}
          </div>
          <div class="dose-label">{{ d.label }}</div>
        </div>
      </div>
    </section>

    <!-- 统计看板 -->
    <section class="panel glass">
      <div class="panel-title">
        <span class="dot" /> 服药统计看板
        <el-radio-group v-model="granularity" size="small" class="gran" @change="onGranChange">
          <el-radio-button value="week">近 7 天</el-radio-button>
          <el-radio-button value="month">近 30 天</el-radio-button>
        </el-radio-group>
      </div>
      <div class="chart-grid">
        <div class="chart-card">
          <div class="chart-name">服药依从率趋势</div>
          <EChart :option="trendOption" height="300px" />
        </div>
        <div class="chart-card">
          <div class="chart-name">漏服时段分布（按小时）</div>
          <EChart :option="missHourOption" height="300px" />
        </div>
        <div class="chart-card">
          <div class="chart-name">按老人依从率 Top10</div>
          <EChart :option="byElderOption" height="300px" />
        </div>
      </div>
    </section>

    <!-- 消息流面板 -->
    <section class="panel glass">
      <div class="panel-title">
        <span class="dot" /> MQTT 消息流
        <span class="sub">演示用 · 上行 / 下行吞吐</span>
      </div>
      <div class="metric-row">
        <div class="metric" v-for="m in msgMetrics" :key="m.label">
          <div class="metric-icon" :style="{ color: m.color, background: m.bg }">
            <el-icon :size="22"><component :is="m.icon" /></el-icon>
          </div>
          <div class="metric-body">
            <div class="metric-value" :style="{ color: m.color }">{{ fmt(m.value) }}</div>
            <div class="metric-label">{{ m.label }}</div>
          </div>
        </div>
      </div>
    </section>

    <footer class="foot">数据来源：medbox 后端聚合接口 · 仅只读展示，不改数据</footer>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  UserFilled,
  Cpu,
  Connection,
  Link,
  SwitchButton,
  Top,
  Bottom,
} from '@element-plus/icons-vue'
import EChart from '../components/EChart.vue'
import { getOverview, getDevices, getAdherence, getMessages } from '../api/dashboard'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()

const overview = ref(null)
const devices = ref([])
const adherence = ref({ trend: [], missByHour: [], byElder: [] })
const messages = ref(null)
const granularity = ref('week')
const loading = ref(false)
const lastError = ref('')
const clock = ref('')

let pollTimer = null
let clockTimer = null

const fmt = (v) => (v == null ? '—' : Number(v).toLocaleString('zh-CN'))

function relTime(iso) {
  if (!iso) return '—'
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return '—'
  const diff = Date.now() - t
  if (diff < 0) return '刚刚'
  const s = Math.floor(diff / 1000)
  if (s < 60) return `${s} 秒前`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m} 分钟前`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} 小时前`
  return `${Math.floor(h / 24)} 天前`
}

async function refresh() {
  loading.value = true
  const [o, d, a, m] = await Promise.allSettled([
    getOverview(),
    getDevices(20),
    getAdherence(granularity.value),
    getMessages(),
  ])
  if (o.status === 'fulfilled') overview.value = o.value
  else lastError.value = '总览加载失败'
  if (d.status === 'fulfilled') devices.value = d.value || []
  else lastError.value = '设备列表加载失败'
  if (a.status === 'fulfilled') adherence.value = a.value || { trend: [], missByHour: [], byElder: [] }
  else lastError.value = '统计加载失败'
  if (m.status === 'fulfilled') messages.value = m.value
  else lastError.value = '消息流加载失败'
  loading.value = false
  if (lastError.value) ElMessage.warning(lastError.value)
}

function onGranChange() {
  // 仅重拉统计，不重置全局轮询，避免重复请求与闪烁
  getAdherence(granularity.value)
    .then((a) => (adherence.value = a || { trend: [], missByHour: [], byElder: [] }))
    .catch(() => ElMessage.warning('统计加载失败'))
}

function onLogout() {
  auth.logout()
  router.replace('/login')
}

function tick() {
  clock.value = new Date().toLocaleString('zh-CN', { hour12: false })
}

// ---- 指标卡 ----
const deviceMetrics = computed(() => {
  const d = overview.value?.devices || {}
  return [
    { label: '设备总数', value: d.total ?? 0, color: '#22D3EE', bg: 'rgba(34,211,238,0.12)', icon: Cpu },
    { label: '在线', value: d.online ?? 0, color: '#34D399', bg: 'rgba(52,211,153,0.12)', icon: Connection },
    { label: '离线', value: d.offline ?? 0, color: '#F87171', bg: 'rgba(248,113,113,0.12)', icon: SwitchButton },
    { label: 'MQTT 连接', value: d.mqttConnected ?? 0, color: '#3B82F6', bg: 'rgba(59,130,246,0.12)', icon: Link },
  ]
})

const doseCards = computed(() => {
  const t = overview.value?.todayDose || {}
  return [
    { label: '应服', value: t.totalSlots ?? 0, color: '#22D3EE', glow: 'rgba(34,211,238,0.6)' },
    { label: '已服', value: t.taken ?? 0, color: '#34D399', glow: 'rgba(52,211,153,0.6)' },
    { label: '漏服', value: t.missed ?? 0, color: '#F87171', glow: 'rgba(248,113,113,0.6)' },
    { label: '待服', value: t.pending ?? 0, color: '#FBBF24', glow: 'rgba(251,191,36,0.6)' },
  ]
})

const msgMetrics = computed(() => {
  const m = messages.value || {}
  return [
    { label: '上行总量', value: m.upTotal ?? 0, color: '#22D3EE', bg: 'rgba(34,211,238,0.12)', icon: Top },
    { label: '上行/近1分', value: m.upLastMinute ?? 0, color: '#34D399', bg: 'rgba(52,211,153,0.12)', icon: Top },
    { label: '下行总量', value: m.downTotal ?? 0, color: '#3B82F6', bg: 'rgba(59,130,246,0.12)', icon: Bottom },
    { label: '下行/近1分', value: m.downLastMinute ?? 0, color: '#6366F1', bg: 'rgba(99,102,241,0.12)', icon: Bottom },
  ]
})

// ---- ECharts 暗色主题基底 ----
const AXIS = '#9FB3C8'
const SPLIT = 'rgba(159,179,200,0.12)'

// 依从率趋势：taken / missed 折线 + rate(%) 右轴
const trendOption = computed(() => {
  const t = adherence.value?.trend || []
  return {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(17,26,46,0.92)',
      borderColor: 'rgba(34,211,238,0.4)',
      textStyle: { color: '#E5EDF7' },
    },
    legend: { data: ['已服', '漏服', '依从率'], textStyle: { color: AXIS }, top: 0, right: 0 },
    grid: { left: 40, right: 50, top: 36, bottom: 28 },
    xAxis: {
      type: 'category',
      data: t.map((p) => p.bucket),
      axisLine: { lineStyle: { color: SPLIT } },
      axisLabel: { color: AXIS, fontSize: 11 },
    },
    yAxis: [
      { type: 'value', axisLabel: { color: AXIS }, splitLine: { lineStyle: { color: SPLIT } } },
      {
        type: 'value',
        max: 100,
        axisLabel: { color: '#22D3EE', formatter: '{value}%' },
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: '已服',
        type: 'line',
        smooth: true,
        data: t.map((p) => p.taken),
        itemStyle: { color: '#34D399' },
        areaStyle: { color: 'rgba(52,211,153,0.18)' },
      },
      {
        name: '漏服',
        type: 'line',
        smooth: true,
        data: t.map((p) => p.missed),
        itemStyle: { color: '#F87171' },
      },
      {
        name: '依从率',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        data: t.map((p) => +(p.rate * 100).toFixed(1)),
        itemStyle: { color: '#22D3EE' },
        lineStyle: { width: 2 },
      },
    ],
  }
})

// 漏服时段分布柱状图
const missHourOption = computed(() => {
  const raw = adherence.value?.missByHour || []
  // 补齐 0-23 小时，保证 x 轴连续
  const map = Object.fromEntries(raw.map((b) => [b.hour, b.count]))
  const hours = Array.from({ length: 24 }, (_, h) => h)
  return {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(17,26,46,0.92)',
      borderColor: 'rgba(34,211,238,0.4)',
      textStyle: { color: '#E5EDF7' },
    },
    grid: { left: 40, right: 16, top: 20, bottom: 28 },
    xAxis: {
      type: 'category',
      data: hours.map((h) => `${h}时`),
      axisLine: { lineStyle: { color: SPLIT } },
      axisLabel: { color: AXIS, fontSize: 10, interval: 2 },
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: AXIS },
      splitLine: { lineStyle: { color: SPLIT } },
    },
    series: [
      {
        type: 'bar',
        data: hours.map((h) => map[h] || 0),
        itemStyle: {
          borderRadius: [4, 4, 0, 0],
          color: {
            type: 'linear',
            x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: '#F87171' },
              { offset: 1, color: 'rgba(248,113,113,0.25)' },
            ],
          },
        },
      },
    ],
  }
})

// 按老人依从率 Top10 横向条形
const byElderOption = computed(() => {
  const list = [...(adherence.value?.byElder || [])]
    .sort((a, b) => b.rate - a.rate)
    .slice(0, 10)
    .reverse()
  const colorOf = (r) => (r >= 0.8 ? '#34D399' : r >= 0.5 ? '#FBBF24' : '#F87171')
  return {
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'item',
      backgroundColor: 'rgba(17,26,46,0.92)',
      borderColor: 'rgba(34,211,238,0.4)',
      textStyle: { color: '#E5EDF7' },
      formatter: (p) => `${p.name}<br/>依从率 ${(p.value).toFixed(1)}%`,
    },
    grid: { left: 80, right: 40, top: 10, bottom: 20 },
    xAxis: {
      type: 'value',
      max: 100,
      axisLabel: { color: AXIS, formatter: '{value}%' },
      splitLine: { lineStyle: { color: SPLIT } },
    },
    yAxis: {
      type: 'category',
      data: list.map((e) => e.elderName || e.elderId || '—'),
      axisLine: { lineStyle: { color: SPLIT } },
      axisLabel: { color: AXIS, fontSize: 11 },
    },
    series: [
      {
        type: 'bar',
        data: list.map((e) => ({
          value: +(e.rate * 100).toFixed(1),
          itemStyle: {
            borderRadius: [0, 6, 6, 0],
            color: colorOf(e.rate),
          },
        })),
        barWidth: '55%',
        label: { show: true, position: 'right', color: '#E5EDF7', formatter: '{c}%' },
      },
    ],
  }
})

onMounted(() => {
  tick()
  clockTimer = setInterval(tick, 1000)
  refresh()
  pollTimer = setInterval(refresh, 10000)
})

onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
  if (clockTimer) clearInterval(clockTimer)
})
</script>

<style scoped>
.dash {
  min-height: 100vh;
  padding: 16px 22px 32px;
  background:
    radial-gradient(1200px 600px at 12% -10%, rgba(34, 211, 238, 0.10), transparent 60%),
    radial-gradient(1000px 500px at 100% 0%, rgba(99, 102, 241, 0.12), transparent 55%),
    linear-gradient(160deg, #0b1220 0%, #111a2e 55%, #0b1220 100%);
  color: #e5edf7;
  font-family: 'PingFang SC', system-ui, sans-serif;
}
.glass {
  background: rgba(17, 26, 46, 0.55);
  border: 1px solid rgba(34, 211, 238, 0.18);
  border-radius: 16px;
  backdrop-filter: blur(10px);
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.35);
}
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  margin-bottom: 16px;
}
.brand { display: flex; align-items: center; gap: 10px; }
.logo { font-size: 24px; }
.brand-name { font-size: 20px; font-weight: 600; letter-spacing: 1px; }
.brand-tag {
  margin-left: 8px; font-size: 12px; color: #22d3ee;
  border: 1px solid rgba(34, 211, 238, 0.4); border-radius: 999px; padding: 1px 10px;
}
.top-right { display: flex; align-items: center; gap: 16px; color: #9fb3c8; font-size: 14px; }
.clock { font-variant-numeric: tabular-nums; }
.admin { display: inline-flex; align-items: center; gap: 4px; }
.logout-btn {
  border-color: rgba(248, 113, 113, 0.4); color: #f87171;
  background: rgba(248, 113, 113, 0.08);
}
.logout-btn:hover { background: rgba(248, 113, 113, 0.18); }

.panel { padding: 16px 18px; margin-bottom: 16px; }
.panel-title {
  display: flex; align-items: center; gap: 8px;
  font-size: 16px; font-weight: 600; margin-bottom: 14px; color: #e5edf7;
}
.panel-title .sub { font-size: 12px; font-weight: 400; color: #9fb3c8; margin-left: 6px; }
.panel-title .refresh-hint {
  margin-left: auto; font-size: 12px; color: #22d3ee;
  border: 1px solid rgba(34, 211, 238, 0.35); border-radius: 999px; padding: 1px 10px;
}
.dot { width: 8px; height: 8px; border-radius: 50%; background: #22d3ee; box-shadow: 0 0 10px #22d3ee; }
.dot-live { background: #34d399; box-shadow: 0 0 10px #34d399; animation: pulse 1.6s infinite; }
@keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.35; } }
.gran { margin-left: auto; }

.metric-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
.metric {
  display: flex; align-items: center; gap: 12px;
  padding: 14px 16px; border-radius: 12px;
  background: rgba(11, 18, 32, 0.5); border: 1px solid rgba(159, 179, 200, 0.12);
}
.metric-icon { width: 44px; height: 44px; border-radius: 12px; display: grid; place-items: center; }
.metric-value { font-size: 26px; font-weight: 700; font-variant-numeric: tabular-nums; line-height: 1; }
.metric-label { font-size: 13px; color: #9fb3c8; margin-top: 4px; }

.dev-table { margin-top: 14px; --el-table-bg-color: transparent; --el-table-tr-bg-color: transparent; }
.dev-table :deep(.el-table__row) { background: transparent !important; }

.dose-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
.dose {
  text-align: center; padding: 18px 10px; border-radius: 14px;
  background: rgba(11, 18, 32, 0.5); border: 1px solid rgba(159, 179, 200, 0.12);
  animation: breathe 2.4s ease-in-out infinite;
}
@keyframes breathe { 0%,100% { transform: translateY(0); } 50% { transform: translateY(-3px); } }
.dose-value { font-size: 40px; font-weight: 700; font-variant-numeric: tabular-nums; }
.dose-label { font-size: 13px; color: #9fb3c8; margin-top: 6px; }

.chart-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
.chart-card {
  padding: 12px; border-radius: 12px;
  background: rgba(11, 18, 32, 0.45); border: 1px solid rgba(159, 179, 200, 0.12);
}
.chart-name { font-size: 13px; color: #9fb3c8; margin-bottom: 6px; }

.foot { text-align: center; color: #5b6b82; font-size: 12px; margin-top: 8px; }

@media (max-width: 1100px) {
  .metric-row, .dose-row { grid-template-columns: repeat(2, 1fr); }
  .chart-grid { grid-template-columns: 1fr; }
}
</style>
