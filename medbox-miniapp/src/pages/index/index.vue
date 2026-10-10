<template>
  <view class="content">
    <image class="logo" src="/static/logo.png"></image>
    <view class="text-area">
      <text class="title">智能药箱</text>
    </view>

    <!-- 登录态（feat/mp-auth 临时入口，后续由 mp-ui-kit / mp-profile 接管） -->
    <view class="card">
      <view class="row">
        <text class="label">登录状态</text>
        <text class="value" :class="{ 'value--ok': loggedIn }">
          {{ loggedIn ? '已登录' : '未登录' }}
        </text>
      </view>
      <view class="row" v-if="loggedIn">
        <text class="label">当前用户</text>
        <text class="value">{{ userId }}（{{ roleText }}）</text>
      </view>

      <view class="actions">
        <button v-if="loggedIn" class="btn btn--ghost" hover-class="btn--hover" @click="onLogout">
          退出登录
        </button>
        <button v-else class="btn" hover-class="btn--hover" @click="onGoLogin">去登录</button>
        <button v-if="!loggedIn" class="btn btn--ghost" hover-class="btn--hover" @click="onGoRegister">
          去注册
        </button>
      </view>
    </view>

    <view class="card">
      <view class="row">
        <text class="label">后端 REST</text>
        <text class="value">{{ BASE_URL }}</text>
      </view>
      <view class="row">
        <text class="label">WebSocket</text>
        <text class="value">{{ WS_URL }}</text>
      </view>
      <view class="row">
        <text class="label">当前 HOST</text>
        <text class="value">{{ hostFrom }}</text>
      </view>
    </view>

    <text class="hint">地基分支 scaffold 占位页；地址取自 src/config/index.js</text>
  </view>
</template>

<script>
import { BASE_URL, WS_URL, SERVER_HOST } from '@/config/index.js'
import { isLoggedIn, logout } from '@/store/auth.js'
import { getUserInfo, roleLabel } from '@/store/user.js'

export default {
  data() {
    return {
      BASE_URL,
      WS_URL,
      hostFrom: SERVER_HOST,
      loggedIn: false,
      userId: '',
      roleText: '',
    }
  },
  onShow() {
    // 登录 / 退出后都会回到这里，故在 onShow 里同步（不是 onLoad）
    const info = getUserInfo()
    this.loggedIn = isLoggedIn()
    this.userId = info.userId || ''
    this.roleText = roleLabel(info.role)
  },
  methods: {
    onLogout() {
      uni.showModal({
        title: '退出登录',
        content: '退出后需要重新输入账号密码',
        success: (res) => {
          if (res.confirm) {
            logout()
          }
        },
      })
    },
    onGoLogin() {
      uni.navigateTo({ url: '/pages/auth/login' })
    },
    onGoRegister() {
      uni.navigateTo({ url: '/pages/auth/register' })
    },
  },
}
</script>

<style>
.content {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 30rpx;
}

.logo {
  height: 160rpx;
  width: 160rpx;
  margin-top: 60rpx;
}

.text-area {
  display: flex;
  justify-content: center;
  margin: 30rpx 0;
}

.title {
  font-size: 40rpx;
  color: #333;
}

.card {
  width: 100%;
  box-sizing: border-box;
  padding: 24rpx;
  margin-bottom: 24rpx;
  border-radius: 12rpx;
  background-color: #f8f8f8;
}

.row {
  display: flex;
  flex-direction: column;
  margin-bottom: 20rpx;
  word-break: break-all;
}

.label {
  font-size: 24rpx;
  color: #999;
}

.value {
  font-size: 28rpx;
  color: #333;
}

.value--ok {
  color: #0e9f8e;
}

.actions {
  display: flex;
  flex-direction: row;
}

.btn {
  flex: 1;
  height: 84rpx;
  line-height: 84rpx;
  margin-right: 16rpx;
  padding: 0;
  border-radius: 42rpx;
  font-size: 30rpx;
  color: #ffffff;
  background: linear-gradient(135deg, #12b39f 0%, #0e9f8e 100%);
}

.btn:last-child {
  margin-right: 0;
}

.btn--ghost {
  color: #0e9f8e;
  background: #e3f6f3;
}

.btn--hover {
  transform: scale(0.98);
  opacity: 0.94;
}

.hint {
  margin-top: 30rpx;
  font-size: 24rpx;
  color: #bbb;
}
</style>
