<template>
  <button
    class="mb-btn"
    :class="[
      `mb-btn--${type}`,
      {
        'mb-btn--block': block,
        'mb-btn--loading': loading,
        'mb-btn--disabled': disabled,
      },
    ]"
    :disabled="disabled || loading"
    hover-class="mb-btn--hover"
    @click="onClick"
  >
    <view v-if="loading" class="mb-btn__spinner"></view>
    <slot />
  </button>
</template>

<script>
/**
 * 按钮（`feat/mp-ui-kit`）：`type` = primary(主色渐变) / ghost(主色浅底) / **danger(危险操作实底**，
 * `feat/mp-user-delete` 起可用，注销一类不可逆操作用**)）；`block` 撑满；
 * `loading` 显示 spinner 并禁用；`disabled` 禁用。点击 emit `click`（loading/disabled 时不触发）。
 */
export default {
  name: 'MbButton',
  // 必须声明：Vue 3 未声明的 @click 会透传到根节点变成原生监听，
  // 与 onClick 里的 $emit('click') 叠加 → 一次点击触发两次
  emits: ['click'],
  props: {
    type: { type: String, default: 'primary' },
    block: { type: Boolean, default: false },
    loading: { type: Boolean, default: false },
    disabled: { type: Boolean, default: false },
  },
  methods: {
    onClick() {
      if (this.disabled || this.loading) return
      this.$emit('click')
    },
  },
}
</script>

<style lang="scss" scoped>
.mb-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 88rpx;
  padding: 0 40rpx;
  border: none;
  border-radius: $mb-radius-pill;
  font-size: 30rpx;
  line-height: 1;
  transition: transform 0.12s ease, opacity 0.12s ease;

  &--primary {
    color: #fff;
    background: $mb-gradient;
    box-shadow: 0 8rpx 20rpx rgba(14, 159, 142, 0.28);
  }

  &--ghost {
    color: $mb-primary;
    background: $mb-primary-bg;
  }

  &--danger {
    color: #fff;
    background: linear-gradient(135deg, #f0666a 0%, $mb-danger 100%);
    box-shadow: 0 8rpx 20rpx rgba(229, 72, 77, 0.28);
  }

  &--block {
    display: flex;
    width: 100%;
  }

  &--hover {
    transform: scale(0.98);
    opacity: 0.94;
  }

  &--disabled {
    opacity: 0.5;
  }

  &__spinner {
    width: 30rpx;
    height: 30rpx;
    margin-right: 14rpx;
    border: 4rpx solid rgba(255, 255, 255, 0.4);
    border-top-color: #fff;
    border-radius: 50%;
    animation: mb-btn-spin 0.7s linear infinite;
  }
}

@keyframes mb-btn-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
