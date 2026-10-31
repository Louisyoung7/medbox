<template>
  <view class="page">
    <MbLoading v-if="loading" fullscreen text="加载中" />

    <MbEmpty v-else-if="error" :text="error" />

    <template v-else>
      <MbCard title="基本信息">
        <MbField
          v-model="form.name"
          label="方案名称"
          placeholder="如：早餐后、睡前"
          :error="nameError"
          @input="nameError = ''"
        />
      </MbCard>

      <MbCard title="提醒时间">
        <view v-if="form.times.length" class="times">
          <view v-for="(time, index) in form.times" :key="time" class="times__chip">
            <text class="times__text">{{ time }}</text>
            <text class="times__del" @click="removeTime(index)">×</text>
          </view>
        </view>
        <picker mode="time" @change="onTimePicked">
          <view class="times__add">
            <text class="times__add-text">＋ 添加时间</text>
          </view>
        </picker>
        <text class="hint">时间为药箱本地时间（默认北京时间），保存后按时间升序下发</text>
      </MbCard>

      <MbCard title="重复周期">
        <view class="segment">
          <view
            class="segment__item"
            :class="{ 'segment__item--on': form.repeatRule === 'DAILY' }"
            @click="form.repeatRule = 'DAILY'"
          >
            每天
          </view>
          <view
            class="segment__item"
            :class="{ 'segment__item--on': form.repeatRule === 'WEEKLY' }"
            @click="form.repeatRule = 'WEEKLY'"
          >
            每周
          </view>
        </view>
      </MbCard>

      <MbCard title="药品明细">
        <PlanItemRow
          v-for="(item, index) in form.items"
          :key="item.medicineId"
          :item="item"
          :name="medicineName(item.medicineId)"
          :spec="medicineSpec(item.medicineId)"
          :index="index"
          :count="form.items.length"
          @field="onItemField"
          @move="onItemMove"
          @remove="removeItem"
        />
        <view class="items__add">
          <MbButton type="ghost" block @click="showPicker = true">＋ 添加药品</MbButton>
        </view>
        <text v-if="!form.items.length" class="hint">一次提醒可添加多种药，至少添加一种</text>
      </MbCard>

      <view class="save">
        <MbButton type="primary" block :loading="saving" @click="save">保存</MbButton>
      </view>

      <view v-if="isEdit" class="remove">
        <MbButton type="danger" block @click="showDelete = true">删除计划</MbButton>
      </view>
    </template>

    <MbModal v-model="showPicker" title="选择药品" confirm-text="关闭" :show-cancel="false">
      <view class="picker-list">
        <MbEmpty v-if="!medicines.length" text="暂无可用药品，请先在药品管理中新建" />
        <MbCell
          v-for="medicine in medicines"
          :key="medicine.medicineId"
          :title="medicine.name"
          :label="medicine.spec || ''"
          :clickable="!isBlocked(medicine.medicineId) && !isSelected(medicine.medicineId)"
          @click="pickMedicine(medicine)"
        >
          <template #extra>
            <view v-if="isBlocked(medicine.medicineId)" class="tag tag--blocked">
              <text class="tag__text">{{ blockedReasonText(medicine.medicineId) }}</text>
            </view>
            <view v-else-if="isSelected(medicine.medicineId)" class="tag tag--picked">
              <text class="tag__text">已添加</text>
            </view>
          </template>
        </MbCell>
      </view>
    </MbModal>

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
        <text class="delete-body__text">删除后该计划的提醒与明细将一并移除，确定删除吗？</text>
        <text v-if="deleteError" class="delete-body__err">{{ deleteError }}</text>
      </view>
    </MbModal>
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
import PlanItemRow from '@/components/PlanItemRow.vue'
import { listDevices } from '@/api/device.js'
import { listMedicines } from '@/api/medicine.js'
import { createPlan, deletePlan, getPlan, updatePlan } from '@/api/plan.js'
import { listStocks } from '@/api/stock.js'
import { CODE } from '@/api/request.js'
import { parseMedicinePage } from '@/utils/medicine.js'
import { parseStocks, toMedicineMap } from '@/utils/stock.js'
import { buildPlanPayload, canWritePlan, normalizeTimes, pickableMedicines } from '@/utils/plan.js'
import { getRole } from '@/store/user.js'

/** 保存 / 删除失败文案按 code 细分（请求已 silent，统一自绘）。 */
function actionErrorText(err, fallback) {
  if (err && err.code === CODE.CONFLICT) return '药品无库存，不能加入计划'
  if (err && err.code === CODE.FORBIDDEN) return '无权操作该计划'
  if (err && err.code === CODE.GUARDIAN_PENDING) return '监护关系未生效，请等待老人确认'
  return (err && err.message) || fallback
}

export default {
  name: 'PlanEditPage',
  components: { MbButton, MbCard, MbCell, MbEmpty, MbField, MbLoading, MbModal, PlanItemRow },
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
      showPicker: false,
      nameError: '',
      form: {
        name: '',
        times: [],
        repeatRule: 'DAILY',
        items: [],
      },
      medicines: [],
      medicineMap: {},
      stockRows: [],
      stocksLoaded: false,
    }
  },
  computed: {
    /** 药品 -> 拦截原因（'no-stock'）；库存没拉到时不拦截（规则 C 兜底）。 */
    blockedMap() {
      if (!this.stocksLoaded) return {}
      const { blocked } = pickableMedicines(this.medicines, this.stockRows)
      const map = {}
      blocked.forEach((entry) => {
        map[entry.medicine.medicineId] = entry.reason
      })
      return map
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
    async load() {
      if (!this.elderId) {
        this.error = '缺少老人信息'
        return
      }
      this.loading = true
      this.error = ''
      try {
        this.medicines = await this.loadMedicines()
        this.medicineMap = toMedicineMap(this.medicines)
        await this.loadStocks()
        if (this.isEdit) {
          const plan = await getPlan(this.planId, { silent: true })
          this.prefill(plan)
        }
      } catch (err) {
        this.error =
          err && err.code === CODE.FORBIDDEN
            ? '无权访问或计划不存在'
            : (err && err.message) || '加载失败'
      } finally {
        this.loading = false
      }
    },
    async loadMedicines() {
      try {
        const page = parseMedicinePage(await listMedicines({ page: 1, size: 100 }, { silent: true }))
        return page.list
      } catch (err) {
        return []
      }
    },
    /** 拉该老人在其可见设备上的全部库存行（规则 C 判定依据）；失败 = stocksLoaded=false 不拦截。 */
    async loadStocks() {
      try {
        const devices = await listDevices(undefined, { silent: true })
        const mains = (devices || []).filter(
          (device) => device.deviceType === 'MAIN' && device.elderId === this.elderId,
        )
        const rows = []
        for (let i = 0; i < mains.length; i += 1) {
          const stocks = await listStocks(mains[i].deviceId, { silent: true })
          rows.push(...parseStocks(stocks))
        }
        this.stockRows = rows
        this.stocksLoaded = true
      } catch (err) {
        this.stockRows = []
        this.stocksLoaded = false
      }
    },
    prefill(plan) {
      this.form.name = plan.name || ''
      this.form.times = Array.isArray(plan.times) ? plan.times.slice() : []
      this.form.repeatRule = plan.repeatRule || 'DAILY'
      this.form.items = (Array.isArray(plan.items) ? plan.items : [])
        .slice()
        .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
        .map((item) => ({
          medicineId: item.medicineId || '',
          dose: item.dose || '',
          unit: item.unit || '',
          note: item.note || '',
        }))
    },
    medicineName(medicineId) {
      const medicine = this.medicineMap[medicineId]
      return (medicine && medicine.name) || medicineId
    },
    medicineSpec(medicineId) {
      const medicine = this.medicineMap[medicineId]
      return (medicine && medicine.spec) || ''
    },
    isBlocked(medicineId) {
      return !!this.blockedMap[medicineId]
    },
    isSelected(medicineId) {
      return this.form.items.some((item) => item.medicineId === medicineId)
    },
    blockedReasonText(medicineId) {
      return '无库存'
    },
    onTimePicked(e) {
      const value = (e && e.detail && e.detail.value) || ''
      if (!normalizeTimes([value]).length) {
        uni.showToast({ title: '时间格式不正确', icon: 'none' })
        return
      }
      if (this.form.times.indexOf(value) !== -1) {
        uni.showToast({ title: '该时间已添加', icon: 'none' })
        return
      }
      this.form.times.push(value)
      // 规则 A：提交时统一去重 + 升序，这里即时排序保持展示一致
      this.form.times = normalizeTimes(this.form.times)
    },
    removeTime(index) {
      this.form.times.splice(index, 1)
    },
    pickMedicine(medicine) {
      if (this.isBlocked(medicine.medicineId)) {
        uni.showToast({
          title: '药箱暂无该药品库存',
          icon: 'none',
        })
        return
      }
      if (this.isSelected(medicine.medicineId)) {
        uni.showToast({ title: '该药品已在计划中', icon: 'none' })
        return
      }
      // 规则 B：dose/unit 预填药品档案默认值（06：计划明细未指定时取此值），用户可改
      this.form.items.push({
        medicineId: medicine.medicineId,
        dose: medicine.dosePerTime || '',
        unit: medicine.unit || '',
        note: '',
      })
      this.showPicker = false
    },
    onItemField({ index, field, value }) {
      this.form.items[index][field] = value
    },
    onItemMove({ index, dir }) {
      const target = index + dir
      if (target < 0 || target >= this.form.items.length) return
      const items = this.form.items.slice()
      const [moved] = items.splice(index, 1)
      items.splice(target, 0, moved)
      this.form.items = items
    },
    removeItem(index) {
      this.form.items.splice(index, 1)
    },
    async save() {
      this.nameError = this.form.name.trim() ? '' : '请输入方案名称'
      if (this.nameError) return
      if (!normalizeTimes(this.form.times).length) {
        uni.showToast({ title: '请至少添加一个提醒时间', icon: 'none' })
        return
      }
      if (!this.form.items.length) {
        uni.showToast({ title: '请至少添加一种药品', icon: 'none' })
        return
      }
      const payload = buildPlanPayload(this.form, this.elderId)
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
          // 弹窗开着（closeOnConfirm=false），让用户看清失败原因后重试或取消
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

.times {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin-bottom: 16rpx;

  &__chip {
    display: inline-flex;
    align-items: center;
    height: 56rpx;
    padding: 0 12rpx 0 20rpx;
    border-radius: $mb-radius-pill;
    background: $mb-primary-bg;
  }

  &__text {
    font-size: 26rpx;
    color: $mb-primary;
    font-weight: 500;
  }

  &__del {
    margin-left: 8rpx;
    width: 36rpx;
    height: 36rpx;
    line-height: 36rpx;
    text-align: center;
    font-size: 26rpx;
    color: $mb-primary;
  }

  &__add {
    display: inline-flex;
    align-items: center;
    height: 56rpx;
    padding: 0 24rpx;
    border: 2rpx dashed $mb-primary;
    border-radius: $mb-radius-pill;

    &-text {
      font-size: 26rpx;
      color: $mb-primary;
    }
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

.items__add {
  margin-top: 16rpx;
}

.hint {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  color: $mb-text-3;
}

.tag {
  display: inline-flex;
  align-items: center;
  height: 40rpx;
  padding: 0 16rpx;
  border-radius: $mb-radius-pill;

  &__text {
    font-size: 22rpx;
  }

  &--blocked {
    background: #fdecec;

    .tag__text {
      color: $mb-danger;
    }
  }

  &--picked {
    background: $mb-border;

    .tag__text {
      color: $mb-text-3;
    }
  }
}

.picker-list {
  max-height: 600rpx;
  overflow-y: auto;
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
