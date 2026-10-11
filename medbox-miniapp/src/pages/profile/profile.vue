<template>
  <view class="page">
    <view class="profile-hero">
      <view class="profile-hero__avatar">{{ avatarText }}</view>
      <view class="profile-hero__info">
        <text class="profile-hero__name">{{ name || '未登录' }}</text>
        <text class="profile-hero__role">{{ roleText }}</text>
      </view>
    </view>

    <MbCard title="账号">
      <MbCell title="用户ID" :value="userId || '-'" />
      <MbCell title="角色" :value="roleText" />
    </MbCard>

    <RoleGate entry="alarmSettings">
      <MbCard title="监护台">
        <text class="guard-hint">仅监护人可见：告警设置、环境阈值、为老人配置 AI Key 将在此区域。</text>
      </MbCard>
    </RoleGate>

    <view class="profile-actions">
      <MbButton type="ghost" block @click="onLogout">退出登录</MbButton>
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
import { logout } from '@/store/auth.js'
import { getUserInfo, getUserId, roleLabel } from '@/store/user.js'

export default {
  name: 'ProfilePage',
  components: { MbCard, MbCell, MbButton, MbTabBar, RoleGate },
  data() {
    return {
      name: '',
      userId: '',
      roleText: '',
    }
  },
  onShow() {
    const info = getUserInfo()
    this.name = info.name || ''
    this.userId = info.userId || ''
    this.roleText = roleLabel(info.role)
  },
  computed: {
    avatarText() {
      return (this.name || '?').slice(0, 1)
    },
  },
  methods: {
    onLogout() {
      uni.showModal({
        title: '退出登录',
        content: '退出后需要重新输入账号密码',
        success: (res) => {
          if (res.confirm) logout()
        },
      })
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

.profile-hero {
  display: flex;
  align-items: center;
  padding: 40rpx;
  margin-bottom: $mb-gap;
  border-radius: $mb-radius-lg;
  background: $mb-gradient;

  &__avatar {
    width: 104rpx;
    height: 104rpx;
    border-radius: 50%;
    background: rgba(255, 255, 255, 0.25);
    color: #fff;
    font-size: 48rpx;
    font-weight: 600;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  &__info {
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
    margin-top: 8rpx;
    font-size: 26rpx;
    color: rgba(255, 255, 255, 0.85);
  }
}

.guard-hint {
  font-size: 26rpx;
  color: $mb-text-3;
  line-height: 1.6;
}

.profile-actions {
  margin-top: 12rpx;
}
</style>
