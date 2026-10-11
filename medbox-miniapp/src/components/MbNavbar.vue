<template>
  <view class="mb-navbar" :style="{ paddingTop: statusBarHeight + 'px' }">
    <view class="mb-navbar__bar">
      <view v-if="back" class="mb-navbar__back" hover-class="mb-navbar__back--hover" @click="onBack">
        <view class="mb-navbar__back-arrow"></view>
      </view>
      <text class="mb-navbar__title">{{ title }}</text>
      <view class="mb-navbar__right"><slot name="right" /></view>
    </view>
  </view>
</template>

<script>
/**
 * 自定义导航栏（`feat/mp-ui-kit`）：适配状态栏高度，支持标题、返回键、右侧插槽。
 * 用于需要自定义头部的页面（如「我的」页 hero）；常规页面仍用 `pages.json` 原生导航栏。
 */
export default {
  name: 'MbNavbar',
  props: {
    title: { type: String, default: '' },
    back: { type: Boolean, default: false },
  },
  data() {
    return { statusBarHeight: 20 }
  },
  mounted() {
    try {
      const info =
        (uni.getWindowInfo && uni.getWindowInfo()) || uni.getSystemInfoSync()
      this.statusBarHeight = info.statusBarHeight || 20
    } catch (e) {
      /* 非小程序环境忽略 */
    }
  },
  methods: {
    onBack() {
      const pages = getCurrentPages()
      if (pages.length > 1) {
        uni.navigateBack()
      } else {
        uni.reLaunch({ url: '/pages/index/index' })
      }
    },
  },
}
</script>

<style lang="scss" scoped>
.mb-navbar {
  background: $mb-card-bg;

  &__bar {
    display: flex;
    align-items: center;
    height: 88rpx;
    padding: 0 24rpx;
    position: relative;
  }

  &__title {
    flex: 1;
    text-align: center;
    font-size: 34rpx;
    font-weight: 600;
    color: $mb-text;
  }

  &__back {
    position: absolute;
    left: 16rpx;
    width: 64rpx;
    height: 64rpx;
    display: flex;
    align-items: center;
    justify-content: center;

    &--hover {
      opacity: 0.6;
    }
  }

  &__back-arrow {
    width: 20rpx;
    height: 20rpx;
    border-top: 4rpx solid $mb-text;
    border-left: 4rpx solid $mb-text;
    transform: rotate(-45deg);
  }

  &__right {
    position: absolute;
    right: 16rpx;
    display: flex;
    align-items: center;
  }
}
</style>
