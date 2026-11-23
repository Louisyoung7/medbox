<template>
  <view class="page">
    <RoleGate entry="alarmSettings">
      <view v-if="isGuardian" class="picker-row">
        <text class="picker-label">老人</text>
        <picker class="picker" mode="selector" :range="elderOptions" range-key="label" @change="onElderChange">
          <view class="picker__value">{{ currentElderLabel }}</view>
        </picker>
      </view>

      <MbCard title="提醒与容忍">
        <view class="field">
          <text class="field__label">服药提醒提前期（分钟）</text>
          <input class="field__input" type="digit" v-model="form.advanceMin" placeholder="如 10" />
        </view>
        <view class="field">
          <text class="field__label">漏服容忍（分钟，老人级）</text>
          <input class="field__input" type="digit" v-model="form.missTolerateMin" placeholder="如 15" />
        </view>
      </MbCard>

      <MbCard title="环境阈值（温 / 湿 / 光 上下限）">
        <view class="env">
          <text class="env__label">温度（℃）</text>
          <view class="env__row">
            <input class="field__input field__input--half" type="digit" v-model="form.tempMin" placeholder="下限" />
            <text class="env__sep">~</text>
            <input class="field__input field__input--half" type="digit" v-model="form.tempMax" placeholder="上限" />
          </view>
        </view>
        <view class="env">
          <text class="env__label">湿度（%）</text>
          <view class="env__row">
            <input class="field__input field__input--half" type="digit" v-model="form.humidityMin" placeholder="下限" />
            <text class="env__sep">~</text>
            <input class="field__input field__input--half" type="digit" v-model="form.humidityMax" placeholder="上限" />
          </view>
        </view>
        <view class="env">
          <text class="env__label">光照（lux）</text>
          <view class="env__row">
            <input class="field__input field__input--half" type="digit" v-model="form.lightMin" placeholder="下限" />
            <text class="env__sep">~</text>
            <input class="field__input field__input--half" type="digit" v-model="form.lightMax" placeholder="上限" />
          </view>
        </view>
      </MbCard>

      <view class="actions">
        <MbButton type="primary" :loading="saving" @click="onSave">保存设置</MbButton>
        <text v-if="hint" class="hint" :class="hintClass">{{ hint }}</text>
      </view>
    </RoleGate>

    <MbEmpty v-if="!isGuardian" text="仅监护人可配置告警设置" />
  </view>
</template>

<script>
import MbCard from '@/components/MbCard.vue'
import MbButton from '@/components/MbButton.vue'
import RoleGate from '@/components/RoleGate.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import { getAlarmSettings, saveAlarmSettings } from '@/api/alarm.js'
import { CODE } from '@/api/request.js'
import { toAlarmSettingsView } from '@/utils/alarm.js'
import { getUserInfo } from '@/store/user.js'
import {
  getAlarmState,
  setCurrentElderId,
  loadVisibleElderIds,
} from '@/store/alarm.js'

export default {
  name: 'AlarmSettingsPage',
  components: { MbCard, MbButton, RoleGate, MbEmpty },
  data() {
    return {
      isGuardian: false,
      elderId: '',
      loading: false,
      saving: false,
      hint: '',
      form: {
        advanceMin: '',
        missTolerateMin: '',
        tempMin: '',
        tempMax: '',
        humidityMin: '',
        humidityMax: '',
        lightMin: '',
        lightMax: '',
      },
    }
  },
  computed: {
    elderOptions() {
      const ids = getAlarmState().elderIds || []
      return ids.map((id) => ({ value: id, label: `老人 ${id}` }))
    },
    currentElderLabel() {
      const id = getAlarmState().currentElderId || this.elderId
      return id ? `老人 ${id}` : '请选择老人'
    },
    hintClass() {
      return this.hint && this.hint.indexOf('成功') >= 0 ? 'hint--ok' : 'hint--err'
    },
  },
  onLoad() {
    const info = getUserInfo()
    this.isGuardian = !!(info && info.role === 'GUARDIAN')
    if (!this.isGuardian) return
    const ids = getAlarmState().elderIds
    if (!ids || !ids.length) {
      loadVisibleElderIds().then(() => {
        this.elderId = getAlarmState().currentElderId || ''
        this.reload()
      })
    } else {
      this.elderId = getAlarmState().currentElderId || ids[0]
      this.reload()
    }
  },
  methods: {
    async reload() {
      if (!this.elderId) return
      this.loading = true
      this.hint = ''
      try {
        const data = await getAlarmSettings({ elderId: this.elderId })
        const view = toAlarmSettingsView(data)
        this.form = {
          advanceMin: String(view.advanceMin),
          missTolerateMin: String(view.missTolerateMin),
          tempMin: String(view.envThreshold.temp.min),
          tempMax: String(view.envThreshold.temp.max),
          humidityMin: String(view.envThreshold.humidity.min),
          humidityMax: String(view.envThreshold.humidity.max),
          lightMin: String(view.envThreshold.light.min),
          lightMax: String(view.envThreshold.light.max),
        }
      } catch (err) {
        this.hint =
          err && err.code === CODE.FORBIDDEN ? '无权限访问该资源' : (err && err.message) || '加载失败'
      } finally {
        this.loading = false
      }
    },
    onElderChange(e) {
      const id = this.elderOptions[Number(e.detail.value)].value
      this.elderId = id
      setCurrentElderId(id)
      this.reload()
    },
    num(v) {
      const n = Number(v)
      return Number.isFinite(n) ? n : undefined
    },
    async onSave() {
      if (!this.elderId) return
      this.saving = true
      this.hint = ''
      const body = {
        advanceMin: this.num(this.form.advanceMin),
        missTolerateMin: this.num(this.form.missTolerateMin),
        envThreshold: {
          temp: { min: this.num(this.form.tempMin), max: this.num(this.form.tempMax) },
          humidity: { min: this.num(this.form.humidityMin), max: this.num(this.form.humidityMax) },
          light: { min: this.num(this.form.lightMin), max: this.num(this.form.lightMax) },
        },
      }
      try {
        await saveAlarmSettings({ elderId: this.elderId }, body)
        this.hint = '保存成功'
      } catch (err) {
        this.hint =
          err && err.code === CODE.FORBIDDEN ? '无权限执行该操作' : (err && err.message) || '保存失败'
      } finally {
        this.saving = false
      }
    },
  },
}
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 48rpx;
  box-sizing: border-box;
  background: $mb-bg;
}

.picker-row {
  display: flex;
  align-items: center;
  padding: 16rpx 24rpx;
  margin-bottom: 16rpx;
  border-radius: $mb-radius;
  background: #fff;
}

.picker-label {
  font-size: 26rpx;
  color: $mb-text-3;
  margin-right: 16rpx;
}

.picker {
  flex: 1;

  &__value {
    font-size: 28rpx;
    font-weight: 600;
    color: $mb-text-2;
  }
}

.field {
  padding: 16rpx 0;

  &__label {
    display: block;
    font-size: 26rpx;
    color: $mb-text-3;
    margin-bottom: 12rpx;
  }

  &__input {
    width: 100%;
    height: 80rpx;
    padding: 0 24rpx;
    box-sizing: border-box;
    border-radius: $mb-radius;
    background: $mb-bg;
    font-size: 28rpx;
    color: $mb-text-2;
    border: none;

    &--half {
      width: 240rpx;
    }
  }
}

.env {
  padding: 16rpx 0;

  &__label {
    display: block;
    font-size: 26rpx;
    color: $mb-text-3;
    margin-bottom: 12rpx;
  }

  &__row {
    display: flex;
    align-items: center;
  }

  &__sep {
    margin: 0 16rpx;
    color: $mb-text-4;
  }
}

.actions {
  margin-top: 24rpx;
  display: flex;
  flex-wrap: wrap;
  gap: 20rpx;
  align-items: center;
}

.hint {
  font-size: 24rpx;

  &--ok {
    color: $mb-success;
  }

  &--err {
    color: $mb-danger;
  }
}
</style>
