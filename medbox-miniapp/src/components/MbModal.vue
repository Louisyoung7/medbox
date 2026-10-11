<template>
  <view v-if="modelValue" class="mb-modal">
    <view class="mb-modal__mask" @click="onMask"></view>
    <view class="mb-modal__panel">
      <text v-if="title" class="mb-modal__title">{{ title }}</text>
      <view class="mb-modal__body"><slot /></view>
      <view class="mb-modal__actions">
        <button
          v-if="showCancel"
          class="mb-modal__btn mb-modal__btn--cancel"
          hover-class="mb-modal__btn--hover"
          @click="onCancel"
        >
          {{ cancelText }}
        </button>
        <button class="mb-modal__btn mb-modal__btn--confirm" hover-class="mb-modal__btn--hover" @click="onConfirm">
          {{ confirmText }}
        </button>
      </view>
    </view>
  </view>
</template>

<script>
/**
 * 弹窗（`feat/mp-ui-kit`）：`v-model` 控制显隐（modelValue），点击遮罩是否关闭由 `maskClosable` 决定。
 * 点击确认 emit `confirm` 并关闭，取消 emit `cancel` 并关闭。
 */
export default {
  name: 'MbModal',
  props: {
    modelValue: { type: Boolean, default: false },
    title: { type: String, default: '' },
    confirmText: { type: String, default: '确定' },
    cancelText: { type: String, default: '取消' },
    showCancel: { type: Boolean, default: true },
    maskClosable: { type: Boolean, default: true },
  },
  methods: {
    close() {
      this.$emit('update:modelValue', false)
    },
    onMask() {
      if (this.maskClosable) this.close()
    },
    onConfirm() {
      this.$emit('confirm')
      this.close()
    },
    onCancel() {
      this.$emit('cancel')
      this.close()
    },
  },
}
</script>

<style lang="scss" scoped>
.mb-modal {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;

  &__mask {
    position: absolute;
    inset: 0;
    background: $mb-mask;
  }

  &__panel {
    position: relative;
    width: 560rpx;
    max-width: 80vw;
    background: $mb-card-bg;
    border-radius: $mb-radius-lg;
    padding: 40rpx 32rpx 28rpx;
    box-shadow: 0 16rpx 48rpx rgba(0, 0, 0, 0.18);
  }

  &__title {
    display: block;
    text-align: center;
    font-size: 34rpx;
    font-weight: 600;
    color: $mb-text;
    margin-bottom: 24rpx;
  }

  &__body {
    font-size: 28rpx;
    color: $mb-text-2;
    line-height: 1.6;
  }

  &__actions {
    display: flex;
    margin-top: 36rpx;
  }

  &__btn {
    flex: 1;
    height: 84rpx;
    line-height: 84rpx;
    margin: 0 8rpx;
    padding: 0;
    border-radius: $mb-radius-pill;
    font-size: 30rpx;
    border: none;

    &--confirm {
      color: #fff;
      background: $mb-gradient;
    }

    &--cancel {
      color: $mb-primary;
      background: $mb-primary-bg;
    }

    &--hover {
      opacity: 0.9;
    }
  }
}
</style>
