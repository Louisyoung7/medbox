<template>
  <view class="page">
    <!-- 整页仅监护人可见；老人端不接触 Key（05 第 2.1 章 FP-8） -->
    <RoleGate entry="aiConfig">
      <view class="ai-hero">
        <view class="ai-hero__title">AI 大模型配置</view>
        <view class="ai-hero__sub">为被监护老人（{{ elderId || '—' }}）代配洞察层所用 Chat Key</view>
      </view>

      <MbCard title="Key 配置">
        <MbCell title="服务商">
          <picker slot="extra" mode="selector" :range="providers" range-key="label" @change="onProvider">
            <view class="picker">{{ providerLabel || '请选择服务商' }}</view>
          </picker>
        </MbCell>

        <MbField
          v-model="form.baseUrl"
          label="Base URL"
          placeholder="https://api.openai.com/v1"
        />
        <MbField
          v-model="form.model"
          label="模型"
          placeholder="gpt-4o-mini"
        />
        <MbField
          v-model="form.apiKey"
          label="API Key"
          type="password"
          placeholder="sk-..."
        />

        <MbCell title="启用" label="关闭后将退回 50310 降级提示">
          <switch slot="extra" :checked="form.enabled" @change="onEnabled" />
        </MbCell>

        <view v-if="maskedKey" class="masked">
          <text class="masked__tag">已配置</text>
          <text class="masked__value">{{ maskedKey }}</text>
        </view>
      </MbCard>

      <MbCard title="操作">
        <MbButton
          type="ghost"
          block
          :loading="testing"
          @click="onTest"
        >
          连通测试
        </MbButton>
        <MbButton
          type="primary"
          block
          :loading="saving"
          @click="onSave"
        >
          保存
        </MbButton>
        <MbButton
          type="danger"
          block
          :disabled="!maskedKey || saving || testing"
          @click="onDelete"
        >
          清除配置
        </MbButton>
        <view class="hint">Key 由后端加密存储，本端不留存明文；老人端不可见。</view>
      </MbCard>
    </RoleGate>

    <MbTabBar />
  </view>
</template>

<script>
import { getRole } from '@/store/user.js'
import { loadVisibleElderIds, getAlarmState } from '@/store/alarm.js'
import { AI_PROVIDERS, validateLlmConfig, maskApiKey, aiErrorMessage } from '@/utils/ai.js'
import {
  getLlmConfig,
  putLlmConfig,
  testLlmConfig,
  deleteLlmConfig,
} from '@/api/ai.js'
import MbCard from '@/components/MbCard.vue'
import MbField from '@/components/MbField.vue'
import MbCell from '@/components/MbCell.vue'
import MbButton from '@/components/MbButton.vue'
import RoleGate from '@/components/RoleGate.vue'
import MbTabBar from '@/components/MbTabBar.vue'

const DEFAULT_FORM = () => ({
  provider: 'openai-compatible',
  baseUrl: '',
  model: '',
  apiKey: '',
  enabled: true,
})

export default {
  name: 'AiConfig',
  components: { MbCard, MbField, MbCell, MbButton, RoleGate, MbTabBar },
  data() {
    return {
      elderId: '',
      providers: AI_PROVIDERS,
      form: DEFAULT_FORM(),
      maskedKey: '',
      saving: false,
      testing: false,
    }
  },
  computed: {
    providerLabel() {
      const p = this.providers.find((x) => x.value === this.form.provider)
      return p ? p.label : ''
    },
  },
  async onLoad() {
    await this.resolveElderId()
    await this.loadConfig()
  },
  methods: {
    /** 分析对象：监护人取可见老人首个（与洞察页一致）；老人不进入（RoleGate 已挡）。 */
    async resolveElderId() {
      if (getRole() === 'GUARDIAN') {
        await loadVisibleElderIds()
        const ids = (getAlarmState().elderIds || []).slice()
        this.elderId = ids[0] || ''
      } else {
        this.elderId = ''
      }
    },
    /** 进入页回填配置（只取脱敏 apiKeyMask，不取明文）。 */
    async loadConfig() {
      if (!this.elderId) return
      try {
        const cfg = await getLlmConfig({ elderId: this.elderId })
        this.form.provider = cfg.provider || 'openai-compatible'
        this.form.baseUrl = cfg.baseUrl || ''
        this.form.model = cfg.model || ''
        this.form.enabled = cfg.enabled
        this.maskedKey = cfg.apiKeyMask || ''
        // 不回填明文 apiKey —— 输入框留空
      } catch (err) {
        uni.showToast({ title: aiErrorMessage(err), icon: 'none' })
      }
    },
    onProvider(e) {
      const idx = Number(e.detail.value)
      if (this.providers[idx]) this.form.provider = this.providers[idx].value
    },
    onEnabled(e) {
      this.form.enabled = !!e.detail.value
    },
    /** 连通测试：05 第 2.1 章 POST /ai/config/test（不真正出网，Mock 模拟）。 */
    async onTest() {
      if (!this.elderId) return
      const needKey = !this.maskedKey
      const { valid, errors } = validateLlmConfig(this.form, { requireApiKey: needKey })
      if (!valid) {
        uni.showToast({
          title: errors.apiKey || errors.baseUrl || errors.model || errors.provider,
          icon: 'none',
        })
        return
      }
      this.testing = true
      try {
        const res = await testLlmConfig({
          elderId: this.elderId,
          provider: this.form.provider,
          baseUrl: this.form.baseUrl.trim(),
          model: this.form.model.trim(),
          apiKey: this.form.apiKey,
        })
        uni.showToast({
          title: res.ok ? '连通正常' : res.message || '连通失败',
          icon: res.ok ? 'success' : 'none',
        })
      } catch (err) {
        uni.showToast({ title: aiErrorMessage(err), icon: 'none' })
      } finally {
        this.testing = false
      }
    },
    /** 保存：PUT /ai/config（apiKey 已配置且未改则可为空，PUT 保留原 Key）。 */
    async onSave() {
      if (!this.elderId) return
      const { valid, errors } = validateLlmConfig(this.form, { requireApiKey: !this.maskedKey })
      if (!valid) {
        uni.showToast({
          title: errors.apiKey || errors.baseUrl || errors.model || errors.provider,
          icon: 'none',
        })
        return
      }
      this.saving = true
      try {
        const cfg = await putLlmConfig({
          elderId: this.elderId,
          provider: this.form.provider,
          baseUrl: this.form.baseUrl.trim(),
          model: this.form.model.trim(),
          apiKey: this.form.apiKey,
          enabled: this.form.enabled,
        })
        this.maskedKey = cfg.apiKeyMask || maskApiKey(this.form.apiKey)
        this.form.apiKey = '' // 保存后清空明文输入
        uni.showToast({ title: '已保存', icon: 'success' })
      } catch (err) {
        uni.showToast({ title: aiErrorMessage(err), icon: 'none' })
      } finally {
        this.saving = false
      }
    },
    /** 清除：DELETE /ai/config，二次确认。 */
    onDelete() {
      if (!this.elderId || !this.maskedKey) return
      uni.showModal({
        title: '清除配置',
        content: '确定清除该老人的 AI Key 配置？清除后洞察将退回"未开通"。',
        confirmColor: '#e5484d',
        success: async (r) => {
          if (!r.confirm) return
          try {
            await deleteLlmConfig({ elderId: this.elderId })
            this.form = DEFAULT_FORM()
            this.maskedKey = ''
            uni.showToast({ title: '已清除', icon: 'success' })
          } catch (err) {
            uni.showToast({ title: aiErrorMessage(err), icon: 'none' })
          }
        },
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
  background: $mb-bg;
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
    color: rgba(255, 255, 255, 0.9);
  }
}

.picker {
  font-size: 28rpx;
  color: $mb-primary;
}

.masked {
  display: flex;
  align-items: center;
  margin-top: 8rpx;
  padding: 16rpx 20rpx;
  background: $mb-primary-bg;
  border-radius: $mb-radius;

  &__tag {
    flex: none;
    margin-right: 16rpx;
    padding: 2rpx 12rpx;
    font-size: 22rpx;
    color: #fff;
    background: $mb-primary;
    border-radius: $mb-radius-sm;
  }

  &__value {
    font-size: 26rpx;
    color: $mb-text;
    letter-spacing: 1rpx;
  }
}

.hint {
  margin-top: 20rpx;
  font-size: 24rpx;
  color: $mb-text-3;
  line-height: 1.5;
}
</style>
