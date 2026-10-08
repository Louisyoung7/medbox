<template>
  <view class="content">
    <image class="logo" src="/static/logo.png"></image>
    <view class="text-area">
      <text class="title">智能药箱</text>
    </view>
    <text class="hint">地基分支 scaffold 占位页</text>
  </view>
</template>

<script>
export default {
  data() {
    return {}
  },
}
</script>

<style>
.content {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.logo {
  height: 200rpx;
  width: 200rpx;
  margin-top: 200rpx;
  margin-left: auto;
  margin-right: auto;
  margin-bottom: 50rpx;
}

.text-area {
  display: flex;
  justify-content: center;
}

.title {
  font-size: 36rpx;
  color: #8f8f94;
}

.hint {
  margin-top: 30rpx;
  font-size: 24rpx;
  color: #bbb;
}
</style>
