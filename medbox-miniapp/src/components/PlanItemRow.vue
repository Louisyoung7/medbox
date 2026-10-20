<template>
  <view class="item">
    <view class="item__head">
      <view class="item__title-wrap">
        <text class="item__name">{{ name }}</text>
        <text v-if="spec" class="item__spec">{{ spec }}</text>
      </view>
      <view class="item__ops">
        <text class="item__op" :class="{ 'item__op--off': !canUp }" @click="move(-1)">↑</text>
        <text class="item__op" :class="{ 'item__op--off': !canDown }" @click="move(1)">↓</text>
        <text class="item__op item__op--del" @click="remove">×</text>
      </view>
    </view>
    <view class="item__fields">
      <MbField
        class="item__field"
        :model-value="item.dose"
        placeholder="剂量"
        @input="onField('dose', $event)"
      />
      <MbField
        class="item__field"
        :model-value="item.unit"
        placeholder="单位"
        @input="onField('unit', $event)"
      />
    </view>
    <MbField
      :model-value="item.note"
      placeholder="服用说明（选填，如：餐后）"
      @input="onField('note', $event)"
    />
  </view>
</template>

<script>
import MbField from '@/components/MbField.vue'

/**
 * 计划明细行（`feat/mp-plan`）：一种药的剂量 / 单位 / 备注编辑 + 排序 / 移除。
 * 纯展示组件，所有变更经 emit 上交页面（`utils/plan.js` 的 buildPlanPayload 统一组装）。
 */
export default {
  name: 'PlanItemRow',
  components: { MbField },
  props: {
    item: { type: Object, required: true },
    name: { type: String, default: '' },
    spec: { type: String, default: '' },
    index: { type: Number, default: 0 },
    count: { type: Number, default: 1 },
  },
  computed: {
    canUp() {
      return this.index > 0
    },
    canDown() {
      return this.index < this.count - 1
    },
  },
  methods: {
    onField(field, value) {
      this.$emit('field', { index: this.index, field, value })
    },
    move(dir) {
      if (dir < 0 && !this.canUp) return
      if (dir > 0 && !this.canDown) return
      this.$emit('move', { index: this.index, dir })
    },
    remove() {
      this.$emit('remove', this.index)
    },
  },
}
</script>

<style lang="scss" scoped>
.item {
  padding: 20rpx 0;
  border-bottom: 2rpx solid $mb-border;

  &:last-child {
    border-bottom: none;
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__title-wrap {
    display: flex;
    align-items: baseline;
    flex: 1;
  }

  &__name {
    font-size: 28rpx;
    font-weight: 500;
    color: $mb-text;
  }

  &__spec {
    margin-left: 12rpx;
    font-size: 24rpx;
    color: $mb-text-3;
  }

  &__ops {
    display: flex;
    align-items: center;
    gap: 20rpx;
  }

  &__op {
    width: 44rpx;
    height: 44rpx;
    line-height: 44rpx;
    text-align: center;
    font-size: 28rpx;
    color: $mb-primary;
    background: $mb-primary-bg;
    border-radius: 50%;

    &--off {
      color: $mb-text-4;
      background: $mb-border;
    }

    &--del {
      color: $mb-danger;
      background: #fdecec;
    }
  }

  &__fields {
    display: flex;
    gap: 20rpx;
    margin-top: 8rpx;
  }

  &__field {
    flex: 1;
  }
}
</style>
