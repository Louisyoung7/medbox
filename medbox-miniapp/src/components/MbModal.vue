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
        <button
          class="mb-modal__btn"
          :class="[
            confirmType === 'danger' ? 'mb-modal__btn--danger' : 'mb-modal__btn--confirm',
            { 'mb-modal__btn--disabled': confirmDisabled || confirmLoading },
          ]"
          :disabled="confirmDisabled || confirmLoading"
          hover-class="mb-modal__btn--hover"
          @click="onConfirm"
        >
          <view v-if="confirmLoading" class="mb-modal__spinner"></view>
          {{ confirmText }}
        </button>
      </view>
    </view>
  </view>
</template>

<script>
/**
 * 弹窗（`feat/mp-ui-kit`，`feat/mp-user-delete` 扩按钮状态）：`v-model` 控制显隐（modelValue），
 * 点击遮罩是否关闭由 `maskClosable` 决定。点击确认 emit `confirm`，取消 emit `cancel`。
 *
 * <p>**默认 behaviors 不变**：确认 / 取消都会自动关闭。做**危险操作确认**（如注销账号）时传
 * `closeOnConfirm=false` 由页面决定何时关 —— 提交中要留着展示 loading，出错时要留着改密码，
 * 若这里自作主张先关，用户就得重新输一遍。
 *
 * <p>`confirmType="danger"` 把确认键换成红色实底（不可逆操作），`confirmDisabled` 用于前置条件
 * 不满足（如密码没填），`confirmLoading` 提交中禁用双键并转圈。
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
    confirmType: { type: String, default: 'primary' },
    confirmDisabled: { type: Boolean, default: false },
    confirmLoading: { type: Boolean, default: false },
    closeOnConfirm: { type: Boolean, default: true },
  },
  methods: {
    close() {
      this.$emit('update:modelValue', false)
    },
    onMask() {
      // 提交中不让点遮罩关：请求还在飞，关了会让页面丢失"关还是不关"的判断依据
      if (this.maskClosable && !this.confirmLoading) this.close()
    },
    onConfirm() {
      if (this.confirmDisabled || this.confirmLoading) return
      this.$emit('confirm')
      if (this.closeOnConfirm) this.close()
    },
    onCancel() {
      if (this.confirmLoading) return
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

    &--danger {
      color: #fff;
      background: linear-gradient(135deg, #f0666a 0%, $mb-danger 100%);
      box-shadow: 0 8rpx 20rpx rgba(229, 72, 77, 0.28);
    }

    &--disabled {
      opacity: 0.5;
    }

    &--hover {
      opacity: 0.9;
    }

    &__spinner {
      width: 28rpx;
      height: 28rpx;
      margin-right: 12rpx;
      border: 4rpx solid rgba(255, 255, 255, 0.4);
      border-top-color: #fff;
      border-radius: 50%;
      animation: mb-modal-spin 0.7s linear infinite;
    }
  }
}

@keyframes mb-modal-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
