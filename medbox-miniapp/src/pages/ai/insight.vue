<template>
  <view class="page">
    <view class="ai-hero">
      <view class="ai-hero__title">AI 健康洞察</view>
      <view class="ai-hero__sub">分析对象：{{ elderId || '—' }}</view>
    </view>

    <!-- 维度选择 -->
    <MbCard title="分析维度">
      <view class="chips">
        <view
          v-for="t in aiTypes"
          :key="t.value"
          class="chip"
          :class="{ 'chip--active': type === t.value }"
          @click="selectType(t.value)"
        >
          {{ t.label }}
        </view>
      </view>
      <view class="chip-desc">{{ selectedTypeDesc }}</view>
    </MbCard>

    <!-- 时间范围 -->
    <MbCard title="时间范围">
      <view class="range-row">
        <view
          v-for="r in rangeOptions"
          :key="r.days"
          class="range-btn"
          :class="{ 'range-btn--active': rangeDays === r.days }"
          @click="selectRange(r.days)"
        >
          {{ r.label }}
        </view>
      </view>
      <view class="range-hint">最多 30 天（超出将被服务端拒绝）</view>
    </MbCard>

    <!-- 生成按钮 -->
    <MbButton
      type="primary"
      block
      :disabled="!canGenerate"
      :loading="streaming"
      @click="generate"
    >
      {{ streaming ? '生成中…' : '生成洞察' }}
    </MbButton>

    <!-- 结果区 -->
    <MbCard v-if="generated && !errorMsg" title="洞察结果">
      <view class="insight-content">{{ content || '（无内容）' }}</view>

      <view v-if="citations.length" class="citations">
        <view class="citations__title">数据出处（仅供参考，非结论）</view>
        <view v-for="(c, i) in citations" :key="i" class="citations__item">{{ c }}</view>
      </view>

      <view class="disclaimer">{{ disclaimer || '本分析仅供参考，用药与设备调整请由监护人 / 医生决策。' }}</view>
    </MbCard>

    <!-- 空态 -->
    <MbEmpty
      v-else-if="!errorMsg && !streaming"
      text="暂无洞察，先选择维度与时间范围后点击「生成洞察」"
    />

    <!-- 异常 / 未开通 -->
    <MbCard v-if="errorMsg" title="提示">
      <view class="error-text">{{ errorMsg }}</view>
    </MbCard>

    <!-- 周期洞察设置（仅监护人，05 第 2.3 章） -->
    <RoleGate entry="aiInsightSchedule">
      <MbCard title="周期洞察（自动推送）">
        <view class="schedule-row">
          <text class="schedule-label">开启周期洞察</text>
          <switch :checked="scheduleEnabled" :disabled="scheduleSaving" @change="onScheduleToggle" />
        </view>
        <view v-if="scheduleEnabled" class="range-row">
          <view
            v-for="f in frequencies"
            :key="f.value"
            class="range-btn"
            :class="{ 'range-btn--active': scheduleFrequency === f.value }"
            @click="onFrequency(f.value)"
          >
            {{ f.label }}
          </view>
        </view>
        <view class="range-hint">开启后，系统将按选定节奏经 WebSocket 推送综合洞察。</view>
      </MbCard>
    </RoleGate>

    <!-- AI 大模型 Key 配置（仅监护人，05 第 2.1 章 FP-8；老人端不接触 Key） -->
    <RoleGate entry="aiConfig">
      <MbCard title="AI 大模型 Key">
        <view class="range-hint">为被监护老人代配洞察层所用的 Chat Key（仅监护人可见，老人端不可见）。</view>
        <MbButton type="ghost" block @click="onOpenAiConfig">配置 AI Key</MbButton>
      </MbCard>
    </RoleGate>

    <MbTabBar />
  </view>
</template>

<script>
import MbCard from '@/components/MbCard.vue'
import MbButton from '@/components/MbButton.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import MbTabBar from '@/components/MbTabBar.vue'
import RoleGate from '@/components/RoleGate.vue'
import {
  streamInsight,
  getInsightSchedule,
  putInsightSchedule,
} from '@/api/ai.js'
import {
  AI_TYPES,
  SCHEDULE_FREQUENCIES,
  aiErrorMessage,
  isNoKeyError,
} from '@/utils/ai.js'
import { getUserId, getRole } from '@/store/user.js'
import { loadVisibleElderIds, getAlarmState } from '@/store/alarm.js'

export default {
  name: 'AiInsightPage',
  components: { MbCard, MbButton, MbEmpty, MbTabBar, RoleGate },
  data() {
    return {
      elderId: '',
      type: 'COMPREHENSIVE',
      rangeDays: 7,
      content: '',
      citations: [],
      disclaimer: '',
      streaming: false,
      generated: false,
      errorMsg: '',
      scheduleEnabled: false,
      scheduleFrequency: 'DAILY',
      scheduleSaving: false,
      aiTypes: AI_TYPES,
      rangeOptions: [
        { days: 7, label: '近 7 天' },
        { days: 14, label: '近 14 天' },
        { days: 30, label: '近 30 天' },
      ],
      frequencies: SCHEDULE_FREQUENCIES,
      streamHandle: null,
    }
  },
  computed: {
    isGuardian() {
      return getRole() === 'GUARDIAN'
    },
    selectedTypeDesc() {
      const hit = this.aiTypes.find((t) => t.value === this.type)
      return hit ? hit.desc : ''
    },
    canGenerate() {
      return !!this.elderId && !this.streaming
    },
  },
  onLoad() {
    this.resolveElderId()
  },
  onShow() {
    if (this.elderId && this.isGuardian) {
      this.loadSchedule()
    }
  },
  onUnload() {
    this.abortStream()
  },
  methods: {
    /** 解析分析对象：老人取自己；监护人取可见老人首个（复用 store/alarm 推导）。 */
    async resolveElderId() {
      const role = getRole()
      if (role === 'GUARDIAN') {
        try {
          const ids = await loadVisibleElderIds()
          const ids2 = ids && ids.length ? ids : getAlarmState().elderIds || []
          this.elderId = getAlarmState().currentElderId || ids2[0] || ''
        } catch (e) {
          this.elderId = ''
        }
      } else {
        this.elderId = getUserId()
      }
    },

    selectType(value) {
      if (this.streaming) return
      this.type = value
    },
    selectRange(days) {
      if (this.streaming) return
      this.rangeDays = days
    },

    /** 生成洞察（流式逐字）。先取消可能未完成的流，再重置结果区。 */
    generate() {
      if (!this.canGenerate) return
      this.abortStream()
      this.content = ''
      this.citations = []
      this.disclaimer = ''
      this.generated = false
      this.errorMsg = ''
      this.streaming = true
      this.streamHandle = streamInsight(
        { elderId: this.elderId, type: this.type, rangeDays: this.rangeDays },
        {
          onDelta: (c) => {
            this.content += c
          },
          onDone: (insight) => {
            this.content = insight.content || ''
            this.citations = insight.citations || []
            this.disclaimer = insight.disclaimer || ''
            this.generated = true
            this.streaming = false
          },
          onError: (err) => {
            this.streaming = false
            this.errorMsg = aiErrorMessage(err)
            if (isNoKeyError(err)) {
              this.generated = true
            }
          },
        },
      )
    },

    abortStream() {
      if (this.streamHandle && this.streamHandle.abort) {
        this.streamHandle.abort()
      }
      this.streamHandle = null
    },

    /** 拉取周期洞察设置（05 第 2.3 章 GET）。 */
    async loadSchedule() {
      try {
        const data = await getInsightSchedule({ elderId: this.elderId })
        this.scheduleEnabled = !!data.enabled
        if (data.frequency) this.scheduleFrequency = data.frequency
      } catch (e) {
        console.warn('[ai] 周期洞察设置读取失败', e && e.code ? e.code : e)
      }
    },

    /** 开关周期洞察（仅监护人；后端强校验 40301）。 */
    async onScheduleToggle(e) {
      const enabled = !!e.detail.value
      const prev = this.scheduleEnabled
      this.scheduleEnabled = enabled
      this.scheduleSaving = true
      try {
        await putInsightSchedule({
          elderId: this.elderId,
          frequency: this.scheduleFrequency,
          enabled,
        })
      } catch (err) {
        this.scheduleEnabled = prev
        uni.showToast({ title: aiErrorMessage(err), icon: 'none' })
      } finally {
        this.scheduleSaving = false
      }
    },

    /** 调整节奏（每日 / 每周）。 */
    async onFrequency(value) {
      if (this.scheduleFrequency === value || this.scheduleSaving) return
      const prev = this.scheduleFrequency
      this.scheduleFrequency = value
      this.scheduleSaving = true
      try {
        await putInsightSchedule({
          elderId: this.elderId,
          frequency: value,
          enabled: this.scheduleEnabled,
        })
      } catch (err) {
        this.scheduleFrequency = prev
        uni.showToast({ title: aiErrorMessage(err), icon: 'none' })
      } finally {
        this.scheduleSaving = false
      }
    },

    /** 进入 AI Key 配置页（仅监护人；05 第 2.1 章 FP-8）。 */
    onOpenAiConfig() {
      uni.navigateTo({ url: '/pages/ai/config' })
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

.ai-hero {
  padding: 36rpx 40rpx;
  margin-bottom: $mb-gap;
  border-radius: $mb-radius-lg;
  background: $mb-gradient;

  &__title {
    font-size: 38rpx;
    font-weight: 600;
    color: #fff;
  }

  &__sub {
    margin-top: 8rpx;
    font-size: 26rpx;
    color: rgba(255, 255, 255, 0.85);
  }
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}

.chip {
  padding: 14rpx 24rpx;
  border-radius: $mb-radius-sm;
  font-size: 26rpx;
  color: $mb-text-2;
  background: #f1f4f8;
  border: 1rpx solid transparent;

  &--active {
    color: #fff;
    background: $mb-primary;
    border-color: $mb-primary;
  }
}

.chip-desc {
  margin-top: 16rpx;
  font-size: 24rpx;
  line-height: 1.6;
  color: $mb-text-3;
}

.range-row {
  display: flex;
  gap: 16rpx;
}

.range-btn {
  flex: 1;
  text-align: center;
  padding: 16rpx 0;
  border-radius: $mb-radius-sm;
  font-size: 26rpx;
  color: $mb-text-2;
  background: #f1f4f8;

  &--active {
    color: #fff;
    background: $mb-primary;
  }
}

.range-hint {
  margin-top: 16rpx;
  font-size: 24rpx;
  color: $mb-text-3;
}

.insight-content {
  font-size: 28rpx;
  line-height: 1.8;
  color: $mb-text;
  white-space: pre-wrap;
}

.citations {
  margin-top: 24rpx;
  padding-top: 20rpx;
  border-top: 1rpx solid #eef1f5;

  &__title {
    font-size: 24rpx;
    color: $mb-text-3;
    margin-bottom: 12rpx;
  }

  &__item {
    font-size: 24rpx;
    line-height: 1.7;
    color: $mb-text-2;
    font-family: monospace;
  }
}

.disclaimer {
  margin-top: 24rpx;
  padding: 18rpx 20rpx;
  border-radius: $mb-radius-sm;
  background: $mb-primary-bg;
  font-size: 24rpx;
  line-height: 1.6;
  color: $mb-text-2;
}

.error-text {
  font-size: 26rpx;
  line-height: 1.7;
  color: $mb-danger;
}

.schedule-row {
  display: flex;
  align-items: center;
  justify-content: space-between;

  .schedule-label {
    font-size: 28rpx;
    color: $mb-text;
  }
}
</style>
