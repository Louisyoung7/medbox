<template>
  <view class="page">
    <!-- 监护人：选择老人（elderId 由 GET /devices 推导；老人端默认自己） -->
    <view v-if="isGuardian" class="picker-row">
      <text class="picker-label">老人</text>
      <picker class="picker" mode="selector" :range="elderOptions" range-key="label" @change="onElderChange">
        <view class="picker__value">{{ currentElderLabel }}</view>
      </picker>
    </view>

    <!-- 筛选：类型 + 已读/未读 -->
    <view class="filters">
      <picker class="filter-type" mode="selector" :range="typeOptions" range-key="label" @change="onTypeChange">
        <view class="filter-type__value">{{ currentTypeLabel }}</view>
      </picker>
      <view class="seg">
        <view
          v-for="opt in readStateOptions"
          :key="opt.value"
          class="seg__item"
          :class="{ 'seg__item--active': readState === opt.value }"
          @click="onReadStateChange(opt.value)"
        >
          <text>{{ opt.label }}</text>
        </view>
      </view>
    </view>

    <MbLoading v-if="loading && page === 1" text="加载中" />
    <MbEmpty v-else-if="error" :text="error" />
    <template v-else>
      <view v-if="alarms.length" class="alarm-list">
        <view
          v-for="a in alarms"
          :key="a.alarmId"
          class="alarm-card"
          :class="{ 'alarm-card--unread': a.isUnread }"
          @click="goDetail(a)"
        >
          <view class="alarm-card__bar" :class="a.levelClass" />
          <view class="alarm-card__body">
            <view class="alarm-card__head">
              <text class="alarm-card__type" :class="a.typeClass">{{ a.typeLabel }}</text>
              <text class="alarm-card__level" :class="a.levelClass">{{ a.levelLabel }}</text>
              <text v-if="a.isUnread" class="alarm-card__unread">未读</text>
              <text v-if="a.handled" class="alarm-card__handled">已处理</text>
            </view>
            <text class="alarm-card__msg">{{ a.message }}</text>
            <text class="alarm-card__time">{{ formatDateTime(a.createdAt) }}</text>
          </view>
        </view>
      </view>
      <MbEmpty v-else text="暂无告警" />
      <MbLoading v-if="loading && page > 1" text="加载中" />
      <text v-if="finished && alarms.length" class="list-end">没有更多了</text>
    </template>
  </view>
</template>

<script>
import MbLoading from '@/components/MbLoading.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import { getAlarms, getUnreadCount } from '@/api/alarm.js'
import { CODE } from '@/api/request.js'
import {
  ALARM_TYPE_OPTIONS,
  READ_STATE_OPTIONS,
  parseAlarmsPage,
  toAlarmView,
} from '@/utils/alarm.js'
import { formatDateTime } from '@/utils/datetime.js'
import { getUserInfo } from '@/store/user.js'
import {
  getAlarmState,
  setCurrentElderId,
  refreshUnreadCount,
  loadVisibleElderIds,
} from '@/store/alarm.js'

export default {
  name: 'AlarmListPage',
  components: { MbLoading, MbEmpty },
  data() {
    return {
      isGuardian: false,
      elderId: '',
      alarms: [],
      type: 'ALL',
      readState: 'ALL',
      typeOptions: ALARM_TYPE_OPTIONS,
      readStateOptions: READ_STATE_OPTIONS,
      page: 1,
      size: 20,
      loading: false,
      finished: false,
      error: '',
      pulling: false,
    }
  },
  computed: {
    elderOptions() {
      const ids = getAlarmState().elderIds || []
      return ids.map((id) => ({ value: id, label: `老人 ${id}` }))
    },
    currentElderLabel() {
      const id = getAlarmState().currentElderId || this.elderId
      return id ? `老人 ${id}` : '请选择老人'
    },
    currentTypeLabel() {
      const opt = this.typeOptions.find((o) => o.value === this.type)
      return opt ? opt.label : '全部类型'
    },
  },
  onLoad() {
    const info = getUserInfo()
    this.isGuardian = !!(info && info.role === 'GUARDIAN')
    if (this.isGuardian) {
      // 复用 App 已推导的可见老人；为空则补一次
      const ids = getAlarmState().elderIds
      if (!ids || !ids.length) {
        loadVisibleElderIds().then(() => {
          this.elderId = getAlarmState().currentElderId || ''
          this.reload()
        })
      } else {
        this.elderId = getAlarmState().currentElderId || ids[0]
        this.reload()
      }
    } else {
      this.elderId = (info && info.userId) || ''
      this.reload()
    }
  },
  onReachBottom() {
    if (!this.finished && !this.loading) this.loadMore()
  },
  onPullDownRefresh() {
    this.pulling = true
    this.reload()
  },
  methods: {
    formatDateTime,
    async reload() {
      this.page = 1
      this.alarms = []
      this.finished = false
      this.error = ''
      await this.fetch()
      if (this.pulling) {
        uni.stopPullDownRefresh()
        this.pulling = false
      }
    },
    async loadMore() {
      await this.fetch()
    },
    async fetch() {
      if (this.loading) return
      this.loading = true
      try {
        const data = await getAlarms({
          elderId: this.elderId,
          type: this.type,
          readState: this.readState,
          page: this.page,
          size: this.size,
        })
        const { list, total } = parseAlarmsPage(data)
        const views = list.map(toAlarmView)
        this.alarms = this.page === 1 ? views : this.alarms.concat(views)
        this.finished = this.alarms.length >= total
        this.page += 1
      } catch (err) {
        this.error =
          err && err.code === CODE.FORBIDDEN
            ? '无权访问或老人不存在'
            : (err && err.message) || '加载失败'
      } finally {
        this.loading = false
      }
    },
    onElderChange(e) {
      const id = this.elderOptions[Number(e.detail.value)].value
      this.elderId = id
      setCurrentElderId(id)
      this.reload()
    },
    onTypeChange(e) {
      this.type = this.typeOptions[Number(e.detail.value)].value
      this.reload()
    },
    onReadStateChange(value) {
      if (this.readState === value) return
      this.readState = value
      this.reload()
    },
    /** 进入详情：把已归一化的告警对象整体带过去（后端无 GET /alarms/{id}，详情复用列表项）。 */
    goDetail(alarm) {
      uni.navigateTo({
        url: `/pages/alarm/detail?alarm=${encodeURIComponent(JSON.stringify(alarm))}`,
      })
    },
  },
}
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 48rpx;
  box-sizing: border-box;
  background: $mb-bg;
}

.picker-row {
  display: flex;
  align-items: center;
  padding: 16rpx 24rpx;
  margin-bottom: 16rpx;
  border-radius: $mb-radius;
  background: #fff;
}

.picker-label {
  font-size: 26rpx;
  color: $mb-text-3;
  margin-right: 16rpx;
}

.picker {
  flex: 1;

  &__value {
    font-size: 28rpx;
    font-weight: 600;
    color: $mb-text-2;
  }
}

.filters {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16rpx;
}

.filter-type {
  &__value {
    padding: 12rpx 24rpx;
    border-radius: $mb-radius;
    background: #fff;
    font-size: 26rpx;
    color: $mb-text-2;
  }
}

.seg {
  display: flex;
  background: #fff;
  border-radius: $mb-radius;
  overflow: hidden;

  &__item {
    padding: 12rpx 24rpx;
    font-size: 26rpx;
    color: $mb-text-3;

    &--active {
      background: $mb-primary;
      color: #fff;
    }
  }
}

.alarm-list {
  display: flex;
  flex-direction: column;
}

.alarm-card {
  display: flex;
  margin-bottom: 16rpx;
  border-radius: $mb-radius;
  background: #fff;
  overflow: hidden;

  &--unread {
    background: #fff;

    .alarm-card__type {
      font-weight: 700;
    }
  }

  &__bar {
    flex: 0 0 8rpx;
    background: $mb-text-4;

    &.alarm-level--critical {
      background: $mb-danger;
    }

    &.alarm-level--warn {
      background: #fb8c00;
    }

    &.alarm-level--info {
      background: $mb-primary;
    }
  }

  &__body {
    flex: 1;
    padding: 24rpx;
  }

  &__head {
    display: flex;
    align-items: center;
    margin-bottom: 10rpx;
  }

  &__type {
    font-size: 28rpx;
    font-weight: 600;
    color: $mb-text-2;

    &.alarm-type--critical {
      color: $mb-danger;
    }

    &.alarm-type--warn {
      color: #fb8c00;
    }

    &.alarm-type--info {
      color: $mb-primary;
    }
  }

  &__level {
    margin-left: 12rpx;
    font-size: 22rpx;

    &.alarm-level--critical {
      color: $mb-danger;
    }

    &.alarm-level--warn {
      color: #fb8c00;
    }

    &.alarm-level--info {
      color: $mb-primary;
    }
  }

  &__unread {
    margin-left: auto;
    padding: 2rpx 14rpx;
    border-radius: $mb-radius-pill;
    background: $mb-danger;
    color: #fff;
    font-size: 20rpx;
  }

  &__handled {
    margin-left: 12rpx;
    padding: 2rpx 14rpx;
    border-radius: $mb-radius-pill;
    background: $mb-success;
    color: #fff;
    font-size: 20rpx;
  }

  &__msg {
    display: block;
    font-size: 26rpx;
    line-height: 1.6;
    color: $mb-text-2;
    margin-bottom: 8rpx;
  }

  &__time {
    font-size: 22rpx;
    color: $mb-text-4;
  }
}

.list-end {
  display: block;
  text-align: center;
  font-size: 22rpx;
  color: $mb-text-4;
  padding: 24rpx 0;
}
</style>
