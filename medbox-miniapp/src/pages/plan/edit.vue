<template>
  <view class="page">
    <MbLoading v-if="loading" fullscreen text="加载中" />
    <MbEmpty v-else-if="error" :text="error" />

    <template v-else>
      <MbCard title="基本信息">
        <MbField
          v-model="form.name"
          label="方案名称"
          placeholder="选填，如：日常服药"
          @input="nameError = ''"
        />
      </MbCard>

      <MbCard title="固定药格排程">
        <view v-for="cell in form.cells" :key="cell.cellNo" class="cell-row">
          <view class="cell-row__head">
            <text class="cell-row__title">{{ cellLabel(cell.cellNo) }}</text>
            <switch
              :checked="cell.enabled"
              color="$mb-primary"
              @change="onCellToggle(cell.cellNo, $event)"
            />
          </view>
          <picker
            v-if="cell.enabled"
            mode="time"
            :value="cell.time"
            @change="onCellTimePicked(cell.cellNo, $event)"
          >
            <view class="cell-row__time">
              <text>{{ cell.time || '选择开格时间' }}</text>
              <text class="cell-row__arrow">›</text>
            </view>
          </picker>
          <text v-else class="hint">未排程（该格不提醒、不解锁）</text>
          <text v-if="cell.enabled && cellError[cell.cellNo]" class="cell-row__err">
            {{ cellError[cell.cellNo] }}
          </text>
        </view>
        <text class="hint">格号 1 / 2 / 3 由药箱固件固定对应早 / 中 / 晚；时间为药箱本地墙钟，格内放什么药由监护人自行布置，系统不记录。</text>
      </MbCard>

      <MbCard title="重复周期">
        <view class="segment">
          <view class="segment__item segment__item--on">每天</view>
        </view>
      </MbCard>

      <view class="save">
        <MbButton type="primary" block :loading="saving" @click="save">保存</MbButton>
      </view>

      <view v-if="isEdit" class="remove">
        <MbButton type="danger" block @click="showDelete = true">删除计划</MbButton>
      </view>
    </template>

    <MbModal
      v-model="showDelete"
      title="删除计划"
      confirm-type="danger"
      confirm-text="删除"
      :confirm-loading="deleting"
      :close-on-confirm="false"
      @confirm="onDeleteConfirm"
    >
      <view class="delete-body">
        <text class="delete-body__text">删除后该老人的格位排程将一并移除，确定删除吗？</text>
        <text v-if="deleteError" class="delete-body__err">{{ deleteError }}</text>
      </view>
    </MbModal>
  </view>
</template>

<script>
import MbButton from '@/components/MbButton.vue'
import MbCard from '@/components/MbCard.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import MbField from '@/components/MbField.vue'
import MbLoading from '@/components/MbLoading.vue'
import MbModal from '@/components/MbModal.vue'
import { createPlan, deletePlan, getPlan, updatePlan } from '@/api/plan.js'
import { CODE } from '@/api/request.js'
import { buildPlanPayload, canWritePlan, cellLabel, normalizeSlots } from '@/utils/plan.js'
import { getRole } from '@/store/user.js'

/** 保存 / 删除失败文案按 code 细分（请求已 silent，统一自绘）。 */
function actionErrorText(err, fallback) {
  if (err && err.code === CODE.CONFLICT) return '该老人已有计划，请直接编辑'
  if (err && err.code === CODE.BAD_REQUEST) return '格位排程不合法，请检查格号与时间'
  if (err && err.code === CODE.FORBIDDEN) return '无权操作该计划'
  if (err && err.code === CODE.GUARDIAN_PENDING) return '监护关系未生效，请等待老人确认'
  return (err && err.message) || fallback
}

function defaultCells() {
  return [
    { cellNo: 1, enabled: true, time: '08:00' },
    { cellNo: 2, enabled: false, time: '12:30' },
    { cellNo: 3, enabled: false, time: '18:00' },
  ]
}

export default {
  name: 'PlanEditPage',
  components: { MbButton, MbCard, MbEmpty, MbField, MbLoading, MbModal },
  data() {
    return {
      planId: '',
      elderId: '',
      isEdit: false,
      loading: false,
      error: '',
      saving: false,
      deleting: false,
      showDelete: false,
      deleteError: '',
      nameError: '',
      cellError: {},
      form: {
        name: '',
        repeatRule: 'DAILY',
        cells: defaultCells(),
      },
    }
  },
  computed: {
    /** 已启用格位 → `slots`（统一归一化：格号合法、时间 HH:mm、按格号升序）。 */
    slots() {
      return normalizeSlots(
        this.form.cells
          .filter((cell) => cell.enabled)
          .map((cell) => ({ cellNo: cell.cellNo, time: cell.time })),
      )
    },
  },
  onLoad(options) {
    this.planId = (options && options.planId) || ''
    this.elderId = (options && options.elderId) || ''
    this.isEdit = !!this.planId
    uni.setNavigationBarTitle({ title: this.isEdit ? '编辑计划' : '新建计划' })
    // WRITE_PLAN 仅 GUARDIAN（02 的 1.3），前端只给监护人进编辑页
    if (!canWritePlan(getRole())) {
      this.error = '仅监护人可维护服药计划'
      return
    }
    this.load()
  },
  methods: {
    cellLabel,
    async load() {
      if (!this.elderId) {
        this.error = '缺少老人信息'
        return
      }
      if (!this.isEdit) return
      this.loading = true
      this.error = ''
      try {
        const plan = await getPlan(this.planId, { silent: true })
        this.prefill(plan)
      } catch (err) {
        this.error =
          err && err.code === CODE.FORBIDDEN
            ? '无权访问或计划不存在'
            : (err && err.message) || '加载失败'
      } finally {
        this.loading = false
      }
    },
    /** 后端 `slots` 回填：缺失的格号 = 该格不排程。 */
    prefill(plan) {
      this.form.name = plan.name || ''
      this.form.repeatRule = plan.repeatRule || 'DAILY'
      const slots = normalizeSlots(plan.slots)
      this.form.cells = defaultCells().map((cell) => {
        const hit = slots.find((slot) => slot.cellNo === cell.cellNo)
        return hit
          ? { cellNo: cell.cellNo, enabled: true, time: hit.time }
          : { ...cell, enabled: false }
      })
    },
    onCellToggle(cellNo, event) {
      const cell = this.form.cells.find((item) => item.cellNo === cellNo)
      if (cell) cell.enabled = !!(event && event.detail && event.detail.value)
    },
    onCellTimePicked(cellNo, event) {
      const value = (event && event.detail && event.detail.value) || ''
      const cell = this.form.cells.find((item) => item.cellNo === cellNo)
      if (!cell) return
      if (!normalizeSlots([{ cellNo, time: value }]).length) {
        this.cellError = { ...this.cellError, [cellNo]: '时间格式应为 HH:mm' }
        return
      }
      cell.time = value
      this.cellError = { ...this.cellError, [cellNo]: '' }
    },
    async save() {
      this.nameError = ''
      if (!this.slots.length) {
        uni.showToast({ title: '请至少启用一个格位并设置有效时间', icon: 'none' })
        return
      }
      const payload = buildPlanPayload(
        { name: this.form.name, slots: this.slots, repeatRule: this.form.repeatRule },
        this.elderId,
      )
      this.saving = true
      try {
        if (this.isEdit) {
          await updatePlan(this.planId, payload, { loading: '保存中', silent: true })
        } else {
          await createPlan(payload, { loading: '保存中', silent: true })
        }
        uni.showToast({ title: '已保存', icon: 'success' })
        setTimeout(() => uni.navigateBack(), 300)
      } catch (err) {
        uni.showToast({ title: actionErrorText(err, '保存失败，请稍后再试'), icon: 'none' })
      } finally {
        this.saving = false
      }
    },
    onDeleteConfirm() {
      if (this.deleting) return
      this.deleting = true
      this.deleteError = ''
      deletePlan(this.planId, { silent: true })
        .then(() => {
          uni.showToast({ title: '已删除', icon: 'success' })
          uni.navigateBack()
        })
        .catch((err) => {
          this.deleteError = actionErrorText(err, '删除失败，请稍后再试')
        })
        .finally(() => {
          this.deleting = false
        })
    },
  },
}
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx 48rpx;
  box-sizing: border-box;
}

.cell-row {
  padding: 16rpx 0;
  border-bottom: 2rpx solid $mb-border;

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__title {
    font-size: 28rpx;
    color: $mb-text;
    font-weight: 500;
  }

  &__time {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: 12rpx;
    padding: 12rpx 20rpx;
    border-radius: $mb-radius-pill;
    background: $mb-primary-bg;
    font-size: 28rpx;
    color: $mb-primary;
  }

  &__arrow {
    font-size: 32rpx;
    color: $mb-primary;
  }

  &__err {
    display: block;
    margin-top: 8rpx;
    font-size: 24rpx;
    color: $mb-danger;
  }
}

.segment {
  display: flex;
  padding: 6rpx;
  border-radius: $mb-radius-pill;
  background: $mb-border;

  &__item {
    flex: 1;
    height: 64rpx;
    line-height: 64rpx;
    text-align: center;
    font-size: 28rpx;
    color: $mb-text-3;
    border-radius: $mb-radius-pill;

    &--on {
      background: $mb-card-bg;
      color: $mb-primary;
      font-weight: 500;
      box-shadow: 0 4rpx 12rpx rgba(14, 159, 142, 0.12);
    }
  }
}

.hint {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  color: $mb-text-3;
  line-height: 1.6;
}

.save {
  margin-top: 32rpx;
}

.remove {
  margin-top: 24rpx;
}

.delete-body {
  &__text {
    font-size: 28rpx;
    color: $mb-text-2;
    line-height: 1.6;
  }

  &__err {
    display: block;
    margin-top: 16rpx;
    font-size: 26rpx;
    color: $mb-danger;
    line-height: 1.5;
  }
}
</style>
