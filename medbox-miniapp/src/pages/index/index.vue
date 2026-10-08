<template>
  <view class="content">
    <image class="logo" src="/static/logo.png"></image>
    <view class="text-area">
      <text class="title">智能药箱</text>
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

export default {
  data() {
    return {
      BASE_URL,
      WS_URL,
      hostFrom: SERVER_HOST,
    }
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

.hint {
  margin-top: 30rpx;
  font-size: 24rpx;
  color: #bbb;
}
</style>
