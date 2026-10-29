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

      <!-- 抓拍留证：capture-core 已落地，captureId 存在时经 fileToken 加载原图（feat/mp-capture-event）。
           查看原图严格走 5 分钟短期令牌，不暴露长期 JWT（02 §4.1）。 -->
      <MbCard title="抓拍留证">
        <view v-if="record.captureId" class="capture">
          <view v-if="captureFileUrl" class="capture__imgwrap">
            <image
              class="capture__img"
              :src="captureFileUrl"
              mode="widthFix"
              @error="onCaptureError"
            />
          </view>
          <view v-else class="capture__box">
            <text class="capture__icon">📷</text>
          </view>
          <text class="capture__text">{{ captureError || '原图加载中…' }}</text>
          <view v-if="recordUncertain" class="capture__badge">待确认（低置信度）</view>
        </view>
        <view v-else class="capture">
          <view class="capture__box">
            <text class="capture__icon">📷</text>
          </view>
          <text class="capture__text">暂无抓拍留证</text>
        </view>
      </MbCard>

      <view v-if="canWrite" class="actions">
        <MbButton type="primary" block :loading="confirming" @click="openConfirm">
          {{ recordUncertain ? '确认服药情况' : '补录 / 确认' }}
        </MbButton>
      </view>
    </template>

    <MbModal
      v-model="showConfirm"
      :title="recordUncertain ? '确认服药情况' : '补录 / 确认'"
      confirm-text="确认提交"
      :confirm-loading="confirming"
      :close-on-confirm="false"
      @confirm="onConfirm"
    >
      <view class="confirm-body">
        <template v-if="recordUncertain">
          <text class="confirm-body__text">该记录为低置信度待确认，请查看上方抓拍原图后裁定：</text>
          <view class="confirm-body__seg">
            <view
              class="confirm-body__opt"
              :class="{ 'confirm-body__opt--on': faceResult === 'TAKEN' }"
              @click="faceResult = 'TAKEN'"
            >
              <text class="confirm-body__opt-text">确认已服</text>
            </view>
            <view
              class="confirm-body__opt"
              :class="{ 'confirm-body__opt--on': faceResult === 'ABSENT' }"
              @click="faceResult = 'ABSENT'"
            >
              <text class="confirm-body__opt-text">确认未服</text>
            </view>
          </view>
        </template>
        <text v-else class="confirm-body__text">确认本记录为实际服药情况（设备异常时可手工校准）。</text>
        <MbField
          v-model="note"
          label="备注（选填）"
          type="textarea"
          :placeholder="recordUncertain ? '如：已核对原图，老人确实取药服用' : '如：老人实际于 21:10 服药，剂量无误'"
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
import { getCapture } from '@/api/capture.js'
import { BASE_URL } from '@/config/index.js'
import { CODE } from '@/api/request.js'
import {
  canWriteRecord,
  isUncertain,
  recordStatusTags,
  recordTitle,
  recordMedicineText,
  formatPlanTime,
  formatActualTime,
} from '@/utils/record.js'
import { buildCaptureFileUrl, buildConfirmPayload, parseCapture } from '@/utils/capture.js'
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
      faceResult: 'TAKEN',
      confirming: false,
      confirmError: '',
      captureFileUrl: '',
      captureError: '',
    }
  },
  computed: {
    recordUncertain() {
      return isUncertain(this.record)
    },
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
        this.loadCapture()
      } catch (err) {
        this.error = errorText(err)
      } finally {
        this.loading = false
      }
    },
    /** captureId 存在时拉取 fileToken 并拼接原图 URL（02 §4.1：5 分钟有效，单次查看足够）。 */
    async loadCapture() {
      this.captureFileUrl = ''
      this.captureError = ''
      const captureId = this.record && this.record.captureId
      if (!captureId) return
      try {
        const data = await getCapture(captureId, { silent: true })
        const parsed = parseCapture(data)
        if (parsed.fileToken) {
          this.captureFileUrl = buildCaptureFileUrl(captureId, parsed.fileToken, BASE_URL)
        } else {
          this.captureError = '原图令牌获取失败'
        }
      } catch (err) {
        // 非致命：不影响记录主体展示，仅图片区报错（如令牌过期 / 无权限 / 已清理）
        this.captureError = '原图加载失败，请重试'
      }
    },
    onCaptureError() {
      this.captureFileUrl = ''
      this.captureError = '原图加载失败，请重试'
    },
    openConfirm() {
      this.note = ''
      this.faceResult = 'TAKEN'
      this.confirmError = ''
      this.showConfirm = true
    },
    onConfirm() {
      if (this.confirming) return
      this.confirming = true
      this.confirmError = ''
      const payload = buildConfirmPayload({
        faceResult: this.recordUncertain ? this.faceResult : undefined,
        note: this.note,
      })
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

  &--pending {
    color: #9a3412;
    background: #fed7aa;
  }
}

.capture {
  display: flex;
  flex-direction: column;
  align-items: center;

  &__imgwrap {
    width: 100%;
    border-radius: $mb-radius;
    overflow: hidden;
    background: $mb-bg;
  }

  &__img {
    width: 100%;
    display: block;
  }

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

  &__badge {
    margin-top: 16rpx;
    padding: 6rpx 20rpx;
    border-radius: $mb-radius-pill;
    font-size: 24rpx;
    color: #9a3412;
    background: #fed7aa;
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

  &__seg {
    display: flex;
    gap: 16rpx;
    margin: 12rpx 0 4rpx;
  }

  &__opt {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    height: 80rpx;
    border-radius: $mb-radius;
    background: $mb-bg;
    border: 2rpx solid $mb-border;

    &--on {
      background: $mb-primary-bg;
      border-color: $mb-primary;
    }

    &-text {
      font-size: 28rpx;
      color: $mb-text;

      .confirm-body__opt--on & {
        color: $mb-primary;
        font-weight: 500;
      }
    }
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
