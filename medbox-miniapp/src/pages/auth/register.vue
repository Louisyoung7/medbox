<template>
  <view class="page">
    <view class="brand">
      <text class="brand-title">注册账号</text>
      <text class="brand-sub">监护人可为老人代建账号</text>
    </view>

    <view class="card">
      <view class="field" :class="{ 'field--focus': focus === 'phone' }">
        <text class="field-label">手机号</text>
        <input
          class="field-input"
          v-model="phone"
          type="number"
          maxlength="11"
          placeholder="11 位手机号"
          placeholder-class="field-placeholder"
          confirm-type="next"
          @focus="focus = 'phone'"
          @blur="focus = ''"
        />
      </view>

      <view class="field" :class="{ 'field--focus': focus === 'password' }">
        <text class="field-label">密码</text>
        <input
          class="field-input"
          v-model="password"
          :password="!showPassword"
          placeholder="至少 6 位"
          placeholder-class="field-placeholder"
          confirm-type="next"
          @focus="focus = 'password'"
          @blur="focus = ''"
        />
        <text class="field-action" @click="showPassword = !showPassword">
          {{ showPassword ? '隐藏' : '显示' }}
        </text>
      </view>

      <view class="field" :class="{ 'field--focus': focus === 'confirm' }">
        <text class="field-label">确认密码</text>
        <input
          class="field-input"
          v-model="confirmPassword"
          :password="!showPassword"
          placeholder="再输入一次密码"
          placeholder-class="field-placeholder"
          confirm-type="next"
          @focus="focus = 'confirm'"
          @blur="focus = ''"
        />
      </view>

      <view class="field" :class="{ 'field--focus': focus === 'name' }">
        <text class="field-label">姓名</text>
        <input
          class="field-input"
          v-model="name"
          type="text"
          placeholder="选填"
          placeholder-class="field-placeholder"
          confirm-type="done"
          @focus="focus = 'name'"
          @blur="focus = ''"
        />
      </view>

      <text class="section-title">我的身份</text>
      <view class="roles">
        <view
          class="role"
          :class="{ 'role--active': role === 'ELDER' }"
          hover-class="role--hover"
          @click="role = 'ELDER'"
        >
          <text class="role-name">我是老人</text>
          <text class="role-desc">查看自己的服药计划</text>
        </view>
        <view
          class="role"
          :class="{ 'role--active': role === 'GUARDIAN' }"
          hover-class="role--hover"
          @click="role = 'GUARDIAN'"
        >
          <text class="role-name">我是监护人</text>
          <text class="role-desc">代管老人的药品与计划</text>
        </view>
      </view>
      <text class="role-hint">护理 / 医生身份由老人确认监护关系时指定，无需在此选择</text>

      <text class="error" v-if="errorMsg">{{ errorMsg }}</text>

      <button
        class="submit"
        :class="{ 'submit--busy': submitting }"
        hover-class="submit--hover"
        :disabled="submitting"
        @click="onSubmit"
      >
        {{ submitting ? '注册中...' : '注册' }}
      </button>

      <text class="agreement">注册即表示同意《用药数据仅用于本人与监护人查看》</text>
    </view>

    <text class="links">
      <text class="link" @click="onGoLogin">已有账号？去登录</text>
    </text>
  </view>
</template>

<script>
import { CODE } from '@/api/request.js'
import { register, navigateAfterLogin } from '@/store/auth.js'
import { ROLE } from '@/store/user.js'

export default {
  data() {
    return {
      phone: '',
      password: '',
      confirmPassword: '',
      name: '',
      role: ROLE.GUARDIAN,
      showPassword: false,
      focus: '',
      submitting: false,
      errorMsg: '',
      redirect: '',
    }
  },
  onLoad(options) {
    this.redirect = (options && options.redirect) || ''
  },
  methods: {
    async onSubmit() {
      if (this.submitting) {
        return
      }
      const phone = this.phone.trim()
      if (!/^1\d{10}$/.test(phone)) {
        this.errorMsg = '请输入正确的 11 位手机号'
        return
      }
      if (this.password.length < 6) {
        this.errorMsg = '密码至少 6 位'
        return
      }
      if (this.password !== this.confirmPassword) {
        this.errorMsg = '两次输入的密码不一致'
        return
      }
      if (!this.role) {
        this.errorMsg = '请选择身份'
        return
      }

      this.errorMsg = ''
      this.submitting = true
      try {
        await register({
          phone,
          password: this.password,
          role: this.role,
          name: this.name.trim() || undefined,
        })
        uni.showToast({ title: '注册成功', icon: 'success' })
        navigateAfterLogin(this.redirect)
      } catch (err) {
        this.errorMsg = describeError(err, '注册失败，请重试')
      } finally {
        this.submitting = false
      }
    },
    onGoLogin() {
      uni.navigateTo({ url: '/pages/auth/login' })
    },
  },
}

/** 注册错误文案：手机号重复 / 参数不合法（40001）都直接透出后端 message。 */
function describeError(err, fallback) {
  if (!err) {
    return fallback
  }
  if (typeof err.isNetworkError === 'function' && err.isNetworkError()) {
    return '网络异常，请检查手机与电脑是否在同一局域网'
  }
  if (err.code === CODE.BAD_REQUEST || err.code === CODE.BAD_CREDENTIALS) {
    return err.message || '手机号或密码不符合要求'
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
  padding: 64rpx 0 40rpx;
}

.brand-title {
  font-size: 40rpx;
  font-weight: 600;
  color: #1b2b2a;
}

.brand-sub {
  margin-top: 12rpx;
  font-size: 28rpx;
  color: #6b7c80;
}

.card {
  padding: 36rpx 32rpx 32rpx;
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
  margin-bottom: 20rpx;
  border: 2rpx solid #eef2f3;
  border-radius: 18rpx;
  background-color: #fafcfc;
}

.field--focus {
  border-color: #0e9f8e;
  background-color: #ffffff;
}

.field-label {
  width: 140rpx;
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

.section-title {
  display: block;
  margin: 20rpx 8rpx 16rpx;
  font-size: 30rpx;
  font-weight: 500;
  color: #1b2b2a;
}

.roles {
  display: flex;
  flex-direction: row;
  justify-content: space-between;
}

.role {
  width: 48%;
  box-sizing: border-box;
  padding: 24rpx 20rpx;
  border: 2rpx solid #eef2f3;
  border-radius: 18rpx;
  background-color: #fafcfc;
}

.role--active {
  border-color: #0e9f8e;
  background-color: #e3f6f3;
}

.role--hover {
  transform: scale(0.98);
}

.role-name {
  display: block;
  font-size: 32rpx;
  font-weight: 600;
  color: #1b2b2a;
}

.role-desc {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #6b7c80;
}

.role-hint {
  display: block;
  margin: 16rpx 8rpx 0;
  font-size: 24rpx;
  color: #a8b4b6;
}

.error {
  display: block;
  min-height: 36rpx;
  margin: 20rpx 8rpx 8rpx;
  font-size: 26rpx;
  color: #e5484d;
}

.submit {
  height: 96rpx;
  line-height: 96rpx;
  margin-top: 16rpx;
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

.agreement {
  display: block;
  margin-top: 24rpx;
  text-align: center;
  font-size: 24rpx;
  color: #a8b4b6;
}

.links {
  display: block;
  margin-top: 40rpx;
  text-align: center;
}

.link {
  font-size: 28rpx;
  color: #0e9f8e;
}
</style>
