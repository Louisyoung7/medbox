<template>
  <view class="page">
    <view class="hero">
      <view class="hero__ws">
        <WsStatusBar />
      </view>
      <view class="hero__top">
        <view class="hero__avatar">{{ avatarText }}</view>
        <view class="hero__who">
          <text class="hero__name">{{ name || '未登录' }}</text>
          <text class="hero__role">{{ roleText }}</text>
        </view>
      </view>
      <text class="hero__greeting">{{ greeting }}</text>
    </view>

    <view class="section">
      <MbCard title="功能入口">
        <MbCell title="我的设备" value="药箱与传感器" arrow clickable @click="go('/pages/device/device')" />
        <MbCell title="药品管理" value="公共与私有药品" arrow clickable @click="go('/pages/medicine/medicine')" />
        <MbCell title="服药计划" value="提醒与剂量" arrow clickable @click="go('/pages/plan/plan')" />
        <MbCell title="服药记录" value="按时 / 错服明细" arrow clickable @click="go('/pages/record/record')" />
        <MbCell title="监护关系" value="绑定与待确认" arrow clickable @click="go('/pages/guardian/guardian')" />
      </MbCard>

      <RoleGate entry="alarmSettings">
        <MbCard title="监护台">
          <text class="guard-hint">仅监护人可见：告警设置、环境阈值、为老人配置 AI Key 将在此区域。</text>
        </MbCard>
      </RoleGate>

      <MbCard title="组件演示">
        <view class="demo-row">
          <MbButton type="primary" @click="openModal">弹窗</MbButton>
          <MbButton type="ghost" @click="toggleLoading">加载</MbButton>
        </view>
      </MbCard>
    </view>

    <MbModal v-model="showModal" title="提示" confirm-text="知道了">
      这是 MedBox UI Kit 的弹窗组件示例。
    </MbModal>
    <MbLoading v-if="loading" text="加载中..." />

    <MbTabBar />
  </view>
</template>

<script>
import MbCard from '@/components/MbCard.vue'
import MbCell from '@/components/MbCell.vue'
import MbButton from '@/components/MbButton.vue'
import MbModal from '@/components/MbModal.vue'
import MbLoading from '@/components/MbLoading.vue'
import MbTabBar from '@/components/MbTabBar.vue'
import RoleGate from '@/components/RoleGate.vue'
import WsStatusBar from '@/components/WsStatusBar.vue'
import { getUserInfo, roleLabel } from '@/store/user.js'

export default {
  name: 'HomePage',
  components: { MbCard, MbCell, MbButton, MbModal, MbLoading, MbTabBar, RoleGate, WsStatusBar },
  data() {
    return {
      name: '',
      roleText: '',
      showModal: false,
      loading: false,
    }
  },
  onShow() {
    const info = getUserInfo()
    this.name = info.name || info.userId || ''
    this.roleText = roleLabel(info.role)
  },
  computed: {
    avatarText() {
      return (this.name || '?').slice(0, 1)
    },
    greeting() {
      const h = new Date().getHours()
      if (h < 6) return '夜深了，注意休息'
      if (h < 12) return '早上好，按时吃药'
      if (h < 18) return '下午好，记得喝水'
      return '晚上好，别忘服药'
    },
  },
  methods: {
    go(path) {
      uni.reLaunch({ url: path })
    },
    openModal() {
      this.showModal = true
    },
    toggleLoading() {
      this.loading = true
      setTimeout(() => (this.loading = false), 1200)
    },
  },
}
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx calc(110rpx + env(safe-area-inset-bottom) + 24rpx);
  box-sizing: border-box;
}

.hero {
  margin: -24rpx -24rpx 24rpx;
  padding: 48rpx 40rpx calc(48rpx + 24rpx);
  background: $mb-gradient;
  border-bottom-left-radius: 40rpx;
  border-bottom-right-radius: 40rpx;

  &__ws {
    margin-bottom: 24rpx;
  }

  &__top {
    display: flex;
    align-items: center;
  }

  &__avatar {
    width: 96rpx;
    height: 96rpx;
    border-radius: 50%;
    background: rgba(255, 255, 255, 0.25);
    color: #fff;
    font-size: 44rpx;
    font-weight: 600;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  &__who {
    margin-left: 24rpx;
    display: flex;
    flex-direction: column;
  }

  &__name {
    font-size: 38rpx;
    font-weight: 600;
    color: #fff;
  }

  &__role {
    margin-top: 6rpx;
    font-size: 24rpx;
    color: rgba(255, 255, 255, 0.85);
  }

  &__greeting {
    display: block;
    margin-top: 28rpx;
    font-size: 30rpx;
    color: #fff;
  }
}

.guard-hint {
  font-size: 26rpx;
  color: $mb-text-3;
  line-height: 1.6;
}

.demo-row {
  display: flex;
  gap: 20rpx;

  .mb-btn {
    flex: 1;
  }
}
</style>
