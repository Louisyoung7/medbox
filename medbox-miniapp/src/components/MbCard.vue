<template>
  <view class="mb-card">
    <view v-if="title || $slots.header" class="mb-card__head">
      <text class="mb-card__title">{{ title }}</text>
      <view class="mb-card__head-extra"><slot name="header" /></view>
    </view>
    <view class="mb-card__body"><slot /></view>
  </view>
</template>

<script>
/**
 * 卡片容器（`feat/mp-ui-kit`）：圆角白卡 + 柔和阴影，承载各页面内容区块。
 * 可选标题（`title`）或具名插槽 `header`（右侧操作）。
 */
export default {
  name: 'MbCard',
  props: {
    title: { type: String, default: '' },
  },
}
</script>

<style lang="scss" scoped>
.mb-card {
  background: $mb-card-bg;
  border-radius: $mb-radius;
  box-shadow: $mb-shadow;
  padding: 32rpx;
  margin-bottom: $mb-gap;

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 20rpx;
  }

  &__title {
    font-size: 32rpx;
    font-weight: 600;
    color: $mb-text;
  }

  &__head-extra {
    display: flex;
    align-items: center;
  }
}
</style>
