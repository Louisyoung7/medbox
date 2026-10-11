<template>
  <view v-if="show" class="mb-loading" :class="{ 'mb-loading--full': fullscreen }">
    <view class="mb-loading__spinner"></view>
    <text v-if="text" class="mb-loading__text">{{ text }}</text>
  </view>
</template>

<script>
/**
 * 加载态（`feat/mp-ui-kit`）：内联 spinner；`fullscreen` 时铺半透明遮罩居中，
 * 用于首屏 / 请求阻塞。`show` 受控（默认 true）。
 */
export default {
  name: 'MbLoading',
  props: {
    show: { type: Boolean, default: true },
    fullscreen: { type: Boolean, default: false },
    text: { type: String, default: '' },
  },
}
</script>

<style lang="scss" scoped>
.mb-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40rpx;

  &--full {
    position: fixed;
    inset: 0;
    background: $mb-mask;
    z-index: 999;
  }

  &__spinner {
    width: 56rpx;
    height: 56rpx;
    border: 6rpx solid rgba(255, 255, 255, 0.35);
    border-top-color: #ffffff;
    border-radius: 50%;
    animation: mb-spin 0.8s linear infinite;
  }

  &--full &__spinner {
    border-color: rgba(14, 159, 142, 0.25);
    border-top-color: $mb-primary;
  }

  &__text {
    margin-top: 20rpx;
    font-size: 26rpx;
    color: #ffffff;
  }

  &--full &__text {
    color: $mb-text-2;
  }
}

@keyframes mb-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
