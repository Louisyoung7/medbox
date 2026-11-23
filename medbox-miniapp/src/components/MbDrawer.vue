<template>
  <view class="mb-drawer">
    <!-- 右上「三横杠」：底部栏只放主入口，次级入口从这里进 -->
    <view class="mb-drawer__trigger" hover-class="mb-drawer__trigger--hover" @click="open">
      <view class="mb-drawer__bars">
        <view class="mb-drawer__bar"></view>
        <view class="mb-drawer__bar"></view>
        <view class="mb-drawer__bar"></view>
      </view>
    </view>

    <view class="mb-drawer__mask" :class="{ 'is-open': visible }" @click="close"></view>

    <view class="mb-drawer__panel" :class="{ 'is-open': visible }">
      <view class="mb-drawer__head">
        <text class="mb-drawer__title">更多功能</text>
        <view class="mb-drawer__close" hover-class="mb-drawer__close--hover" @click="close">×</view>
      </view>

      <view
        v-for="item in items"
        :key="item.key"
        class="mb-drawer__item"
        :class="{ 'is-active': item.path === currentPath }"
        hover-class="mb-drawer__item--hover"
        @click="onSelect(item)"
      >
        <image class="mb-drawer__icon" :src="iconOf(item.icon, item.path === currentPath)" mode="aspectFit" />
        <text class="mb-drawer__label">{{ item.title }}</text>
      </view>
    </view>
  </view>
</template>

<script>
/**
 * 右侧侧边栏（`feat/mp-nav-drawer`）：右上三横杠打开，收纳底部栏放不下的次级入口。
 *
 * <p>入口读 `config/tabbar.js` 的 `DRAWER_ENTRIES`，经 `visibleEntries(当前角色)` 过滤；
 * 一级页（`tab: true`）用 `reLaunch` 保持单页栈，二级页用 `navigateTo` 保留返回。
 * 图标与 `MbTabBar` 共用 `utils/nav-icon.js`。放在主页面里，不放进 `MbTabBar`，
 * 避免二级页（已有原生返回键）也出现三横杠。
 */
import { getRole } from '@/store/user.js'
import { visibleEntries } from '@/utils/permission.js'
import { DRAWER_ENTRIES } from '@/config/tabbar.js'
import { iconDataUri } from '@/utils/nav-icon.js'

export default {
  name: 'MbDrawer',
  data() {
    return { visible: false, currentPath: '', entries: DRAWER_ENTRIES }
  },
  computed: {
    items() {
      return visibleEntries(getRole(), this.entries)
    },
  },
  methods: {
    iconOf(name, active) {
      return iconDataUri(name, active)
    },
    syncCurrent() {
      const pages = getCurrentPages()
      const last = pages[pages.length - 1]
      this.currentPath = last ? '/' + last.route : ''
    },
    open() {
      this.syncCurrent()
      this.visible = true
    },
    close() {
      this.visible = false
    },
    onSelect(item) {
      this.close()
      if (item.path === this.currentPath) return
      if (item.tab) {
        uni.reLaunch({ url: item.path })
      } else {
        uni.navigateTo({ url: item.path })
      }
    },
  },
}
</script>

<style lang="scss" scoped>
.mb-drawer {
  &__trigger {
    position: fixed;
    top: 16rpx;
    right: 24rpx;
    width: 68rpx;
    height: 68rpx;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 50%;
    background: rgba(255, 255, 255, 0.94);
    border: 1rpx solid $mb-border;
    box-shadow: 0 4rpx 16rpx rgba(0, 0, 0, 0.08);
    z-index: 520;

    &--hover {
      opacity: 0.7;
    }
  }

  &__bars {
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    width: 30rpx;
    height: 22rpx;
  }

  &__bar {
    width: 100%;
    height: 4rpx;
    border-radius: 2rpx;
    background: $mb-text-2;
  }

  &__mask {
    position: fixed;
    top: 0;
    right: 0;
    bottom: 0;
    left: 0;
    background: $mb-mask;
    opacity: 0;
    visibility: hidden;
    transition: opacity 0.2s ease, visibility 0.2s ease;
    z-index: 540;

    &.is-open {
      opacity: 1;
      visibility: visible;
    }
  }

  &__panel {
    position: fixed;
    top: 0;
    right: 0;
    bottom: 0;
    width: 520rpx;
    display: flex;
    flex-direction: column;
    padding: 24rpx 0 calc(24rpx + env(safe-area-inset-bottom));
    background: $mb-card-bg;
    border-top-left-radius: $mb-radius-lg;
    border-bottom-left-radius: $mb-radius-lg;
    transform: translateX(100%);
    transition: transform 0.24s ease;
    z-index: 550;

    &.is-open {
      transform: translateX(0);
    }
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 8rpx 32rpx 24rpx;
    border-bottom: 1rpx solid $mb-border;
  }

  &__title {
    font-size: 30rpx;
    font-weight: 600;
    color: $mb-text;
  }

  &__close {
    width: 56rpx;
    height: 56rpx;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 44rpx;
    line-height: 1;
    color: $mb-text-3;

    &--hover {
      opacity: 0.6;
    }
  }

  &__item {
    display: flex;
    align-items: center;
    padding: 30rpx 32rpx;

    &--hover {
      background: $mb-bg;
    }

    &.is-active {
      background: $mb-primary-bg;
    }
  }

  &__icon {
    width: 44rpx;
    height: 44rpx;
    margin-right: 20rpx;
  }

  &__label {
    font-size: 30rpx;
    color: $mb-text;
  }

  &__item.is-active &__label {
    color: $mb-primary;
    font-weight: 600;
  }
}
</style>
