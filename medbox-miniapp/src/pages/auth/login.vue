<template>
  <view class="page">
    <view class="brand">
      <image class="brand-logo" src="/static/logo.png" mode="aspectFit"></image>
      <text class="brand-title">智能药箱</text>
      <text class="brand-sub">按时吃药，家人更安心</text>
    </view>

    <view class="card">
      <view class="field" :class="{ 'field--focus': focus === 'account' }">
        <text class="field-label">账号</text>
        <input
          class="field-input"
          v-model="account"
          type="text"
          placeholder="手机号 / 用户名"
          placeholder-class="field-placeholder"
          confirm-type="next"
          @focus="focus = 'account'"
          @blur="focus = ''"
        />
      </view>

      <view class="field" :class="{ 'field--focus': focus === 'password' }">
        <text class="field-label">密码</text>
        <input
          class="field-input"
          v-model="password"
          :password="!showPassword"
          placeholder="请输入密码"
          placeholder-class="field-placeholder"
          confirm-type="done"
          @focus="focus = 'password'"
          @blur="focus = ''"
          @confirm="onSubmit"
        />
        <text class="field-action" @click="showPassword = !showPassword">
          {{ showPassword ? '隐藏' : '显示' }}
        </text>
      </view>

      <text class="error" v-if="errorMsg">{{ errorMsg }}</text>

      <button
        class="submit"
        :class="{ 'submit--busy': submitting }"
        hover-class="submit--hover"
        :disabled="submitting"
        @click="onSubmit"
      >
        {{ submitting ? '登录中...' : '登录' }}
      </button>

      <view class="links">
        <text class="link link--muted" @click="onForgot">忘记密码？</text>
        <text class="link" @click="onGoRegister">还没有账号？去注册</text>
      </view>
    </view>

    <text class="footnote">局域网演示阶段，请与后端主机在同一网段</text>
  </view>
</template>

<script>
import { CODE } from '@/api/request.js'
import { login, navigateAfterLogin } from '@/store/auth.js'

export default {
  data() {
    return {
      account: '',
      password: '',
      showPassword: false,
      focus: '',
      submitting: false,
      errorMsg: '',
      redirect: '',
    }
  },
  onLoad(options) {
    // 路由守卫拦截时带来的回跳目标（登录成功后回跳，见 store/guard.js）
    this.redirect = (options && options.redirect) || ''
  },
  methods: {
    async onSubmit() {
      if (this.submitting) {
        return
      }
      const account = this.account.trim()
      if (!account) {
        this.errorMsg = '请输入账号'
        return
      }
      if (!this.password) {
        this.errorMsg = '请输入密码'
        return
      }

      this.errorMsg = ''
      this.submitting = true
      try {
        await login({ account, password: this.password })
        uni.showToast({ title: '登录成功', icon: 'success' })
        navigateAfterLogin(this.redirect)
      } catch (err) {
        this.errorMsg = describeError(err, '登录失败，请重试')
      } finally {
        this.submitting = false
      }
    },
    onGoRegister() {
      uni.navigateTo({ url: '/pages/auth/register' })
    },
    onForgot() {
      uni.showToast({ title: '请联系监护人重置密码', icon: 'none' })
    },
  },
}

/** 把请求错误翻成表单里的一行红字（登录接口是 silent 的，请求层不再 toast）。 */
function describeError(err, fallback) {
  if (!err) {
    return fallback
  }
  if (typeof err.isNetworkError === 'function' && err.isNetworkError()) {
    return '网络异常，请检查手机与电脑是否在同一局域网'
  }
  if (err.code === CODE.BAD_CREDENTIALS) {
    return '账号或密码错误'
  }
  return err.message || fallback
}
</script>

<style>
.page {
  min-height: 100vh;
  box-sizing: border-box;
  padding: 0 48rpx 60rpx;
  background: linear-gradient(180deg, #e3f6f3 0%, #f4f8fa 46%, #f4f8fa 100%);
}

.brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 72rpx 0 56rpx;
}

.brand-logo {
  width: 140rpx;
  height: 140rpx;
  margin-bottom: 24rpx;
}

.brand-title {
  font-size: 44rpx;
  font-weight: 600;
  color: #1b2b2a;
  letter-spacing: 2rpx;
}

.brand-sub {
  margin-top: 12rpx;
  font-size: 28rpx;
  color: #6b7c80;
}

.card {
  padding: 40rpx 32rpx 32rpx;
  border-radius: 28rpx;
  background-color: #ffffff;
  box-shadow: 0 8rpx 24rpx rgba(15, 163, 163, 0.12);
}

.field {
  display: flex;
  flex-direction: row;
  align-items: center;
  height: 104rpx;
  padding: 0 24rpx;
  margin-bottom: 24rpx;
  border: 2rpx solid #eef2f3;
  border-radius: 18rpx;
  background-color: #fafcfc;
  transition: border-color 0.2s, background-color 0.2s;
}

.field--focus {
  border-color: #0e9f8e;
  background-color: #ffffff;
}

.field-label {
  width: 96rpx;
  font-size: 30rpx;
  color: #1b2b2a;
}

.field-input {
  flex: 1;
  height: 104rpx;
  font-size: 32rpx;
  color: #1b2b2a;
}

.field-placeholder {
  color: #a8b4b6;
  font-size: 30rpx;
}

.field-action {
  padding-left: 20rpx;
  font-size: 28rpx;
  color: #0e9f8e;
}

.error {
  display: block;
  min-height: 36rpx;
  margin: -4rpx 8rpx 16rpx;
  font-size: 26rpx;
  color: #e5484d;
}

.submit {
  height: 96rpx;
  line-height: 96rpx;
  margin-top: 8rpx;
  border-radius: 48rpx;
  font-size: 34rpx;
  font-weight: 600;
  color: #ffffff;
  background: linear-gradient(135deg, #12b39f 0%, #0e9f8e 100%);
  box-shadow: 0 8rpx 20rpx rgba(14, 159, 142, 0.28);
}

.submit--hover {
  transform: scale(0.98);
  opacity: 0.94;
}

.submit--busy {
  opacity: 0.7;
}

.links {
  display: flex;
  flex-direction: row;
  justify-content: space-between;
  align-items: center;
  margin-top: 32rpx;
}

.link {
  font-size: 28rpx;
  color: #0e9f8e;
}

.link--muted {
  color: #6b7c80;
}

.footnote {
  display: block;
  margin-top: 40rpx;
  text-align: center;
  font-size: 24rpx;
  color: #a8b4b6;
}
</style>
