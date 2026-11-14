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
        <MbCell title="服药计划" value="格位排程" arrow clickable @click="go('/pages/plan/plan')" />
        <MbCell title="服药记录" value="按格记录" arrow clickable @click="go('/pages/record/record')" />
        <MbCell title="告警" :value="unreadText" arrow clickable @click="go('/pages/alarm/list')" />
        <MbCell title="监护关系" value="绑定与待确认" arrow clickable @click="go('/pages/guardian/guardian')" />
      </MbCard>

      <RoleGate entry="alarmSettings">
        <MbCard title="监护台">
          <view class="guard-row">
            <text class="guard-hint">为老人配置告警与阈值、环境上下限。</text>
            <MbButton type="ghost" size="small" @click="goSettings">告警设置</MbButton>
          </view>
        </MbCard>
      </RoleGate>
    </view>

    <MbTabBar />
  </view>
</template>

<script>
import MbCard from '@/components/MbCard.vue'
import MbCell from '@/components/MbCell.vue'
import MbButton from '@/components/MbButton.vue'
import MbTabBar from '@/components/MbTabBar.vue'
import RoleGate from '@/components/RoleGate.vue'
import WsStatusBar from '@/components/WsStatusBar.vue'
import { getUserInfo, roleLabel } from '@/store/user.js'
import { getAlarmState, refreshUnreadCount } from '@/store/alarm.js'

export default {
  name: 'HomePage',
  components: { MbCard, MbCell, MbButton, MbTabBar, RoleGate, WsStatusBar },
  data() {
    return {
      name: '',
      roleText: '',
    }
  },
  onShow() {
    const info = getUserInfo()
    this.name = info.name || info.userId || ''
    this.roleText = roleLabel(info.role)
    refreshUnreadCount()
  },
  computed: {
    avatarText() {
      return (this.name || '?').slice(0, 1)
    },
    unreadCount() {
      return getAlarmState().unreadCount || 0
    },
    unreadText() {
      return this.unreadCount > 0 ? `${this.unreadCount} 条未读` : '暂无未读'
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
    goSettings() {
      // 二级页用 navigateTo 入栈，保留首页才能出现原生返回键
      uni.navigateTo({ url: '/pages/alarm/settings' })
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

.guard-row {
  display: flex;
  align-items: center;
  justify-content: space-between;

  .guard-hint {
    flex: 1;
    padding-right: 20rpx;
  }
}
</style>
