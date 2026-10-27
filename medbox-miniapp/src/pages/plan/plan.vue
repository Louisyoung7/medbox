<template>
  <view class="page">
    <view class="head">
      <text class="head__title">服药计划</text>
      <text class="head__sub">提醒时间、药品、剂量与备注</text>
    </view>

    <!-- 老人选择器：监护人可见；候选来自可见设备的 elderId（P1 mp-guardian 前无老人列表接口，同 mp-stock 模式）。
         老人端为只读本人的计划，显示静态文案。 -->
    <scroll-view v-if="canWrite && elders.length > 1" class="elders" scroll-x :show-scrollbar="false">
      <view
        v-for="elder in elders"
        :key="elder"
        class="elders__item"
        :class="{ 'elders__item--on': elder === elderId }"
        @click="selectElder(elder)"
      >
        <text class="elders__text">{{ elder }}</text>
      </view>
    </scroll-view>
    <view v-else-if="!canWrite" class="elders-tip">我的计划（只读）</view>

    <view v-if="summaryVisible" class="summary">共 {{ total }} 个计划</view>

    <MbLoading v-if="loading && plans.length === 0" fullscreen text="加载中" />

    <MbEmpty v-else-if="error" :text="error" />

    <MbEmpty v-else-if="!loading && plans.length === 0" text="暂无计划" />

    <template v-else>
      <MbCard v-for="plan in plans" :key="plan.planId">
        <MbCell
          :title="plan.name || '未命名计划'"
          :label="planLabel(plan)"
          clickable
          arrow
          @click="goEdit(plan)"
        >
          <template #extra>
            <view v-if="plan.syncState" class="sync">
              <text class="sync__text">{{ syncStateLabel(plan.syncState) }}</text>
            </view>
          </template>
        </MbCell>
        <view class="switch-row">
          <text class="switch-row__label">启用提醒</text>
          <switch
            :checked="plan.status === 'ENABLED'"
            :disabled="!canWrite"
            color="#0E9F8E"
            @change="onToggle(plan, $event)"
          />
        </view>
        <view class="timeline-row" @click="goTimeline(plan)">
          <text class="timeline-row__label">未来服药时间线</text>
          <text class="timeline-row__arrow">›</text>
        </view>
      </MbCard>
    </template>

    <view v-if="canWrite" class="create">
      <MbButton type="primary" block @click="goCreate">新建计划</MbButton>
    </view>

    <MbTabBar />
  </view>
</template>

<script>
import MbButton from '@/components/MbButton.vue'
import MbCard from '@/components/MbCard.vue'
import MbCell from '@/components/MbCell.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import MbLoading from '@/components/MbLoading.vue'
import MbTabBar from '@/components/MbTabBar.vue'
import { listDevices } from '@/api/device.js'
import { listMedicines } from '@/api/medicine.js'
import { listPlans, updatePlanStatus } from '@/api/plan.js'
import { CODE } from '@/api/request.js'
import { parseMedicinePage } from '@/utils/medicine.js'
import { toMedicineMap } from '@/utils/stock.js'
import {
  canWritePlan,
  parsePlansPage,
  planItemsText,
  planTimesText,
  repeatRuleLabel,
  syncStateLabel,
} from '@/utils/plan.js'
import { getRole, getUserId } from '@/store/user.js'

/** 列表错误文案按 code 细分（请求已 silent，页面统一自绘）。 */
function errorText(err) {
  if (err && err.code === CODE.FORBIDDEN) return '无权访问或该老人不存在'
  if (err && err.code === CODE.GUARDIAN_PENDING) return '监护关系未生效，请等待老人确认'
  return (err && err.message) || '加载失败'
}

export default {
  name: 'PlanPage',
  components: { MbButton, MbCard, MbCell, MbEmpty, MbLoading, MbTabBar },
  data() {
    return {
      canWrite: false,
      myUserId: '',
      elders: [],
      elderId: '',
      plans: [],
      total: 0,
      medicineMap: {},
      loading: false,
      error: '',
      loaded: false,
    }
  },
  computed: {
    summaryVisible() {
      return !this.error && this.total > 0
    },
  },
  onLoad() {
    this.canWrite = canWritePlan(getRole())
    this.myUserId = getUserId()
    // 老人端只看自己；监护人端先归纳候选老人再加载
    this.elderId = this.canWrite ? '' : this.myUserId
    this.load()
  },
  onShow() {
    // 从新建 / 编辑页返回时刷新（启停与明细都可能已变）
    if (this.loaded) this.load()
  },
  methods: {
    syncStateLabel,
    async load() {
      if (!this.elderId) {
        await this.loadElders()
        if (!this.elderId) {
          this.error = '暂无可查看的老人，请先为其登记药箱设备'
          return
        }
      }
      this.loading = true
      this.error = ''
      try {
        const data = await listPlans({ elderId: this.elderId, page: 1, size: 20 }, { silent: true })
        const page = parsePlansPage(data)
        this.plans = page.list
        this.total = page.total
        this.medicineMap = toMedicineMap(await this.loadMedicines())
        this.loaded = true
      } catch (err) {
        this.error = errorText(err)
      } finally {
        this.loading = false
      }
    },
    async loadElders() {
      try {
        const devices = await listDevices(undefined, { silent: true })
        const elders = []
        ;(devices || []).forEach((device) => {
          if (device.elderId && elders.indexOf(device.elderId) === -1) elders.push(device.elderId)
        })
        this.elders = elders
        if (!this.elderId && elders.length) this.elderId = elders[0]
      } catch (err) {
        // 拉不到设备不阻塞：老人端仍可按自身 elderId 加载
        this.elders = []
      }
    },
    async loadMedicines() {
      try {
        // 药品概览 join：字典拉不到不阻塞列表（planItemsText 回退显示 medicineId）
        const page = parseMedicinePage(await listMedicines({ page: 1, size: 100 }, { silent: true }))
        return page.list
      } catch (err) {
        return []
      }
    },
    selectElder(elder) {
      if (elder === this.elderId) return
      this.elderId = elder
      this.load()
    },
    planLabel(plan) {
      const parts = [planTimesText(plan.times), repeatRuleLabel(plan.repeatRule)]
      parts.push(planItemsText(plan.items, this.medicineMap))
      return parts.join(' · ')
    },
    async onToggle(plan, e) {
      const checked = !!(e && e.detail && e.detail.value)
      const next = checked ? 'ENABLED' : 'DISABLED'
      if ((plan.status === 'ENABLED') === checked) return
      try {
        const updated = await updatePlanStatus(plan.planId, next, { silent: true })
        // 局部生效：以响应为准，缺省沿用请求值
        plan.status = (updated && updated.status) || next
        if (updated && updated.syncState) plan.syncState = updated.syncState
      } catch (err) {
        uni.showToast({
          title:
            err && err.code === CODE.GUARDIAN_PENDING
              ? '监护关系未生效，请等待老人确认'
              : (err && err.message) || '操作失败',
          icon: 'none',
        })
        // switch 由 :checked 驱动，重拉列表让其回弹
        this.load()
      }
    },
    goEdit(plan) {
      uni.navigateTo({
        url: `/pages/plan/edit?planId=${encodeURIComponent(plan.planId)}` +
          `&elderId=${encodeURIComponent(this.elderId)}`,
      })
    },
    goCreate() {
      uni.navigateTo({ url: `/pages/plan/edit?elderId=${encodeURIComponent(this.elderId)}` })
    },
    goTimeline(plan) {
      uni.navigateTo({
        url: `/pages/plan/timeline?planId=${encodeURIComponent(plan.planId)}` +
          `&elderId=${encodeURIComponent(this.elderId)}`,
      })
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

.elders {
  white-space: nowrap;
  margin-bottom: $mb-gap;

  &__item {
    display: inline-flex;
    align-items: center;
    height: 56rpx;
    padding: 0 24rpx;
    margin-right: 16rpx;
    border-radius: $mb-radius-pill;
    background: $mb-card-bg;
    border: 2rpx solid $mb-border;

    &--on {
      background: $mb-primary-bg;
      border-color: $mb-primary;
    }
  }

  &__text {
    font-size: 26rpx;
    color: $mb-text-2;
  }

  &__item--on &__text {
    color: $mb-primary;
    font-weight: 500;
  }
}

.elders-tip {
  margin-bottom: $mb-gap;
  font-size: 26rpx;
  color: $mb-text-3;
}

.summary {
  margin-bottom: 16rpx;
  font-size: 26rpx;
  color: $mb-text-3;
}

.sync {
  display: inline-flex;
  align-items: center;
  height: 40rpx;
  padding: 0 16rpx;
  border-radius: $mb-radius-pill;
  background: $mb-primary-bg;

  &__text {
    font-size: 22rpx;
    color: $mb-primary;
  }
}

.switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 16rpx;
  padding-top: 20rpx;
  border-top: 2rpx solid $mb-border;

  &__label {
    font-size: 28rpx;
    color: $mb-text-2;
  }
}

.timeline-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 16rpx;
  padding-top: 20rpx;
  border-top: 2rpx solid $mb-border;

  &__label {
    font-size: 28rpx;
    color: $mb-text-2;
  }

  &__arrow {
    font-size: 32rpx;
    color: $mb-text-3;
  }
}

.create {
  margin-top: $mb-gap;
}
</style>
