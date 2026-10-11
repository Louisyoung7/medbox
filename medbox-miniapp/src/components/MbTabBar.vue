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
 * <p>为什么不用原生 tabBar：① 微信原生 tabBar 最多 5 个 item，而本期需 6 个入口；
 * ② 需按角色过滤可见项（见 `utils/permission.js`）。本组件读 `config/tabbar.js` 的
 * `TAB_ENTRIES` 经 `visibleEntries(当前角色)` 过滤后渲染，点击 `reLaunch` 切换；
 * 当前页高亮主色。底部适配 iPhone 安全区。
 *
 * <p>每个 tab 页在底部放一份 `<MbTabBar />` 即可（骨架已统一加好）。
 */
import { getRole } from '@/store/user.js'
import { visibleEntries } from '@/utils/permission.js'
import { TAB_ENTRIES } from '@/config/tabbar.js'

// 图标 SVG（24x24，描边风格），按 active 切换主色 / 灰
const ICONS = {
  home: "<path d='M3 11l9-8 9 8'/><path d='M5 10v9a1 1 0 0 0 1 1h4v-5h4v5h4a1 1 0 0 0 1-1v-9'/>",
  device: "<rect x='4' y='4' width='16' height='16' rx='3'/><path d='M9 9h6M9 13h6'/>",
  medicine:
    "<rect x='3' y='9' width='18' height='6' rx='3' transform='rotate(45 12 12)'/><path d='M12 9v6M9 12h6'/>",
  plan: "<circle cx='12' cy='12' r='8'/><path d='M12 8v4l3 2'/>",
  record: "<rect x='5' y='3' width='14' height='18' rx='2'/><path d='M8 8h8M8 12h8M8 16h5'/>",
  profile: "<circle cx='12' cy='8' r='4'/><path d='M4 21c0-4 4-6 8-6s8 2 8 6'/>",
}

function iconDataUri(name, active) {
  const color = active ? '#0e9f8e' : '#a8b4b6'
  const body = ICONS[name] || ''
  const svg =
    "<svg xmlns='http://www.w3.org/2000/svg' width='48' height='48' viewBox='0 0 24 24' fill='none' stroke='" +
    color +
    "' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'>" +
    body +
    '</svg>'
  return 'data:image/svg+xml,' + encodeURIComponent(svg)
}

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
