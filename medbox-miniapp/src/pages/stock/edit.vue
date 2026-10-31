<template>
  <view class="page">
    <MbLoading v-if="loading" fullscreen text="加载中" />

    <MbEmpty v-else-if="error" :text="error" />

    <template v-else>
      <MbCard title="库存录入">
        <MbCell
          title="药品"
          :label="selectedMedicineSpec"
          :value="selectedMedicineName || '点击选择'"
          clickable
          arrow
          @click="showPicker = true"
        />
        <MbField
          v-model="form.quantity"
          type="number"
          label="数量"
          placeholder="当前库存数量（绝对值）"
          :error="quantityError"
          @input="quantityError = ''"
        />
        <picker mode="date" :value="form.productionDate" @change="onDateChange('form', 'productionDate', $event)">
          <MbCell title="生产日期" :value="form.productionDate || '点击选择'" arrow />
        </picker>
        <picker mode="date" :value="form.expiryDate" @change="onDateChange('form', 'expiryDate', $event)">
          <MbCell title="有效期至" :value="form.expiryDate || '点击选择'" arrow />
        </picker>
      </MbCard>

      <MbCard v-if="stock" title="补药入库">
        <text class="hint">在现有库存（{{ stock.quantity }}）基础上增加，可同时更新日期</text>
        <MbField
          v-model="restock.quantity"
          type="number"
          label="入库数量"
          placeholder="本次补药数量（增量）"
          :error="restockError"
          @input="restockError = ''"
        />
        <picker mode="date" :value="restock.productionDate" @change="onDateChange('restock', 'productionDate', $event)">
          <MbCell title="生产日期（可选）" :value="restock.productionDate || '不更新'" arrow />
        </picker>
        <picker mode="date" :value="restock.expiryDate" @change="onDateChange('restock', 'expiryDate', $event)">
          <MbCell title="有效期至（可选）" :value="restock.expiryDate || '不更新'" arrow />
        </picker>
        <view class="restock-btn">
          <MbButton type="ghost" block :loading="restocking" @click="onRestock">补药入库</MbButton>
        </view>
      </MbCard>

      <view class="save">
        <MbButton type="primary" block :loading="saving" @click="onSave">保存</MbButton>
      </view>

      <view v-if="stock" class="remove">
        <MbButton type="danger" block @click="showDelete = true">移除库存</MbButton>
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
          clickable
          @click="pickMedicine(medicine)"
        >
          <template #extra>
            <view class="radio" :class="{ 'radio--on': selectedMedicineId === medicine.medicineId }">
              <view class="radio__dot"></view>
            </view>
          </template>
        </MbCell>
      </view>
    </MbModal>

    <MbModal
      v-model="showDelete"
      title="移除库存"
      confirm-type="danger"
      confirm-text="移除"
      :confirm-loading="deleting"
      :close-on-confirm="false"
      @confirm="onDeleteConfirm"
    >
      <view class="delete-body">
        <text class="delete-body__text">移除后该药品在此药箱的库存记录将删除，确定移除吗？</text>
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
import { listMedicines } from '@/api/medicine.js'
import { listStocks, restockStock, saveStock, deleteStock } from '@/api/stock.js'
import { CODE } from '@/api/request.js'
import { canWriteMedicine, parseMedicinePage } from '@/utils/medicine.js'
import { getRole } from '@/store/user.js'
import { buildRestockPayload, buildStockPayload, parseStocks } from '@/utils/stock.js'

/** 录入 / 补药失败文案按 code 细分（请求已 silent，统一自绘）。 */
function actionErrorText(err, fallback) {
  if (err && err.code === CODE.FORBIDDEN) return '无权操作该药箱库存'
  if (err && err.code === CODE.GUARDIAN_PENDING) return '监护关系未生效，请等待老人确认'
  return (err && err.message) || fallback
}

export default {
  name: 'StockEditPage',
  components: { MbButton, MbCard, MbCell, MbEmpty, MbField, MbLoading, MbModal },
  data() {
    return {
      deviceId: '',
      medicineId: '',
      loading: false,
      error: '',
      stocks: [],
      medicines: [],
      selectedMedicineId: '',
      stock: null,
      form: { quantity: '', productionDate: '', expiryDate: '' },
      restock: { quantity: '', productionDate: '', expiryDate: '' },
      quantityError: '',
      restockError: '',
      saving: false,
      restocking: false,
      showPicker: false,
      showDelete: false,
      deleting: false,
      deleteError: '',
    }
  },
  computed: {
    selectedMedicineName() {
      const hit = this.medicines.find((m) => m.medicineId === this.selectedMedicineId)
      return hit ? hit.name : ''
    },
    selectedMedicineSpec() {
      const hit = this.medicines.find((m) => m.medicineId === this.selectedMedicineId)
      return hit ? hit.spec || '' : ''
    },
  },
  onLoad(options) {
    this.deviceId = (options && options.deviceId) || ''
    this.medicineId = (options && options.medicineId) || ''
    // WRITE_MEDICINE 覆盖库存录入与补药（02 的 1.3 矩阵），仅 GUARDIAN；服务端仍强校验
    if (!canWriteMedicine(getRole())) {
      this.error = '仅监护人可维护药箱库存'
      return
    }
    this.load()
  },
  methods: {
    async load() {
      if (!this.deviceId) {
        this.error = '缺少设备编号'
        return
      }
      this.loading = true
      this.error = ''
      try {
        const [stocks, medicines] = await Promise.all([
          listStocks(this.deviceId, { silent: true }),
          // 药品字典拉不到不阻塞页面：名称回退显示 medicineId
          listMedicines({ page: 1, size: 100 }, { silent: true }).then(
            (page) => parseMedicinePage(page).list,
            () => [],
          ),
        ])
        this.stocks = parseStocks(stocks)
        this.medicines = medicines
        if (this.medicineId) {
          this.selectedMedicineId = this.medicineId
          this.prefillFromStock(this.medicineId)
        }
      } catch (err) {
        this.error =
          err && err.code === CODE.FORBIDDEN
            ? '无权访问或设备不存在'
            : (err && err.message) || '加载失败'
      } finally {
        this.loading = false
      }
    },
    /** 选中药品时，若该药箱已有其库存记录则带出现值，否则清空待录入。 */
    prefillFromStock(medicineId) {
      const stock = this.stocks.find((s) => s.medicineId === medicineId) || null
      this.stock = stock
      this.form.quantity = stock && stock.quantity != null ? String(stock.quantity) : ''
      this.form.productionDate = (stock && stock.productionDate) || ''
      this.form.expiryDate = (stock && stock.expiryDate) || ''
      this.restock.quantity = ''
      this.restock.productionDate = ''
      this.restock.expiryDate = ''
    },
    pickMedicine(medicine) {
      this.selectedMedicineId = medicine.medicineId
      this.showPicker = false
      this.prefillFromStock(medicine.medicineId)
    },
    onDateChange(group, field, e) {
      // DATE 列无时刻：picker 直接给 YYYY-MM-DD，不做 UTC 换算（02 的 1. 时区口径）
      this[group][field] = (e && e.detail && e.detail.value) || ''
    },
    async onSave() {
      if (!this.selectedMedicineId) {
        uni.showToast({ title: '请选择药品', icon: 'none' })
        return
      }
      const result = buildStockPayload(this.form)
      if (!result.ok) {
        this.quantityError = result.error
        return
      }
      this.quantityError = ''
      this.saving = true
      try {
        await saveStock(this.deviceId, this.selectedMedicineId, result.payload, {
          loading: '保存中',
          silent: true,
        })
        uni.showToast({ title: '已保存', icon: 'success' })
        setTimeout(() => uni.navigateBack(), 300)
      } catch (err) {
        uni.showToast({ title: actionErrorText(err, '保存失败，请稍后再试'), icon: 'none' })
      } finally {
        this.saving = false
      }
    },
    async onRestock() {
      const result = buildRestockPayload(this.restock)
      if (!result.ok) {
        this.restockError = result.error
        return
      }
      this.restockError = ''
      this.restocking = true
      try {
        await restockStock(this.deviceId, this.selectedMedicineId, result.payload, {
          loading: '入库中',
          silent: true,
        })
        uni.showToast({ title: '已入库', icon: 'success' })
        setTimeout(() => uni.navigateBack(), 300)
      } catch (err) {
        uni.showToast({ title: actionErrorText(err, '入库失败，请稍后再试'), icon: 'none' })
      } finally {
        this.restocking = false
      }
    },
    onDeleteConfirm() {
      if (this.deleting) return
      this.deleting = true
      this.deleteError = ''
      deleteStock(this.deviceId, this.selectedMedicineId, { silent: true })
        .then(() => {
          uni.showToast({ title: '已移除', icon: 'success' })
          uni.navigateBack()
        })
        .catch((err) => {
          // 弹窗开着（closeOnConfirm=false），让用户看清失败原因后重试或取消
          this.deleteError = actionErrorText(err, '移除失败，请稍后再试')
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

.hint {
  display: block;
  margin-bottom: 16rpx;
  font-size: 24rpx;
  color: $mb-text-3;
}

.restock-btn {
  margin-top: 16rpx;
}

.save {
  margin-top: 32rpx;
}

.remove {
  margin-top: 24rpx;
}

.picker-list {
  max-height: 600rpx;
  overflow-y: auto;
}

.radio {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36rpx;
  height: 36rpx;
  border: 2rpx solid $mb-text-4;
  border-radius: 50%;

  &--on {
    border-color: $mb-primary;
  }

  &__dot {
    width: 20rpx;
    height: 20rpx;
    border-radius: 50%;
    background: transparent;
  }

  &--on &__dot {
    background: $mb-primary;
  }
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
