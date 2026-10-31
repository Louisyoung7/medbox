<template>
  <view class="page">
    <view class="head">
      <view class="head__left">
        <text class="head__title">服药记录</text>
        <text class="head__sub">服药明细与抓拍留证</text>
      </view>
      <view class="head__action" hover-class="head__action--hover" @click="goReport">
        <text class="head__action-text">依从性报告 ›</text>
      </view>
    </view>

    <!-- 老人选择器：监护人可见；候选来自可见设备的 elderId（P1 mp-guardian 前无老人列表接口，同 plan 模式）。
         老人端为只读本人的记录，显示静态文案。 -->
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
    <view v-else-if="!canWrite" class="elders-tip">我的记录（只读）</view>

    <!-- 时间区间筛选：按日期起止过滤（02 第 7 章 from/to，UTC；本期只用到日粒度）。 -->
    <view class="filter">
      <picker class="filter__picker" mode="date" :value="fromDate" @change="onFromChange">
        <view class="filter__field">
          <text class="filter__label">起</text>
          <text class="filter__value" :class="{ 'filter__value--ph': !fromDate }">{{ fromDate || '不限' }}</text>
        </view>
      </picker>
      <text class="filter__sep">至</text>
      <picker class="filter__picker" mode="date" :value="toDate" @change="onToChange">
        <view class="filter__field">
          <text class="filter__label">止</text>
          <text class="filter__value" :class="{ 'filter__value--ph': !toDate }">{{ toDate || '不限' }}</text>
        </view>
      </picker>
      <view
        v-if="fromDate || toDate"
        class="filter__reset"
        hover-class="filter__reset--hover"
        @click="resetRange"
      >
        <text class="filter__reset-text">重置</text>
      </view>
    </view>

    <view v-if="canWrite" class="pending-toggle" hover-class="pending-toggle--hover" @click="togglePending">
      <view class="pending-toggle__box" :class="{ 'pending-toggle__box--on': onlyPending }">
        <text v-if="onlyPending" class="pending-toggle__check">✓</text>
      </view>
      <text class="pending-toggle__text">仅看待确认</text>
    </view>

    <view v-if="summaryVisible" class="summary">共 {{ total }} 条记录</view>

    <MbEmpty v-if="error" :text="error" />

    <MbEmpty v-else-if="!loading && records.length === 0" text="暂无记录" />

    <template v-else>
      <MbCard v-for="record in records" :key="record.recordId">
        <MbCell
          :title="recordTitle(record)"
          :label="recordLabel(record)"
          clickable
          arrow
          @click="goDetail(record)"
        >
          <template #extra>
            <view class="tags">
              <text
                v-for="tag in record.tags"
                :key="tag.text"
                class="tag"
                :class="`tag--${tag.level}`"
              >{{ tag.text }}</text>
            </view>
          </template>
        </MbCell>
        <view class="times">
          <text class="times__item">计划 {{ formatPlanTime(record) }}</text>
          <text class="times__item">实际 {{ formatActualTime(record) }}</text>
        </view>
      </MbCard>

      <view v-if="canLoadMore" class="more">
        <MbButton type="ghost" block :loading="loadingMore" @click="loadMore">加载更多</MbButton>
      </view>
    </template>

    <view v-if="loading && records.length === 0" class="loading-tip">加载中…</view>

    <MbTabBar />
  </view>
</template>

<script>
import MbButton from '@/components/MbButton.vue'
import MbCard from '@/components/MbCard.vue'
import MbCell from '@/components/MbCell.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import MbTabBar from '@/components/MbTabBar.vue'
import { listDevices } from '@/api/device.js'
import { listRecords } from '@/api/record.js'
import { CODE } from '@/api/request.js'
import {
  canWriteRecord,
  parseRecordsPage,
  recordStatusTags,
  recordTitle,
  recordMedicineText,
  formatPlanTime,
  formatActualTime,
} from '@/utils/record.js'
import { getRole, getUserId } from '@/store/user.js'

const PAGE_SIZE = 20

/** 列表 / 加载失败文案按 code 细分（请求已 silent，页面统一自绘）。 */
function errorText(err) {
  if (err && err.code === CODE.FORBIDDEN) return '无权访问或该老人不存在'
  if (err && err.code === CODE.GUARDIAN_PENDING) return '监护关系未生效，请等待老人确认'
  return (err && err.message) || '加载失败'
}

export default {
  name: 'RecordPage',
  components: { MbButton, MbCard, MbCell, MbEmpty, MbTabBar },
  data() {
    return {
      canWrite: false,
      myUserId: '',
      elders: [],
      elderId: '',
      fromDate: '',
      toDate: '',
      records: [],
      total: 0,
      page: 1,
      loading: false,
      loadingMore: false,
      error: '',
      loaded: false,
      onlyPending: false,
    }
  },
  computed: {
    summaryVisible() {
      return !this.error && this.total > 0
    },
    canLoadMore() {
      return this.loaded && !this.error && this.records.length < this.total
    },
  },
  onLoad() {
    this.canWrite = canWriteRecord(getRole())
    this.myUserId = getUserId()
    // 老人端只看自己；监护人端先归纳候选老人再加载
    this.elderId = this.canWrite ? '' : this.myUserId
    this.load()
  },
  onShow() {
    // 从详情页返回时刷新（补录 / 确认后状态可能已变）
    if (this.loaded) this.reload()
  },
  methods: {
    recordTitle,
    recordMedicineText,
    formatPlanTime,
    formatActualTime,
    recordLabel(record) {
      return recordMedicineText(record)
    },
    async reload() {
      this.page = 1
      this.records = []
      await this.load()
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
        await this.fetchPage(1)
        this.loaded = true
      } catch (err) {
        this.error = errorText(err)
      } finally {
        this.loading = false
      }
    },
    async loadElders() {
      try {
        const devices = await listDevices(undefined, { silent: true })
        const elders = []
        ;(devices || []).forEach((device) => {
          if (device.elderId && elders.indexOf(device.elderId) === -1) elders.push(device.elderId)
        })
        this.elders = elders
        if (!this.elderId && elders.length) this.elderId = elders[0]
      } catch (err) {
        // 拉不到设备不阻塞：老人端仍可按自身 elderId 加载
        this.elders = []
      }
    },
    async fetchPage(page) {
      const data = await listRecords(
        {
          elderId: this.elderId,
          from: this.fromDate || undefined,
          to: this.toDate || undefined,
          pendingConfirm: this.onlyPending ? true : undefined,
          page,
          size: PAGE_SIZE,
        },
        { silent: true },
      )
      const result = parseRecordsPage(data)
      const list = result.list.map((r) => ({ ...r, tags: recordStatusTags(r) }))
      if (page === 1) {
        this.records = list
      } else {
        this.records = this.records.concat(list)
      }
      this.total = result.total
      this.page = page
    },
    async loadMore() {
      if (this.loadingMore) return
      this.loadingMore = true
      try {
        await this.fetchPage(this.page + 1)
      } catch (err) {
        uni.showToast({ title: errorText(err), icon: 'none' })
      } finally {
        this.loadingMore = false
      }
    },
    selectElder(elder) {
      if (elder === this.elderId) return
      this.elderId = elder
      this.reload()
    },
    onFromChange(e) {
      this.fromDate = (e && e.detail && e.detail.value) || ''
      this.reload()
    },
    onToChange(e) {
      this.toDate = (e && e.detail && e.detail.value) || ''
      this.reload()
    },
    resetRange() {
      this.fromDate = ''
      this.toDate = ''
      this.reload()
    },
    togglePending() {
      this.onlyPending = !this.onlyPending
      this.reload()
    },
    goDetail(record) {
      uni.navigateTo({
        url: `/pages/record/detail?recordId=${encodeURIComponent(record.recordId)}` +
          `&elderId=${encodeURIComponent(this.elderId)}`,
      })
    },
    // 跳转到依从性报告页（feat/mp-report），携带当前选中的老人，便于跨页续接上下文
    goReport() {
      uni.navigateTo({
        url: `/pages/report/report?elderId=${encodeURIComponent(this.elderId || this.myUserId)}`,
      })
    },
  },
}
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx calc(110rpx + env(safe-area-inset-bottom) + 24rpx);
  box-sizing: border-box;
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

  // 头部右侧「依从性报告 ›」入口（feat/mp-report），全角色可见
  &__action {
    flex-shrink: 0;
    padding: 10rpx 20rpx;
    border-radius: 999rpx;
    background: rgba(14, 159, 142, 0.1);

    &--hover {
      background: rgba(14, 159, 142, 0.18);
    }
  }

  &__action-text {
    font-size: 26rpx;
    font-weight: 500;
    color: $mb-primary;
  }
}

.elders {
  white-space: nowrap;
  margin-bottom: 16rpx;

  &__item {
    display: inline-flex;
    align-items: center;
    height: 56rpx;
    padding: 0 24rpx;
    margin-right: 16rpx;
    border-radius: $mb-radius-pill;
    background: $mb-card-bg;
    border: 2rpx solid $mb-border;

    &--on {
      background: $mb-primary-bg;
      border-color: $mb-primary;
    }
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
  margin-bottom: 16rpx;
  font-size: 26rpx;
  color: $mb-text-3;
}

.filter {
  display: flex;
  align-items: center;
  margin-bottom: 16rpx;

  &__picker {
    flex: 1;
  }

  &__field {
    display: flex;
    align-items: center;
    height: 72rpx;
    padding: 0 20rpx;
    border-radius: $mb-radius-sm;
    background: $mb-card-bg;
    border: 2rpx solid $mb-border;
  }

  &__label {
    font-size: 26rpx;
    color: $mb-text-3;
    margin-right: 12rpx;
  }

  &__value {
    font-size: 28rpx;
    color: $mb-text;

    &--ph {
      color: $mb-placeholder;
    }
  }

  &__sep {
    margin: 0 16rpx;
    font-size: 26rpx;
    color: $mb-text-3;
  }

  &__reset {
    margin-left: 20rpx;
    padding: 0 20rpx;
    height: 72rpx;
    display: flex;
    align-items: center;

    &--hover {
      opacity: 0.7;
    }

    &-text {
      font-size: 26rpx;
      color: $mb-primary;
    }
  }
}

.pending-toggle {
  display: flex;
  align-items: center;
  margin-bottom: 16rpx;

  &__box {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 36rpx;
    height: 36rpx;
    margin-right: 12rpx;
    border-radius: 8rpx;
    border: 2rpx solid $mb-border;
    background: $mb-card-bg;

    &--on {
      background: $mb-primary;
      border-color: $mb-primary;
    }
  }

  &__check {
    font-size: 24rpx;
    color: #fff;
    line-height: 1;
  }

  &__text {
    font-size: 26rpx;
    color: $mb-text-2;
  }

  &--hover {
    opacity: 0.7;
  }
}

.summary {
  margin-bottom: 16rpx;
  font-size: 26rpx;
  color: $mb-text-3;
}

.tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.tag {
  margin-left: 12rpx;
  padding: 4rpx 16rpx;
  border-radius: $mb-radius-pill;
  font-size: 22rpx;
  line-height: 1.6;

  &--ok {
    color: $mb-primary;
    background: $mb-primary-bg;
  }

  &--warn {
    color: #b7791f;
    background: #fdf3e0;
  }

  &--danger {
    color: #fff;
    background: $mb-danger;
  }

  &--info {
    color: $mb-text-2;
    background: $mb-bg;
  }

  &--pending {
    color: #9a3412;
    background: #fed7aa;
  }
}

.times {
  display: flex;
  flex-direction: column;
  margin-top: 12rpx;
  padding-top: 16rpx;
  border-top: 2rpx solid $mb-border;

  &__item {
    font-size: 24rpx;
    color: $mb-text-3;
    line-height: 1.7;
  }
}

.more {
  margin-top: 8rpx;
}

.loading-tip {
  padding: 40rpx 0;
  text-align: center;
  font-size: 26rpx;
  color: $mb-text-3;
}
</style>
