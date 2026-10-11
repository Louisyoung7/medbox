<template>
  <view class="ws-bar" :class="`ws-bar--${tone}`">
    <view class="ws-bar__row">
      <view class="ws-bar__dot"></view>
      <text class="ws-bar__state">{{ stateText }}</text>
      <text class="ws-bar__topics">{{ topicsText }}</text>
      <text v-if="!connected" class="ws-bar__action" @click="onReconnect">重连</text>
    </view>
    <text v-if="eventText" class="ws-bar__event">{{ eventText }}</text>
    <text v-else class="ws-bar__event ws-bar__event--empty">暂无实时事件</text>
  </view>
</template>

<script>
/**
 * 实时连接状态条（`feat/mp-ws`）：首页顶部的**联调入口**。
 *
 * <p>显示连接状态 + 最近一条事件摘要 + 手动重连；后端用调试端点推一帧即可在这里看到，
 * 断网 / 后端重启也能直接观察"重连中 → 已连接"的变化。
 *
 * <p>状态与事件都向 `src/store/ws.js` 订阅（**不自己建连接**），`beforeUnmount` 退订；
 * 后续 `mp-alarm` / `mp-device` 复用同一套订阅即可，不必再改本组件。
 */
import { WS_EVENT, WS_STATE, connect, getLastEvent, getState, onEvent, onStateChange, reconnectNow } from '@/store/ws.js'
import { describeFrame } from '@/utils/ws-frame.js'
import { formatRelative } from '@/utils/datetime.js'

const STATE_TEXT = {
  [WS_STATE.IDLE]: '未连接',
  [WS_STATE.CONNECTING]: '连接中',
  [WS_STATE.OPEN]: '实时推送已连接',
  [WS_STATE.RECONNECTING]: '重连中',
  [WS_STATE.CLOSED]: '已停止',
}

/** 订阅的事件类型（PONG 不算：它是心跳控制帧，展示出来只会刷屏）。 */
const WATCHED_TYPES = [WS_EVENT.ALARM, WS_EVENT.DEVICE_STATUS, WS_EVENT.REMINDER, WS_EVENT.RESYNC]

export default {
  name: 'WsStatusBar',
  data() {
    return {
      state: getState(),
      eventText: '',
      eventAt: '',
    }
  },
  computed: {
    connected() {
      return this.state === WS_STATE.OPEN
    },
    stateText() {
      return STATE_TEXT[this.state] || this.state
    },
    /** 绿（已连接）/ 橙（连接中、重连中）/ 灰（未连接、已停止）。 */
    tone() {
      if (this.state === WS_STATE.OPEN) return 'ok'
      if (this.state === WS_STATE.CONNECTING || this.state === WS_STATE.RECONNECTING) return 'busy'
      return 'idle'
    },
    topicsText() {
      return this.connected ? '' : '点击重连可立即尝试'
    },
  },
  mounted() {
    const initial = getLastEvent()
    if (initial) {
      this.applyFrame(initial)
    }
    this.offState = onStateChange((state) => {
      this.state = state
    })
    this.offEvents = WATCHED_TYPES.map((type) =>
      onEvent(type, (frame) => {
        this.applyFrame(frame)
      }),
    )
    // 页面进前台补一次连接（幂等）：登录后落地首页时这里才第一次起连
    connect()
  },
  beforeUnmount() {
    if (this.offState) this.offState()
    if (this.offEvents) this.offEvents.forEach((off) => off())
  },
  methods: {
    applyFrame(frame) {
      const text = describeFrame(frame)
      if (!text) return
      this.eventText = text
      this.eventAt = frame && frame.ts ? formatRelative(frame.ts) : ''
      if (this.eventAt) {
        this.eventText = `${text} · ${this.eventAt}`
      }
    },
    onReconnect() {
      reconnectNow()
    },
  },
}
</script>

<style lang="scss" scoped>
.ws-bar {
  padding: 16rpx 24rpx;
  margin-bottom: 16rpx;
  border-radius: $mb-radius;
  background: $mb-card-bg;
  box-shadow: $mb-shadow;

  &__row {
    display: flex;
    align-items: center;
  }

  &__dot {
    width: 16rpx;
    height: 16rpx;
    margin-right: 12rpx;
    border-radius: 50%;
    background: $mb-text-4;
  }

  &__state {
    font-size: 26rpx;
    font-weight: 600;
    color: $mb-text-2;
  }

  &__topics {
    margin-left: 12rpx;
    font-size: 22rpx;
    color: $mb-text-4;
  }

  &__action {
    margin-left: auto;
    padding: 4rpx 20rpx;
    border-radius: $mb-radius-pill;
    background: $mb-primary-bg;
    color: $mb-primary;
    font-size: 24rpx;
  }

  &__event {
    display: block;
    margin-top: 8rpx;
    padding-left: 28rpx;
    font-size: 24rpx;
    color: $mb-text-3;
    line-height: 1.5;

    &--empty {
      color: $mb-text-4;
    }
  }

  &--ok &__dot {
    background: $mb-success;
  }

  &--busy &__dot {
    background: $mb-warning;
  }
}
</style>
