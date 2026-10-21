<template>
  <view class="page">
    <MbLoading v-if="loading" fullscreen text="加载中" />

    <MbEmpty v-else-if="error" :text="error" />

    <template v-else-if="record">
      <MbCard title="计划信息">
        <MbCell title="计划" :value="recordTitle(record)" />
        <MbCell title="药品" :value="recordMedicineText(record)" />
        <MbCell v-if="record.deviceId" title="药箱设备" :value="record.deviceId" />
        <MbCell title="计划时间" :value="formatPlanTime(record)" />
        <MbCell title="实际时间" :value="formatActualTime(record)" />
        <MbCell
          v-if="record.actualDose !== undefined && record.actualDose !== null && record.actualDose !== ''"
          title="实际剂量"
          :value="actualDoseText(record)"
        >
          <template #extra>
            <view class="tags">
              <text
                v-for="tag in record.tags"
                :key="tag.text"
                class="tag"
                :class="`tag--${tag.level}`"
              >{{ tag.text }}</text>
            </view>
          </template>
        </MbCell>
      </MbCard>

      <!-- 抓拍留证：capture-core 属 P1，本期未落地，缩略图区域留空占位（feat/mp-capture 接入） -->
      <MbCard title="抓拍留证">
        <view class="capture">
          <view class="capture__box">
            <text class="capture__icon">📷</text>
          </view>
          <text class="capture__text">
            {{ record.captureId ? '抓拍留证（待 P1 mp-capture 接入）' : '暂无抓拍留证' }}
          </text>
        </view>
      </MbCard>

      <view v-if="canWrite" class="actions">
        <MbButton type="primary" block :loading="confirming" @click="showConfirm = true">
          补录 / 确认
        </MbButton>
      </view>
    </template>

    <MbModal
      v-model="showConfirm"
      title="补录 / 确认"
      confirm-text="确认提交"
      :confirm-loading="confirming"
      :close-on-confirm="false"
      @confirm="onConfirm"
    >
      <view class="confirm-body">
        <text class="confirm-body__text">确认本记录为实际服药情况（设备异常时可手工校准）。</text>
        <MbField
          v-model="note"
          label="备注（选填）"
          type="textarea"
          placeholder="如：老人实际于 21:10 服药，剂量无误"
        />
        <text v-if="confirmError" class="confirm-body__err">{{ confirmError }}</text>
      </view>
    </MbModal>

    <MbTabBar />
  </view>
</template>

<script>
import MbButton from '@/components/MbButton.vue'
import MbCard from '@/components/MbCard.vue'
import MbCell from '@/components/MbCell.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import MbField from '@/components/MbField.vue'
import MbLoading from '@/components/MbLoading.vue'
import MbModal from '@/components/MbModal.vue'
import MbTabBar from '@/components/MbTabBar.vue'
import { confirmRecord, getRecord } from '@/api/record.js'
import { CODE } from '@/api/request.js'
import {
  canWriteRecord,
  recordStatusTags,
  recordTitle,
  recordMedicineText,
  formatPlanTime,
  formatActualTime,
} from '@/utils/record.js'
import { getRole } from '@/store/user.js'

/** 详情加载失败文案按 code 细分（请求已 silent）。 */
function errorText(err) {
  if (err && err.code === CODE.FORBIDDEN) return '无权访问或记录不存在'
  if (err && err.code === CODE.NOT_FOUND) return '记录不存在或已删除'
  if (err && err.code === CODE.GUARDIAN_PENDING) return '监护关系未生效，请等待老人确认'
  return (err && err.message) || '加载失败'
}

/** 补录 / 确认失败文案。 */
function confirmErrorText(err) {
  if (err && err.code === CODE.FORBIDDEN) return '无权限操作（仅监护人可补录）'
  if (err && err.code === CODE.GUARDIAN_PENDING) return '监护关系未生效，请等待老人确认'
  if (err && err.code === CODE.NOT_FOUND) return '记录不存在或已删除'
  return (err && err.message) || '提交失败，请稍后再试'
}

export default {
  name: 'RecordDetailPage',
  components: { MbButton, MbCard, MbCell, MbEmpty, MbField, MbLoading, MbModal, MbTabBar },
  data() {
    return {
      recordId: '',
      elderId: '',
      record: null,
      loading: false,
      error: '',
      canWrite: false,
      showConfirm: false,
      note: '',
      confirming: false,
      confirmError: '',
    }
  },
  onLoad(options) {
    this.recordId = (options && options.recordId) || ''
    this.elderId = (options && options.elderId) || ''
    this.canWrite = canWriteRecord(getRole())
    this.load()
  },
  onShow() {
    // 从确认弹窗提交后由本页自行刷新，无需重复；保留钩子以兼容返回刷新
    if (this.record && !this.showConfirm) this.load()
  },
  methods: {
    recordTitle,
    recordMedicineText,
    formatPlanTime,
    formatActualTime,
    actualDoseText(record) {
      const r = record || {}
      const unit = r.medicineUnit || ''
      return [r.actualDose, unit].filter(Boolean).join(' ') || '-'
    },
    async load() {
      if (!this.recordId) {
        this.error = '缺少记录编号'
        return
      }
      this.loading = true
      this.error = ''
      try {
        const data = await getRecord(this.recordId, { silent: true })
        this.record = { ...data, tags: recordStatusTags(data) }
      } catch (err) {
        this.error = errorText(err)
      } finally {
        this.loading = false
      }
    },
    onConfirm() {
      if (this.confirming) return
      this.confirming = true
      this.confirmError = ''
      const payload = {}
      if (this.note && this.note.trim()) payload.note = this.note.trim()
      confirmRecord(this.recordId, payload, { silent: true })
        .then(() => {
          this.showConfirm = false
          this.note = ''
          uni.showToast({ title: '已提交', icon: 'success' })
          this.load()
        })
        .catch((err) => {
          // 弹窗保持打开（closeOnConfirm=false），让用户看清失败原因后重试或取消
          this.confirmError = confirmErrorText(err)
        })
        .finally(() => {
          this.confirming = false
        })
    },
  },
}
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx calc(150rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}

.tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.tag {
  margin-left: 12rpx;
  padding: 4rpx 16rpx;
  border-radius: $mb-radius-pill;
  font-size: 22rpx;
  line-height: 1.6;

  &--ok {
    color: $mb-primary;
    background: $mb-primary-bg;
  }

  &--warn {
    color: #b7791f;
    background: #fdf3e0;
  }

  &--danger {
    color: #fff;
    background: $mb-danger;
  }

  &--info {
    color: $mb-text-2;
    background: $mb-bg;
  }
}

.capture {
  display: flex;
  flex-direction: column;
  align-items: center;

  &__box {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 100%;
    height: 280rpx;
    border-radius: $mb-radius;
    background: $mb-bg;
    border: 2rpx dashed $mb-border;
  }

  &__icon {
    font-size: 80rpx;
    opacity: 0.4;
  }

  &__text {
    margin-top: 20rpx;
    font-size: 26rpx;
    color: $mb-text-3;
  }
}

.actions {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  padding: 20rpx 24rpx calc(20rpx + env(safe-area-inset-bottom));
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 -8rpx 24rpx rgba(14, 159, 142, 0.08);
}

.confirm-body {
  &__text {
    display: block;
    font-size: 28rpx;
    color: $mb-text-2;
    line-height: 1.6;
    margin-bottom: 8rpx;
  }

  &__err {
    display: block;
    margin-top: 12rpx;
    font-size: 26rpx;
    color: $mb-danger;
    line-height: 1.5;
  }
}
</style>
