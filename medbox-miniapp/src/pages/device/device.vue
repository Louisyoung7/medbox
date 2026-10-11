<template>
  <view class="page">
    <view class="head">
      <text class="head__title">我的设备</text>
      <text class="head__sub">药箱主控与温湿度 / 光照 / 摄像头子设备</text>
    </view>

    <MbCard title="设备列表">
      <MbEmpty text="暂无设备，后端 device-core 联调后展示" />
    </MbCard>

    <MbTabBar />
  </view>
</template>

<script>
import MbCard from '@/components/MbCard.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import MbTabBar from '@/components/MbTabBar.vue'

export default {
  name: 'DevicePage',
  components: { MbCard, MbEmpty, MbTabBar },
}
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx calc(110rpx + env(safe-area-inset-bottom) + 24rpx);
  box-sizing: border-box;
}

.head {
  display: flex;
  flex-direction: column;
  margin-bottom: $mb-gap;

  &__title {
    font-size: 40rpx;
    font-weight: 600;
    color: $mb-text;
  }

  &__sub {
    margin-top: 8rpx;
    font-size: 26rpx;
    color: $mb-text-3;
  }
}
</style>
