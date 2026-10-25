<template>
  <view
    class="mb-cell"
    :class="{ 'mb-cell--clickable': clickable }"
    hover-class="mb-cell--hover"
    @click="onClick"
  >
    <view v-if="$slots.icon" class="mb-cell__icon"><slot name="icon" /></view>
    <view class="mb-cell__body">
      <text class="mb-cell__title">{{ title }}</text>
      <text v-if="label" class="mb-cell__label">{{ label }}</text>
    </view>
    <view class="mb-cell__extra">
      <slot name="extra" />
      <text v-if="value" class="mb-cell__value">{{ value }}</text>
    </view>
    <view v-if="arrow" class="mb-cell__arrow"></view>
  </view>
</template>

<script>
/**
 * 列表行（`feat/mp-ui-kit`）：左图标(slot) / 标题 / 右值或描述 / 箭头。
 * `clickable` 时整行可点并带 hover 反馈，点击 emit `click`。
 */
export default {
  name: 'MbCell',
  // 必须声明：Vue 3 未声明的 @click 会透传到根节点变成原生监听，
  // 与 onClick 里的 $emit('click') 叠加 → 一次点击触发两次（devtools 发模拟上报时落了双份记录）
  emits: ['click'],
  props: {
    title: { type: String, default: '' },
    value: { type: String, default: '' },
    label: { type: String, default: '' },
    arrow: { type: Boolean, default: false },
    clickable: { type: Boolean, default: false },
  },
  methods: {
    onClick() {
      if (this.clickable) this.$emit('click')
    },
  },
}
</script>

<style lang="scss" scoped>
.mb-cell {
  display: flex;
  align-items: center;
  min-height: 104rpx;
  padding: 20rpx 4rpx;
  border-bottom: 2rpx solid $mb-border;

  &:last-child {
    border-bottom: none;
  }

  &--clickable {
    cursor: pointer;
  }

  &--hover {
    background: $mb-bg;
  }

  &__icon {
    margin-right: 20rpx;
    font-size: 36rpx;
  }

  &__body {
    flex: 1;
    display: flex;
    flex-direction: column;
    min-width: 0;
  }

  &__title {
    font-size: 30rpx;
    color: $mb-text;
  }

  &__label {
    margin-top: 4rpx;
    font-size: 24rpx;
    color: $mb-text-3;
  }

  &__extra {
    display: flex;
    align-items: center;
    margin-left: 16rpx;
  }

  &__value {
    font-size: 28rpx;
    color: $mb-text-3;
    max-width: 360rpx;
    text-align: right;
  }

  &__arrow {
    width: 16rpx;
    height: 16rpx;
    margin-left: 16rpx;
    border-top: 3rpx solid $mb-text-4;
    border-right: 3rpx solid $mb-text-4;
    transform: rotate(45deg);
  }
}
</style>
