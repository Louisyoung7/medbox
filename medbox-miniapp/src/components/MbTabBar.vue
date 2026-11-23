<template>
  <view class="mb-tabbar">
    <view
      v-for="item in tabs"
      :key="item.key"
      class="mb-tabbar__item"
      :class="{ 'is-active': item.path === currentPath }"
      hover-class="mb-tabbar__item--hover"
      @click="onSwitch(item)"
    >
      <image class="mb-tabbar__icon" :src="iconOf(item.icon, item.path === currentPath)" mode="aspectFit" />
      <text class="mb-tabbar__label">{{ item.title }}</text>
    </view>
  </view>
</template>

<script>
/**
 * 自定义底部 TabBar（`feat/mp-ui-kit`，非原生 tabBar）。
 *
 * <p>为什么不用原生 tabBar：① 微信原生 tabBar 最多 5 个 item，本期底部栏只放 4 个主入口
 * （`feat/mp-nav-drawer` 起次级入口收进右侧抽屉）；② 需按角色过滤可见项（见
 * `utils/permission.js`）。本组件读 `config/tabbar.js` 的 `TAB_ENTRIES` 经
 * `visibleEntries(当前角色)` 过滤后渲染，点击 `reLaunch` 切换；当前页高亮主色。
 * 底部适配 iPhone 安全区。图标与 `MbDrawer` 共用 `utils/nav-icon.js`。
 *
 * <p>每个 tab 页在底部放一份 `<MbTabBar />` 即可（骨架已统一加好）。
 */
import { getRole } from '@/store/user.js'
import { visibleEntries } from '@/utils/permission.js'
import { TAB_ENTRIES } from '@/config/tabbar.js'
import { iconDataUri } from '@/utils/nav-icon.js'

export default {
  name: 'MbTabBar',
  data() {
    return { currentPath: '', entries: TAB_ENTRIES }
  },
  computed: {
    tabs() {
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
    onSwitch(item) {
      if (item.path === this.currentPath) return
      uni.reLaunch({ url: item.path })
    },
  },
  created() {
    // 页面生命周期 onShow 不会在子组件触发；Tab 页切换走 reLaunch（页面重建），
    // 组件每次挂载即是最新页，故在 created 里读当前路由即可。
    this.syncCurrent()
  },
}
</script>

<style lang="scss" scoped>
.mb-tabbar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  height: 110rpx;
  padding-bottom: env(safe-area-inset-bottom);
  background: $mb-card-bg;
  box-shadow: 0 -4rpx 20rpx rgba(0, 0, 0, 0.05);
  z-index: 500;

  &__item {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    height: 100%;
    transition: transform 0.12s ease;

    &--hover {
      opacity: 0.7;
    }
  }

  &__icon {
    width: 46rpx;
    height: 46rpx;
    margin-bottom: 6rpx;
  }

  &__label {
    font-size: 22rpx;
    color: $mb-text-4;
  }

  &__item.is-active &__label {
    color: $mb-primary;
    font-weight: 600;
  }

  &__item.is-active {
    transform: translateY(-2rpx);
  }
}
</style>
