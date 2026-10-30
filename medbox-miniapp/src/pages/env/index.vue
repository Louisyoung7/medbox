<template>
  <view class="page">
    <MbLoading v-if="loading" fullscreen text="加载中" />
    <MbEmpty v-else-if="loadError" :text="loadError" />

    <template v-else-if="device">
      <!-- 实时读数 -->
      <MbCard title="实时读数">
        <view class="realtime-grid">
          <view
            v-for="m in realtimeCards"
            :key="m.key"
            class="realtime-card"
            :class="m.statusClass"
          >
            <text class="realtime-card__label">{{ m.label }}</text>
            <text class="realtime-card__value">
              {{ m.valueText }}<text class="realtime-card__unit">{{ m.unit }}</text>
            </text>
            <text class="realtime-card__range">{{ m.rangeText }}</text>
          </view>
        </view>
        <text v-if="realtime.sampleTime" class="realtime-time">
          采样时间 {{ formatDateTime(realtime.sampleTime) }}
        </text>
        <text v-if="realtimeError" class="realtime-time realtime-time--err">{{ realtimeError }}</text>
      </MbCard>

      <!-- 历史曲线 -->
      <MbCard title="历史曲线">
        <view class="filter-row">
          <view class="seg">
            <text
              v-for="r in RANGES"
              :key="r.value"
              class="seg__item"
              :class="{ 'seg__item--on': rangeDays === r.value }"
              @click="onRange(r.value)"
            >{{ r.label }}</text>
          </view>
        </view>
        <view class="filter-row">
          <view class="seg">
            <text
              v-for="s in SENSORS"
              :key="s.value"
              class="seg__item"
              :class="{ 'seg__item--on': sensorFilter === s.value }"
              @click="onSensor(s.value)"
            >{{ s.label }}</text>
          </view>
          <view class="seg">
            <text
              v-for="i in INTERVALS"
              :key="i.value"
              class="seg__item"
              :class="{ 'seg__item--on': interval === i.value }"
              @click="onInterval(i.value)"
            >{{ i.label }}</text>
          </view>
        </view>

        <EnvLineChart
          v-if="history.length"
          :points="history"
          :metrics="chartMetrics"
          :thresholds="threshold"
          :width-px="chartWidth"
          :height-px="chartHeight"
        />
        <MbEmpty v-else-if="historyError" :text="historyError" />
        <MbEmpty v-else text="暂无环境数据" />

        <view v-if="history.length" class="legend">
          <view v-for="m in chartMetrics" :key="m" class="legend__item">
            <text class="legend__dot" :class="'legend__dot--' + m"></text>
            <text class="legend__text">{{ metricLabel(m) }}</text>
          </view>
        </view>
      </MbCard>
    </template>
  </view>
</template>

<script>
import MbCard from '@/components/MbCard.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import MbLoading from '@/components/MbLoading.vue'
import EnvLineChart from '@/components/EnvLineChart.vue'
import { getDevice } from '@/api/device.js'
import { getEnvRealtime, getEnvHistory } from '@/api/env.js'
import { getAlarmSettings } from '@/api/alarm.js'
import { CODE } from '@/api/request.js'
import {
  ENV_METRICS,
  parseEnvRealtime,
  parseEnvHistory,
  toEnvThresholdView,
  envMetricStatus,
} from '@/utils/env.js'
import { formatDateTime } from '@/utils/datetime.js'

// ENV_METRICS 的 key（temperature/humidity/lux）→ 阈值视图 key（temp/humidity/light）
const METRIC_TO_THRESHOLD = {
  temperature: 'temp',
  humidity: 'humidity',
  lux: 'light',
}

export default {
  name: 'EnvPage',
  components: { MbCard, MbEmpty, MbLoading, EnvLineChart },
  data() {
    return {
      deviceId: '',
      device: null,
      elderId: '',
      loading: false,
      loadError: '',
      realtime: { temperature: null, humidity: null, lux: null, sampleTime: null },
      realtimeError: '',
      threshold: null,
      // 历史筛选
      rangeDays: 7,
      sensorFilter: 'ALL',
      interval: 0,
      history: [],
      historyError: '',
      RANGES: [
        { label: '近1天', value: 1 },
        { label: '近7天', value: 7 },
        { label: '近30天', value: 30 },
      ],
      SENSORS: [
        { label: '全部', value: 'ALL' },
        { label: '温湿度', value: 'TEMP_HUMI' },
        { label: '光照', value: 'LIGHT' },
      ],
      INTERVALS: [
        { label: '原始', value: 0 },
        { label: '10分钟', value: 10 },
        { label: '1小时', value: 60 },
        { label: '1天', value: 1440 },
      ],
      chartWidth: 330,
      chartHeight: 240,
    }
  },
  computed: {
    // 实时卡片：三指标，无读数显示 —
    realtimeCards() {
      return ENV_METRICS.map((m) => {
        const value = this.realtime[m.key]
        const range = this.threshold ? this.threshold[METRIC_TO_THRESHOLD[m.key]] : null
        const status = envMetricStatus(value, range)
        return {
          key: m.key,
          label: m.label,
          unit: m.unit,
          valueText: value == null ? '—' : String(value),
          rangeText: range
            ? `合规 ${range.min ?? '·'}~${range.max ?? '·'}${m.unit}`
            : '未配置阈值',
          statusClass:
            status === 'high'
              ? 'realtime-card--high'
              : status === 'low'
                ? 'realtime-card--low'
                : '',
        }
      })
    },
    // 当前传感器筛选下要绘制的指标
    chartMetrics() {
      if (this.sensorFilter === 'TEMP_HUMI') return ['temperature', 'humidity']
      if (this.sensorFilter === 'LIGHT') return ['lux']
      return ['temperature', 'humidity', 'lux']
    },
  },
  onLoad(options) {
    this.deviceId = (options && options.deviceId) || ''
    this.chartWidth = Math.min(330, (uni.getSystemInfoSync().windowWidth || 375) - 48)
    this.load()
  },
  methods: {
    formatDateTime,
    metricLabel(key) {
      const meta = ENV_METRICS.find((m) => m.key === key)
      return meta ? meta.label : key
    },
    async load() {
      if (!this.deviceId) {
        this.loadError = '缺少设备编号'
        return
      }
      this.loading = true
      this.loadError = ''
      try {
        // 先取设备（拿到 elderId + 传感器列表），再并发实时 + 阈值
        this.device = await getDevice(this.deviceId)
        this.elderId = this.device.elderId || ''
        await Promise.all([this.loadRealtime(), this.loadThreshold()])
        await this.loadHistory()
      } catch (err) {
        this.loadError =
          err && err.code === CODE.FORBIDDEN
            ? '无权访问或设备不存在'
            : (err && err.message) || '加载失败'
      } finally {
        this.loading = false
      }
    },
    async loadRealtime() {
      try {
        const data = await getEnvRealtime(this.deviceId, {})
        this.realtime = parseEnvRealtime(data)
        this.realtimeError = ''
      } catch (err) {
        this.realtime = { temperature: null, humidity: null, lux: null, sampleTime: null }
        this.realtimeError =
          err && err.code === CODE.FORBIDDEN ? '无权访问或设备不存在' : '实时读数加载失败'
      }
    },
    async loadThreshold() {
      if (!this.elderId) return
      try {
        const data = await getAlarmSettings({ elderId: this.elderId })
        this.threshold = toEnvThresholdView(data)
      } catch (err) {
        // 阈值读取失败不影响读数展示，仅不高亮
        this.threshold = null
      }
    },
    async loadHistory() {
      this.historyError = ''
      const to = new Date()
      const from = new Date(to.getTime() - this.rangeDays * 86400000)
      const sensorId = this.buildSensorId()
      try {
        const data = await getEnvHistory(this.deviceId, {
          sensorId,
          from: from.toISOString(),
          to: to.toISOString(),
          interval: this.interval || undefined,
        })
        this.history = parseEnvHistory(data)
      } catch (err) {
        this.history = []
        this.historyError =
          err && err.code === CODE.BAD_REQUEST
            ? '时间范围不合法（跨度≤30天）'
            : err && err.code === CODE.FORBIDDEN
              ? '无权访问或设备不存在'
              : '历史数据加载失败'
      }
    },
    buildSensorId() {
      if (this.sensorFilter === 'ALL') return undefined
      const sensor = (this.device.sensors || []).find(
        (s) => s.deviceType === this.sensorFilter,
      )
      return sensor ? sensor.deviceId : undefined
    },
    onRange(v) {
      this.rangeDays = v
      this.loadHistory()
    },
    onSensor(v) {
      this.sensorFilter = v
      this.loadHistory()
    },
    onInterval(v) {
      this.interval = v
      this.loadHistory()
    },
  },
}
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 48rpx;
  box-sizing: border-box;
}

/* 实时三卡 */
.realtime-grid {
  display: flex;
  gap: 16rpx;
}

.realtime-card {
  flex: 1;
  background: $mb-bg;
  border-radius: $mb-radius;
  padding: 24rpx 16rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  border: 2rpx solid transparent;

  &--high {
    border-color: $mb-danger;
    background: rgba(220, 38, 38, 0.06);
  }

  &--low {
    border-color: $mb-primary;
    background: rgba(37, 99, 235, 0.06);
  }

  &__label {
    font-size: 24rpx;
    color: $mb-text-3;
  }

  &__value {
    font-size: 44rpx;
    font-weight: 600;
    color: $mb-text;
    margin: 10rpx 0 6rpx;
  }

  &__unit {
    font-size: 24rpx;
    font-weight: 400;
    color: $mb-text-3;
    margin-left: 4rpx;
  }

  &__range {
    font-size: 20rpx;
    color: $mb-text-4;
  }
}

.realtime-time {
  display: block;
  margin-top: 16rpx;
  font-size: 22rpx;
  color: $mb-text-4;

  &--err {
    color: $mb-danger;
  }
}

/* 历史筛选 */
.filter-row {
  display: flex;
  gap: 16rpx;
  margin-bottom: 16rpx;
  flex-wrap: wrap;
}

.seg {
  display: flex;
  background: $mb-bg;
  border-radius: $mb-radius;
  padding: 4rpx;

  &__item {
    font-size: 24rpx;
    color: $mb-text-3;
    padding: 10rpx 20rpx;
    border-radius: $mb-radius;
    cursor: pointer;

    &--on {
      background: #fff;
      color: $mb-text;
      font-weight: 600;
      box-shadow: $mb-shadow;
    }
  }
}

.legend {
  display: flex;
  gap: 28rpx;
  margin-top: 16rpx;
  justify-content: center;

  &__item {
    display: flex;
    align-items: center;
  }

  &__dot {
    width: 16rpx;
    height: 16rpx;
    border-radius: 50%;
    margin-right: 8rpx;

    &--temperature {
      background: #e8833a;
    }

    &--humidity {
      background: #0e9f8e;
    }

    &--lux {
      background: #2563eb;
    }
  }

  &__text {
    font-size: 22rpx;
    color: $mb-text-3;
  }
}
</style>
