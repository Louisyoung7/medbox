<template>
  <!-- 实时告警（ALARM 帧）：居中卡片，按级别着色，可点"查看"跳列表 -->
  <view v-if="alarmVisible" class="popup">
    <view class="popup__mask" @click="closeAlarm" />
    <view class="popup__card" :class="levelClass(alarmLevel)">
      <view class="popup__head">
        <text class="popup__title">{{ alarmTitle }}</text>
        <text class="popup__close" @click="closeAlarm">×</text>
      </view>
      <text class="popup__msg">{{ alarmMessage }}</text>
      <view class="popup__actions">
        <view class="popup__btn popup__btn--ghost" @click="closeAlarm">
          <text>稍后</text>
        </view>
        <view class="popup__btn popup__btn--primary" @click="goList">
          <text>查看</text>
        </view>
      </view>
    </view>
  </view>

  <!-- 服药提醒（REMINDER 帧，非告警）：多药列表 -->
  <view v-if="reminderVisible" class="popup">
    <view class="popup__mask" @click="closeReminder" />
    <view class="popup__card popup__card--reminder">
      <view class="popup__head">
        <text class="popup__title">服药提醒</text>
        <text class="popup__close" @click="closeReminder">×</text>
      </view>
      <view class="reminder-meds">
        <view
          v-for="(m, i) in reminderMeds"
          :key="m.itemId || m.medicineId || i"
          class="reminder-med"
        >
          <text class="reminder-med__name">{{ m.medicineName || m.medicineId || '药品' }}</text>
          <text class="reminder-med__dose">{{ m.dose }}{{ m.unit }}</text>
        </view>
        <text v-if="!reminderMeds.length" class="reminder-empty">该提醒暂无药品明细</text>
      </view>
      <view class="popup__actions">
        <view class="popup__btn popup__btn--primary" @click="closeReminder">
          <text>知道了</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import { WS_EVENT, onEvent } from '@/store/ws.js'
import { bumpUnreadCount } from '@/store/alarm.js'
import { alarmTypeLabel, alarmLevelLabel } from '@/utils/alarm.js'

export default {
  name: 'AlarmRealtime',
  data() {
    return {
      alarmVisible: false,
      alarmTitle: '',
      alarmMessage: '',
      alarmLevel: '',
      reminderVisible: false,
      reminderMeds: [],
      offAlarm: null,
      offReminder: null,
    }
  },
  mounted() {
    // 唯一长连接只在 src/store/ws.js；此处只订阅，不自己建 socket
    this.offAlarm = onEvent(WS_EVENT.ALARM, (frame) => this.onAlarm(frame))
    this.offReminder = onEvent(WS_EVENT.REMINDER, (frame) => this.onReminder(frame))
  },
  beforeUnmount() {
    if (this.offAlarm) this.offAlarm()
    if (this.offReminder) this.offReminder()
  },
  methods: {
    onAlarm(frame) {
      const p = (frame && frame.payload) || {}
      this.alarmTitle = alarmTypeLabel(p.type) || '告警'
      if (p.level) this.alarmTitle += ' · ' + alarmLevelLabel(p.level)
      this.alarmMessage = p.message || '收到一条告警'
      this.alarmLevel = p.level || ''
      this.alarmVisible = true
      bumpUnreadCount(1)
    },
    onReminder(frame) {
      const p = (frame && frame.payload) || {}
      this.reminderMeds = Array.isArray(p.medicines) ? p.medicines : []
      this.reminderVisible = true
    },
    levelClass(level) {
      if (level === 'CRITICAL') return 'popup__card--critical'
      if (level === 'WARN') return 'popup__card--warn'
      return 'popup__card--info'
    },
    closeAlarm() {
      this.alarmVisible = false
    },
    closeReminder() {
      this.reminderVisible = false
    },
    goList() {
      this.alarmVisible = false
      uni.navigateTo({ url: '/pages/alarm/list' })
    },
  },
}
</script>

<style lang="scss" scoped>
.popup {
  position: fixed;
  inset: 0;
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: center;

  &__mask {
    position: absolute;
    inset: 0;
    background: rgba(0, 0, 0, 0.45);
  }

  &__card {
    position: relative;
    width: 560rpx;
    max-height: 70vh;
    overflow: hidden;
    padding: 32rpx;
    box-sizing: border-box;
    background: #fff;
    border-radius: 24rpx;
    border-left: 8rpx solid #1a73e8;
    box-shadow: 0 12rpx 40rpx rgba(0, 0, 0, 0.18);

    &--warn {
      border-left-color: #fb8c00;
    }

    &--critical {
      border-left-color: #e53935;
    }

    &--reminder {
      border-left-color: #1a73e8;
    }
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 16rpx;
  }

  &__title {
    font-size: 32rpx;
    font-weight: 600;
    color: #1f2329;
  }

  &__close {
    font-size: 40rpx;
    line-height: 1;
    color: #6b7280;
    padding: 0 8rpx;
  }

  &__msg {
    display: block;
    font-size: 28rpx;
    line-height: 1.6;
    color: #1f2329;
    margin-bottom: 24rpx;
  }

  &__actions {
    display: flex;
    gap: 20rpx;
    justify-content: flex-end;
  }

  &__btn {
    min-width: 160rpx;
    height: 72rpx;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 36rpx;
    font-size: 28rpx;

    &--ghost {
      background: #f5f7fa;
      color: #6b7280;
    }

    &--primary {
      background: #1a73e8;
      color: #fff;
    }
  }
}

.reminder-meds {
  margin-bottom: 24rpx;
}

.reminder-med {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12rpx 0;
  border-bottom: 1rpx solid #eef0f3;

  &__name {
    font-size: 28rpx;
    color: #1f2329;
  }

  &__dose {
    font-size: 26rpx;
    color: #1a73e8;
  }
}

.reminder-empty {
  display: block;
  font-size: 26rpx;
  color: #6b7280;
  padding: 12rpx 0;
}
</style>
