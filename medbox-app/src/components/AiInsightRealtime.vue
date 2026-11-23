<template>
  <!-- 周期洞察（AI_INSIGHT 帧）：后端定时生成后推送，内容不落库，仅展示 -->
  <view v-if="visible" class="popup">
    <view class="popup__mask" @click="close" />
    <view class="popup__card">
      <view class="popup__head">
        <text class="popup__title">AI 健康洞察</text>
        <text class="popup__close" @click="close">×</text>
      </view>
      <scroll-view scroll-y class="popup__body">
        <text class="popup__msg">{{ content }}</text>
        <view v-if="citations.length" class="citations">
          <text class="citations__title">数据出处（仅供参考，非结论）</text>
          <text v-for="(c, i) in citations" :key="i" class="citations__item">{{ c }}</text>
        </view>
        <text class="disclaimer">{{ disclaimer }}</text>
      </scroll-view>
      <view class="popup__actions">
        <view class="popup__btn" @click="close">
          <text>知道了</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import { WS_EVENT, onEvent } from '@/store/ws.js'
import { parseAiInsightPayload } from '@/utils/ai.js'

export default {
  name: 'AiInsightRealtime',
  data() {
    return {
      visible: false,
      content: '',
      citations: [],
      disclaimer: '',
      offInsight: null,
    }
  },
  mounted() {
    // 唯一长连接只在 src/store/ws.js；此处只订阅，不自己建 socket
    this.offInsight = onEvent(WS_EVENT.AI_INSIGHT, (frame) => this.onInsight(frame))
  },
  beforeUnmount() {
    if (this.offInsight) this.offInsight()
  },
  methods: {
    onInsight(frame) {
      const data = parseAiInsightPayload(frame && frame.payload)
      if (!data.content) return
      this.content = data.content
      this.citations = data.citations
      this.disclaimer = data.disclaimer
      this.visible = true
    },
    close() {
      this.visible = false
    },
  },
}
</script>

<style lang="scss" scoped>
.popup {
  position: fixed;
  inset: 0;
  z-index: 9998;
  display: flex;
  align-items: center;
  justify-content: center;

  &__mask {
    position: absolute;
    inset: 0;
    background: rgba(0, 0, 0, 0.45);
  }

  &__card {
    position: relative;
    width: 620rpx;
    max-height: 78vh;
    padding: 32rpx;
    box-sizing: border-box;
    background: #fff;
    border-radius: 24rpx;
    border-left: 8rpx solid #1a73e8;
    box-shadow: 0 12rpx 40rpx rgba(0, 0, 0, 0.18);
    display: flex;
    flex-direction: column;
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 16rpx;
  }

  &__title {
    font-size: 32rpx;
    font-weight: 600;
    color: #1f2329;
  }

  &__close {
    font-size: 40rpx;
    line-height: 1;
    color: #6b7280;
    padding: 0 8rpx;
  }

  &__body {
    max-height: 52vh;
  }

  &__msg {
    display: block;
    font-size: 28rpx;
    line-height: 1.7;
    color: #1f2329;
    white-space: pre-wrap;
  }

  &__actions {
    display: flex;
    justify-content: flex-end;
    margin-top: 24rpx;
  }

  &__btn {
    min-width: 160rpx;
    height: 72rpx;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 36rpx;
    font-size: 28rpx;
    background: #1a73e8;
    color: #fff;
  }
}

.citations {
  margin-top: 20rpx;

  &__title {
    display: block;
    font-size: 24rpx;
    color: #6b7280;
    margin-bottom: 8rpx;
  }

  &__item {
    display: block;
    font-size: 22rpx;
    color: #8a9099;
    line-height: 1.6;
  }
}

.disclaimer {
  display: block;
  margin-top: 20rpx;
  font-size: 24rpx;
  line-height: 1.6;
  color: #8a9099;
}
</style>
