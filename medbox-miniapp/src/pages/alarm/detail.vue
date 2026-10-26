<template>
  <view class="page">
    <MbCard title="告警详情">
      <view class="row">
        <text class="row__label">类型</text>
        <text class="row__value" :class="alarm.typeClass">{{ alarm.typeLabel }}</text>
      </view>
      <view class="row">
        <text class="row__label">级别</text>
        <text class="row__value" :class="alarm.levelClass">{{ alarm.levelLabel }}</text>
      </view>
      <view class="row">
        <text class="row__label">状态</text>
        <text class="row__value">{{ alarm.isUnread ? '未读' : '已读' }}{{ alarm.handled ? ' · 已处理' : '' }}</text>
      </view>
      <view class="row">
        <text class="row__label">时间</text>
        <text class="row__value">{{ formatDateTime(alarm.createdAt) }}</text>
      </view>
      <view class="msg">
        <text class="msg__text">{{ alarm.message }}</text>
      </view>
      <view v-if="alarm.medicineCount" class="meds">
        <text class="meds__label">关联药品（{{ alarm.medicineCount }}）</text>
        <view class="meds__chips">
          <text v-for="m in alarm.medicineIds" :key="m" class="meds__chip">{{ m }}</text>
        </view>
      </view>
    </MbCard>

    <view class="actions">
      <MbButton
        v-if="alarm.isUnread"
        type="ghost"
        :loading="acting === 'read'"
        @click="onMarkRead"
      >标记已读</MbButton>
      <RoleGate entry="alarmHandle">
        <MbButton
          v-if="!alarm.handled"
          type="primary"
          :loading="acting === 'handle'"
          @click="onHandle"
        >处理 / 确认</MbButton>
      </RoleGate>
      <text v-if="hint" class="hint" :class="hintClass">{{ hint }}</text>
    </view>
  </view>
</template>

<script>
import MbCard from '@/components/MbCard.vue'
import MbButton from '@/components/MbButton.vue'
import RoleGate from '@/components/RoleGate.vue'
import { markAlarmRead, handleAlarm } from '@/api/alarm.js'
import { CODE } from '@/api/request.js'
import { formatDateTime } from '@/utils/datetime.js'
import { refreshUnreadCount } from '@/store/alarm.js'

export default {
  name: 'AlarmDetailPage',
  components: { MbCard, MbButton, RoleGate },
  data() {
    return {
      alarm: {},
      acting: '',
      hint: '',
    }
  },
  computed: {
    hintClass() {
      return this.hint && this.hint.indexOf('成功') >= 0 ? 'hint--ok' : 'hint--err'
    },
  },
  onLoad(options) {
    try {
      this.alarm = options && options.alarm ? JSON.parse(decodeURIComponent(options.alarm)) : {}
    } catch (e) {
      this.alarm = {}
    }
  },
  methods: {
    formatDateTime,
    async onMarkRead() {
      if (!this.alarm.alarmId) return
      this.acting = 'read'
      this.hint = ''
      try {
        await markAlarmRead(this.alarm.alarmId)
        this.alarm.readState = 'READ'
        this.alarm.isUnread = false
        await refreshUnreadCount()
        this.hint = '标记已读成功'
      } catch (err) {
        this.hint =
          err && err.code === CODE.FORBIDDEN ? '无权限执行该操作' : (err && err.message) || '操作失败'
      } finally {
        this.acting = ''
      }
    },
    async onHandle() {
      if (!this.alarm.alarmId) return
      this.acting = 'handle'
      this.hint = ''
      try {
        await handleAlarm(this.alarm.alarmId)
        this.alarm.handled = true
        this.alarm.readState = 'READ'
        this.alarm.isUnread = false
        await refreshUnreadCount()
        this.hint = '处理成功'
      } catch (err) {
        this.hint =
          err && err.code === CODE.FORBIDDEN ? '无权限执行该操作' : (err && err.message) || '操作失败'
      } finally {
        this.acting = ''
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

.row {
  display: flex;
  align-items: center;
  padding: 16rpx 0;
  border-bottom: 1rpx solid $mb-border;

  &__label {
    flex: 0 0 120rpx;
    font-size: 26rpx;
    color: $mb-text-3;
  }

  &__value {
    flex: 1;
    font-size: 28rpx;
    color: $mb-text-2;

    &.alarm-type--critical {
      color: $mb-danger;
    }

    &.alarm-type--warn {
      color: #fb8c00;
    }

    &.alarm-type--info {
      color: $mb-primary;
    }
  }
}

.msg {
  margin-top: 20rpx;

  &__text {
    font-size: 28rpx;
    line-height: 1.7;
    color: $mb-text-2;
  }
}

.meds {
  margin-top: 20rpx;

  &__label {
    font-size: 26rpx;
    color: $mb-text-3;
  }

  &__chips {
    display: flex;
    flex-wrap: wrap;
    gap: 12rpx;
    margin-top: 12rpx;
  }

  &__chip {
    padding: 6rpx 18rpx;
    border-radius: $mb-radius-pill;
    background: $mb-primary-bg;
    color: $mb-primary;
    font-size: 24rpx;
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
