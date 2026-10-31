<template>
  <view class="page">
    <view class="head">
      <view class="head__left">
        <text class="head__title">服药依从性报告</text>
        <text class="head__sub">按时率 · 漏服趋势（近{{ periodLabel }}）</text>
      </view>
    </view>

    <!-- 周期分段选择：日 / 周 / 月 滚动窗口 -->
    <view class="periods">
      <view
        v-for="p in periods"
        :key="p.value"
        class="periods__item"
        :class="{ 'periods__item--on': p.value === period }"
        hover-class="periods__item--hover"
        @click="selectPeriod(p.value)"
      >
        <text class="periods__text">近{{ p.label }}</text>
      </view>
    </view>

    <!-- 老人选择器：监护人可见；候选来自可见设备的 elderId（同 record 模式）。
         老人端只读本人，显示静态文案。 -->
    <scroll-view v-if="canWrite && elders.length > 1" class="elders" scroll-x :show-scrollbar="false">
      <view
        v-for="elder in elders"
        :key="elder"
        class="elders__item"
        :class="{ 'elders__item--on': elder === elderId }"
        @click="selectElder(elder)"
      >
        <text class="elders__text">{{ elder }}</text>
      </view>
    </scroll-view>
    <view v-else-if="!canWrite" class="elders-tip">我的报告（只读）</view>

    <MbEmpty v-if="error" :text="error" />

    <view v-else-if="loading && !loaded" class="loading">
      <text class="loading__text">加载中…</text>
    </view>

    <template v-else-if="view">
      <!-- 核心指标摘要卡 -->
      <view class="summary">
        <view class="summary__hero">
          <view class="summary__hero-top">
            <text class="summary__rate" :class="rateClass">{{ view.rateText }}</text>
            <text class="summary__rate-label">按时率</text>
          </view>
          <text class="summary__hero-sub">近{{ view.periodLabel }}共 {{ view.totalDoses }} 次服药</text>
        </view>
        <view class="summary__grid">
          <view class="metric metric--miss">
            <text class="metric__num">{{ view.missCount }}</text>
            <text class="metric__label">漏服</text>
          </view>
          <view class="metric metric--late">
            <text class="metric__num">{{ view.lateCount }}</text>
            <text class="metric__label">迟到</text>
          </view>
          <view class="metric metric--judge">
            <text class="metric__num">{{ view.unjudgedCount }}</text>
            <text class="metric__label">未判定</text>
          </view>
        </view>
      </view>

      <!-- 每日按时率趋势折线 -->
      <MbCard title="每日按时率趋势">
        <view class="chart">
          <RateLineChart
            v-if="chartPoints.length"
            :points="chartPoints"
            :width-px="chartWidth"
            :height-px="220"
          />
          <view v-else class="chart__empty">
            <text class="chart__empty-text">该区间暂无服药记录</text>
          </view>
        </view>
      </MbCard>

      <!-- 导出：仅监护人（EXPORT_RECORD；RoleGate entry=exportRecord） -->
      <RoleGate entry="exportRecord">
        <MbButton type="primary" block :loading="exporting" @click="onExport">导出 CSV 报告</MbButton>
      </RoleGate>
    </template>

    <MbTabBar />
  </view>
</template>

<script>
import MbButton from '@/components/MbButton.vue'
import MbCard from '@/components/MbCard.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import MbTabBar from '@/components/MbTabBar.vue'
import RateLineChart from '@/components/RateLineChart.vue'
import RoleGate from '@/components/RoleGate.vue'

import { listDevices } from '@/api/device.js'
import { getAdherence, exportRecords } from '@/api/report.js'
import { CODE } from '@/api/request.js'
import { canWriteRecord } from '@/utils/record.js'
import { canExportRecord, toAdherenceView, buildChartPoints, adherencePeriodLabel } from '@/utils/report.js'
import { getRole, getUserId } from '@/store/user.js'

const PERIODS = [
  { value: 'day', label: '日' },
  { value: 'week', label: '周' },
  { value: 'month', label: '月' },
]

/** 错误文案按 code 细分（请求已 silent，页面统一自绘）。 */
function errorText(err) {
  const code = err && err.code
  if (code === CODE.UNAUTHORIZED) return '登录已失效，请重新登录'
  if (code === CODE.FORBIDDEN) return '无权限访问该资源'
  if (code === CODE.GUARDIAN_PENDING) return '监护关系未生效，请等待老人确认'
  if (code === CODE.BAD_REQUEST) return '参数不合法，请稍后再试'
  if (code === CODE.NOT_FOUND) return '资源不存在'
  return (err && err.message) || '加载失败，请稍后再试'
}

export default {
  name: 'ReportPage',
  components: { MbButton, MbCard, MbEmpty, MbTabBar, RateLineChart, RoleGate },
  data() {
    return {
      periods: PERIODS,
      period: 'week',
      canWrite: false,
      myUserId: '',
      elders: [],
      elderId: '',
      view: null,
      chartPoints: [],
      chartWidth: 330,
      loading: false,
      loaded: false,
      error: '',
      exporting: false,
    }
  },
  computed: {
    periodLabel() {
      return adherencePeriodLabel(this.period)
    },
    // 按时率语义色：≥80% 主色、50%~80% 警示橙、<50% 危险红
    rateClass() {
      const r = this.view ? this.view.onTimeRate : 0
      if (r >= 0.8) return 'summary__rate--ok'
      if (r >= 0.5) return 'summary__rate--warn'
      return 'summary__rate--danger'
    },
  },
  onLoad(query) {
    this.canWrite = canWriteRecord(getRole())
    this.myUserId = getUserId()
    this.chartWidth = this.computeChartWidth()
    const q = query || {}
    const fromQuery = q.elderId ? String(q.elderId).trim() : ''
    this.elderId = fromQuery || (this.canWrite ? '' : this.myUserId)
    this.load()
  },
  methods: {
    computeChartWidth() {
      let w = 375
      try {
        const info =
          (typeof uni !== 'undefined' && uni.getWindowInfo && uni.getWindowInfo()) ||
          (typeof uni !== 'undefined' && uni.getSystemInfoSync && uni.getSystemInfoSync())
        w = (info && info.windowWidth) || 375
      } catch (e) {
        /* 默认值兜底 */
      }
      // 页面左右各 24rpx 内边距 → 24/750*windowWidth
      return Math.floor(w - (48 / 750) * w)
    },
    async load() {
      if (!this.elderId) {
        await this.loadElders()
        if (!this.elderId) {
          this.error = '暂无可查看的老人，请先为其登记药箱设备'
          return
        }
      }
      this.loading = true
      this.error = ''
      try {
        await this.fetch()
        this.loaded = true
      } catch (err) {
        this.error = errorText(err)
      } finally {
        this.loading = false
      }
    },
    async loadElders() {
      const devices = await listDevices({ silent: true })
      const ids = []
      ;(devices || []).forEach((d) => {
        if (d.elderId && ids.indexOf(d.elderId) === -1) ids.push(d.elderId)
      })
      this.elders = ids
      if (ids.length && !this.elderId) this.elderId = ids[0]
    },
    async fetch() {
      const data = await getAdherence({ elderId: this.elderId, period: this.period }, { silent: true })
      this.view = toAdherenceView(data)
      this.chartPoints = buildChartPoints(this.view.dailyBreakdown)
    },
    selectPeriod(value) {
      if (value === this.period) return
      this.period = value
      this.reload()
    },
    selectElder(elder) {
      if (elder === this.elderId) return
      this.elderId = elder
      this.reload()
    },
    reload() {
      this.view = null
      this.chartPoints = []
      this.load()
    },
    async onExport() {
      if (this.exporting) return
      this.exporting = true
      try {
        const res = await exportRecords({ elderId: this.elderId }, { silent: true })
        await this.saveAndShare(res)
        uni.showToast({ title: '导出成功', icon: 'success' })
      } catch (err) {
        uni.showToast({ title: errorText(err), icon: 'none' })
      } finally {
        this.exporting = false
      }
    },
    // 文件流落地：微信持久化临时文件 → 通过聊天转发 CSV（小程序内无法直接预览）；
    // 非微信端回退提示已保存本地路径。
    async saveAndShare(res) {
      const tmp = res.filePath || res.tempFilePath
      if (!tmp) {
        uni.showToast({ title: '导出失败：未获取文件', icon: 'none' })
        return
      }
      let filePath = tmp
      const fs = typeof uni !== 'undefined' && typeof uni.getFileSystemManager === 'function'
        ? uni.getFileSystemManager()
        : null
      if (fs && typeof fs.saveFile === 'function') {
        try {
          filePath = await new Promise((resolve, reject) => {
            fs.saveFile({ tempFilePath: tmp, success: (r) => resolve(r.savedFilePath), fail: reject })
          })
        } catch (e) {
          filePath = tmp
        }
      }
      if (typeof uni !== 'undefined' && typeof uni.shareFileMessage === 'function') {
        uni.shareFileMessage({
          filePath,
          fileName: res.filename || 'medication-records.csv',
          success: () => {},
          fail: () => {
            uni.showToast({ title: '已保存，可在聊天中发送文件查看', icon: 'none' })
          },
        })
      } else {
        uni.showToast({ title: '已保存至本地', icon: 'none' })
      }
    },
  },
}
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx calc(110rpx + env(safe-area-inset-bottom) + 24rpx);
  box-sizing: border-box;
  background: $mb-bg;
}

.head {
  display: flex;
  flex-direction: row;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: $mb-gap;

  &__left {
    display: flex;
    flex-direction: column;
  }

  &__title {
    font-size: 40rpx;
    font-weight: 600;
    color: $mb-text;
  }

  &__sub {
    margin-top: 8rpx;
    font-size: 26rpx;
    color: $mb-text-3;
  }
}

.periods {
  display: flex;
  gap: 16rpx;
  margin-bottom: 20rpx;

  &__item {
    flex: 1;
    text-align: center;
    padding: 18rpx 0;
    border-radius: 16rpx;
    background: #fff;
    border: 1rpx solid $mb-border;

    &--on {
      background: linear-gradient(135deg, #0E9F8E, #0FB6A3);
      border-color: transparent;
    }

    &--hover {
      opacity: 0.85;
    }
  }

  &__text {
    font-size: 28rpx;
    font-weight: 500;
    color: $mb-text-2;
  }

  &__item--on &__text {
    color: #fff;
  }
}

.elders {
  white-space: nowrap;
  margin-bottom: 16rpx;

  &__item {
    display: inline-flex;
    align-items: center;
    padding: 12rpx 28rpx;
    margin-right: 16rpx;
    border-radius: 999rpx;
    background: #fff;
    border: 1rpx solid $mb-border;
  }

  &__item--on {
    background: rgba(14, 159, 142, 0.12);
    border-color: $mb-primary;
  }

  &__text {
    font-size: 26rpx;
    color: $mb-text-2;
  }

  &__item--on &__text {
    color: $mb-primary;
    font-weight: 500;
  }
}

.elders-tip {
  padding: 12rpx 0;
  margin-bottom: 16rpx;
  font-size: 26rpx;
  color: $mb-text-3;
}

.loading {
  padding: 80rpx 0;
  text-align: center;

  &__text {
    font-size: 28rpx;
    color: $mb-text-3;
  }
}

.summary {
  background: #fff;
  border-radius: 24rpx;
  padding: 28rpx;
  margin-bottom: $mb-gap;
  box-shadow: 0 6rpx 24rpx rgba(31, 42, 55, 0.06);

  &__hero {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding-bottom: 24rpx;
    border-bottom: 1rpx solid $mb-border;
  }

  &__hero-top {
    display: flex;
    align-items: baseline;
    gap: 12rpx;
  }

  &__rate {
    font-size: 72rpx;
    font-weight: 700;
    line-height: 1;
    color: $mb-primary;

    &--ok {
      color: #0E9F8E;
    }

    &--warn {
      color: #B7791F;
    }

    &--danger {
      color: #DC2626;
    }
  }

  &__rate-label {
    font-size: 28rpx;
    color: $mb-text-3;
  }

  &__hero-sub {
    margin-top: 12rpx;
    font-size: 24rpx;
    color: $mb-text-3;
  }

  &__grid {
    display: flex;
    margin-top: 24rpx;
  }
}

.metric {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;

  &__num {
    font-size: 40rpx;
    font-weight: 600;
    color: $mb-text;
  }

  &__label {
    margin-top: 6rpx;
    font-size: 24rpx;
    color: $mb-text-3;
  }

  &--miss &__num {
    color: #DC2626;
  }

  &--late &__num {
    color: #B7791F;
  }

  &--judge &__num {
    color: $mb-text-2;
  }
}

.chart {
  padding: 12rpx 0 4rpx;

  &__empty {
    padding: 60rpx 0;
    text-align: center;
  }

  &__empty-text {
    font-size: 26rpx;
    color: $mb-text-3;
  }
}
</style>
