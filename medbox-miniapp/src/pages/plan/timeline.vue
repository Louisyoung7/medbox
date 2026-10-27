<template>
  <view class="page">
    <view class="head">
      <text class="head__title">{{ planName || '服药时间线' }}</text>
      <text class="head__sub">未来待服药 · 按设备本地时间</text>
    </view>

    <view v-if="!error" class="days">
      <view
        v-for="opt in dayOptions"
        :key="opt"
        class="days__item"
        :class="{ 'days__item--on': days === opt }"
        @click="changeDays(opt)"
      >
        <text class="days__text">{{ opt }} 天</text>
      </view>
    </view>

    <MbLoading v-if="loading && groups.length === 0" fullscreen text="加载中" />

    <MbEmpty v-else-if="error" :text="error" />

    <MbEmpty v-else-if="isDisabled" text="计划已停用，暂无未来服药" />

    <MbEmpty
      v-else-if="!loading && groups.length === 0"
      :text="`未来 ${days} 天暂无服药安排`"
    />

    <template v-else>
      <MbCard v-for="group in groups" :key="group.day" :title="group.day">
        <view
          v-for="dose in group.doses"
          :key="dose.scheduledTimeLocal"
          class="dose"
        >
          <text class="dose__time">{{ dose.time }}</text>
          <view class="dose__body">
            <view
              v-for="(item, i) in dose.items"
              :key="item.medicineId || i"
              class="item"
            >
              <text class="item__dot" />
              <text class="item__text">{{ item.text }}</text>
            </view>
            <text v-if="!dose.items.length" class="item__empty">未配置药品</text>
          </view>
        </view>
      </MbCard>
    </template>

    <MbTabBar />
  </view>
</template>

<script>
import MbCard from '@/components/MbCard.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import MbLoading from '@/components/MbLoading.vue'
import MbTabBar from '@/components/MbTabBar.vue'
import { getPlan, getNextDoses } from '@/api/plan.js'
import { CODE } from '@/api/request.js'
import { parseNextDoses, toDoseView, groupDosesByDay } from '@/utils/plan.js'

/** 列表/详情错误文案按 code 细分（请求已 silent，页面统一自绘）。 */
function errorText(err) {
  if (err && err.code === CODE.FORBIDDEN) return '无权访问或该老人不存在'
  if (err && err.code === CODE.GUARDIAN_PENDING) return '监护关系未生效，请等待老人确认'
  return (err && err.message) || '加载失败'
}

export default {
  name: 'PlanTimelinePage',
  components: { MbCard, MbEmpty, MbLoading, MbTabBar },
  data() {
    return {
      planId: '',
      elderId: '',
      planName: '',
      planStatus: '',
      days: 7,
      dayOptions: [7, 15, 30],
      groups: [],
      loading: false,
      error: '',
      loaded: false,
    }
  },
  computed: {
    isDisabled() {
      return this.planStatus === 'DISABLED'
    },
  },
  onLoad(options) {
    this.planId = (options && options.planId) || ''
    this.elderId = (options && options.elderId) || ''
    this.load()
  },
  methods: {
    async load() {
      this.loading = true
      this.error = ''
      try {
        // 计划名/状态（停用 → 空态）与 next-doses 并行；getPlan 非 403 失败不阻断时间线
        const [planRes, dosesRes] = await Promise.allSettled([
          getPlan(this.planId, { silent: true }),
          getNextDoses(this.planId, { days: this.days }, { silent: true }),
        ])
        if (planRes.status === 'fulfilled' && planRes.value) {
          this.planName = planRes.value.name || ''
          this.planStatus = planRes.value.status || ''
        } else if (
          planRes.status === 'rejected' &&
          planRes.reason &&
          planRes.reason.code === CODE.FORBIDDEN
        ) {
          throw planRes.reason
        }
        if (dosesRes.status === 'rejected') throw dosesRes.reason
        const raw = dosesRes.status === 'fulfilled' ? dosesRes.value : []
        const views = parseNextDoses(raw).map(toDoseView)
        this.groups = groupDosesByDay(views)
        this.loaded = true
      } catch (err) {
        this.error = errorText(err)
      } finally {
        this.loading = false
      }
    },
    changeDays(opt) {
      if (opt === this.days) return
      this.days = opt
      this.load()
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

.days {
  display: flex;
  gap: 16rpx;
  margin-bottom: $mb-gap;

  &__item {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    height: 72rpx;
    border-radius: $mb-radius-pill;
    background: $mb-card-bg;
    border: 2rpx solid $mb-border;

    &--on {
      background: $mb-primary-bg;
      border-color: $mb-primary;
    }
  }

  &__text {
    font-size: 28rpx;
    color: $mb-text-2;

    .days__item--on & {
      color: $mb-primary;
      font-weight: 500;
    }
  }
}

.dose {
  display: flex;
  align-items: flex-start;
  padding: 20rpx 0;
  border-bottom: 2rpx solid $mb-border;

  &:last-child {
    border-bottom: none;
    padding-bottom: 0;
  }

  &__time {
    flex: 0 0 120rpx;
    font-size: 34rpx;
    font-weight: 600;
    color: $mb-primary;
  }

  &__body {
    flex: 1;
    padding-left: 8rpx;
  }
}

.item {
  display: flex;
  align-items: center;
  padding: 6rpx 0;

  &__dot {
    width: 12rpx;
    height: 12rpx;
    margin-right: 16rpx;
    border-radius: 50%;
    background: $mb-primary;
    flex: 0 0 auto;
  }

  &__text {
    font-size: 28rpx;
    color: $mb-text;
  }

  &__empty {
    font-size: 26rpx;
    color: $mb-text-3;
  }
}
</style>
