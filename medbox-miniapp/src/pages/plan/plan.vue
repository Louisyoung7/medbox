<template>
  <view class="page">
    <view class="head">
      <text class="head__title">服药计划</text>
      <text class="head__sub">提醒时间、药品、剂量与备注</text>
    </view>

    <MbCard title="计划列表">
      <MbEmpty text="暂无计划，后端 plan-core 联调后展示" />
    </MbCard>

    <MbTabBar />
  </view>
</template>

<script>
import MbCard from '@/components/MbCard.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import MbTabBar from '@/components/MbTabBar.vue'

export default {
  name: 'PlanPage',
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
