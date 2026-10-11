<template>
  <view class="page">
    <view class="head">
      <text class="head__title">药品管理</text>
      <text class="head__sub">公共药品与所监护老人的私有药品</text>
    </view>

    <MbCard title="药品列表">
      <MbEmpty text="暂无药品，后端 medicine-core 联调后展示" />
    </MbCard>

    <MbTabBar />
  </view>
</template>

<script>
import MbCard from '@/components/MbCard.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import MbTabBar from '@/components/MbTabBar.vue'

export default {
  name: 'MedicinePage',
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
