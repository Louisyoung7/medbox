<template>
  <view class="mb-empty">
    <image class="mb-empty__img" :src="svg" mode="aspectFit" />
    <text class="mb-empty__text">{{ text }}</text>
    <view v-if="$slots.default" class="mb-empty__action"><slot /></view>
  </view>
</template>

<script>
/**
 * 列表空状态（`feat/mp-ui-kit`）：SVG 插画 + 文案 + 可选操作插槽（如"去添加"按钮）。
 */
const SVG =
  "data:image/svg+xml," +
  encodeURIComponent(
    "<svg xmlns='http://www.w3.org/2000/svg' width='200' height='200' viewBox='0 0 24 24' fill='none' stroke='%23c7d3d2' stroke-width='1.4' stroke-linecap='round' stroke-linejoin='round'><rect x='3' y='4' width='18' height='16' rx='3'/><path d='M3 9h18M8 14h8'/></svg>"
  )

export default {
  name: 'MbEmpty',
  props: {
    text: { type: String, default: '暂无数据' },
  },
  data() {
    return { svg: SVG }
  },
}
</script>

<style lang="scss" scoped>
.mb-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80rpx 40rpx;

  &__img {
    width: 180rpx;
    height: 180rpx;
    opacity: 0.9;
  }

  &__text {
    margin-top: 24rpx;
    font-size: 28rpx;
    color: $mb-text-4;
  }

  &__action {
    margin-top: 32rpx;
  }
}
</style>
