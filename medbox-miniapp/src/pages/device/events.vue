<template>
  <view class="page">
    <MbLoading v-if="loading && page === 1" text="加载中" />
    <MbEmpty v-else-if="error" :text="error" />
    <template v-else>
      <view v-if="events.length" class="event-list">
        <view v-for="ev in events" :key="ev.eventId" class="event-row">
          <view class="event-main">
            <text class="event-type" :class="eventClass(ev)">{{ ev.eventTypeLabel }}</text>
            <text class="event-detail">{{ eventDetailText(ev) }}</text>
          </view>
          <text class="event-time">{{ formatDateTime(ev.createdAt) }}</text>
        </view>
      </view>
      <MbEmpty v-else text="暂无设备事件" />
      <MbLoading v-if="loading && page > 1" text="加载中" />
      <text v-if="finished && events.length" class="event-end">没有更多了</text>
    </template>
  </view>
</template>

<script>
import MbLoading from '@/components/MbLoading.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import { getDeviceEvents } from '@/api/device.js'
import { CODE } from '@/api/request.js'
import { parseEventsPage, toEventView } from '@/utils/device.js'
import { formatDateTime } from '@/utils/datetime.js'

export default {
  name: 'DeviceEventsPage',
  components: { MbLoading, MbEmpty },
  data() {
    return {
      deviceId: '',
      events: [],
      page: 1,
      size: 20,
      loading: false,
      finished: false,
      error: '',
      pulling: false,
    }
  },
  onLoad(options) {
    this.deviceId = (options && options.deviceId) || ''
    this.reload()
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
      this.events = []
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
        const data = await getDeviceEvents(this.deviceId, { page: this.page, size: this.size })
        const { list, total } = parseEventsPage(data)
        const views = list.map(toEventView)
        this.events = this.page === 1 ? views : this.events.concat(views)
        this.finished = this.events.length >= total
        this.page += 1
      } catch (err) {
        this.error =
          err && err.code === CODE.FORBIDDEN
            ? '无权访问或设备不存在'
            : (err && err.message) || '加载失败'
      } finally {
        this.loading = false
      }
    },
    eventDetailText(ev) {
      if (ev.isFault) return ev.faultLabel
      if (ev.isCommand) return `${ev.cmdLabel} · ${ev.commandStateLabel}`
      return ''
    },
    eventClass(ev) {
      if (ev.isFault) return 'event-type--fault'
      if (ev.isCommand) return 'event-type--command'
      if (ev.isOffline) return 'event-type--off'
      return 'event-type--on'
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

.event-list {
  display: flex;
  flex-direction: column;
}

.event-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24rpx;
  margin-bottom: 16rpx;
  border-radius: $mb-radius;
  background: #fff;
}

.event-main {
  display: flex;
  flex-direction: column;
  flex: 1;
  padding-right: 16rpx;
}

.event-type {
  font-size: 28rpx;
  font-weight: 600;
  margin-bottom: 6rpx;

  &--on {
    color: $mb-success;
  }

  &--off {
    color: $mb-text-3;
  }

  &--fault {
    color: $mb-danger;
  }

  &--command {
    color: $mb-primary;
  }
}

.event-detail {
  font-size: 24rpx;
  color: $mb-text-3;
}

.event-time {
  flex: 0 0 auto;
  font-size: 22rpx;
  color: $mb-text-4;
}

.event-end {
  display: block;
  text-align: center;
  font-size: 22rpx;
  color: $mb-text-4;
  padding: 24rpx 0;
}
</style>
