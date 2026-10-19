<template>
  <view class="page">
    <MbLoading v-if="loading" fullscreen text="加载中" />

    <MbEmpty v-else-if="error" :text="error" />

    <template v-else>
      <MbCard :title="isEdit ? '编辑药品' : '新建药品'">
        <MbField
          v-model="form.name"
          label="药品名称"
          placeholder="如：阿莫西林胶囊"
          :error="nameError"
          @blur="validateName"
        />
        <MbField v-model="form.spec" label="规格" placeholder="如：0.25g×24粒" />
        <view class="row">
          <MbField v-model="form.dosePerTime" class="row__item" label="默认单次剂量" placeholder="如：1" />
          <MbField v-model="form.unit" class="row__item" label="单位" placeholder="片 / 粒 / ml / 瓶" />
        </view>
        <MbField
          v-model="form.contraindication"
          type="textarea"
          label="禁忌"
          placeholder="如：青霉素过敏者禁用；服用期间忌酒"
        />
        <MbField
          v-model="form.storageCond"
          type="textarea"
          label="储存条件"
          placeholder="如：遮光、密封、阴凉处保存"
        />
      </MbCard>

      <MbCard title="归属">
        <view class="segment">
          <view
            class="segment__item"
            :class="{ 'segment__item--on': ownership === 'public' }"
            @click="ownership = 'public'"
          >
            公共药品
          </view>
          <view
            class="segment__item"
            :class="{ 'segment__item--on': ownership === 'private' }"
            @click="switchPrivate"
          >
            归属老人
          </view>
        </view>
        <text class="hint">
          {{ ownership === 'public' ? '公共药品：全体用户可见（只读）' : '私有药品：仅该老人与其监护人可见' }}
        </text>

        <template v-if="ownership === 'private'">
          <view v-if="eldersLoading" class="elders-tip">正在获取老人列表…</view>
          <template v-else-if="elders.length">
            <MbCell
              v-for="elder in elders"
              :key="elder"
              :title="elder"
              clickable
              @click="selectedElder = elder"
            >
              <template #extra>
                <view class="radio" :class="{ 'radio--on': selectedElder === elder }">
                  <view class="radio__dot"></view>
                </view>
              </template>
            </MbCell>
          </template>
          <view v-else class="elders-tip">暂无可归属的老人，请先为其登记药箱设备</view>
        </template>
      </MbCard>

      <view class="save">
        <MbButton type="primary" block :loading="saving" @click="save">保存</MbButton>
      </view>
    </template>
  </view>
</template>

<script>
import MbButton from '@/components/MbButton.vue'
import MbCard from '@/components/MbCard.vue'
import MbCell from '@/components/MbCell.vue'
import MbEmpty from '@/components/MbEmpty.vue'
import MbField from '@/components/MbField.vue'
import MbLoading from '@/components/MbLoading.vue'
import { getMedicine, saveMedicine } from '@/api/medicine.js'
import { listDevices } from '@/api/device.js'
import { CODE } from '@/api/request.js'
import { buildMedicinePayload } from '@/utils/medicine.js'

/** 表单初始值（与 `medicine` 表字段一一对应，见 06 的 2.4）。 */
function emptyForm() {
  return {
    medicineId: '',
    name: '',
    spec: '',
    dosePerTime: '',
    unit: '',
    contraindication: '',
    storageCond: '',
    ownerElderId: '',
  }
}

/** 编辑页加载失败文案按 code 细分（请求已 silent）。 */
function errorText(err) {
  if (err && err.code === CODE.FORBIDDEN) return '无权访问或药品不存在'
  if (err && err.code === CODE.NOT_FOUND) return '药品不存在或已删除'
  if (err && err.code === CODE.GUARDIAN_PENDING) return '监护关系未生效，请等待老人确认'
  return (err && err.message) || '加载失败'
}

export default {
  name: 'MedicineEditPage',
  components: { MbButton, MbCard, MbCell, MbEmpty, MbField, MbLoading },
  data() {
    return {
      medicineId: '',
      isEdit: false,
      loading: false,
      error: '',
      saving: false,
      form: emptyForm(),
      nameError: '',
      ownership: 'public',
      elders: [],
      eldersLoading: false,
      eldersLoaded: false,
      selectedElder: '',
    }
  },
  onLoad(options) {
    this.medicineId = (options && options.medicineId) || ''
    this.isEdit = !!this.medicineId
    uni.setNavigationBarTitle({ title: this.isEdit ? '编辑药品' : '新建药品' })
    if (this.isEdit) {
      this.load()
    }
  },
  methods: {
    async load() {
      this.loading = true
      this.error = ''
      try {
        const medicine = await getMedicine(this.medicineId, { silent: true })
        this.prefill(medicine)
      } catch (err) {
        this.error = errorText(err)
      } finally {
        this.loading = false
      }
    },
    prefill(medicine) {
      const form = emptyForm()
      form.medicineId = medicine.medicineId || this.medicineId
      form.name = medicine.name || ''
      form.spec = medicine.spec || ''
      form.dosePerTime = medicine.dosePerTime || ''
      form.unit = medicine.unit || ''
      form.contraindication = medicine.contraindication || ''
      form.storageCond = medicine.storageCond || ''
      this.form = form
      this.ownership = medicine.ownerElderId ? 'private' : 'public'
      this.selectedElder = medicine.ownerElderId || ''
      if (this.ownership === 'private') this.loadElders()
    },
    validateName() {
      this.nameError = this.form.name.trim() ? '' : '请输入药品名称'
    },
    switchPrivate() {
      this.ownership = 'private'
      this.loadElders()
    },
    async loadElders() {
      if (this.eldersLoaded || this.eldersLoading) return
      this.eldersLoading = true
      try {
        // P1 `mp-guardian` 前没有"我监护的老人列表"接口，暂从可见设备的 elderId 归纳
        // （与 `feat/mp-device` 列表页归纳 WS 订阅主题同一思路）
        const devices = await listDevices(undefined, { silent: true })
        const elders = []
        ;(devices || []).forEach((device) => {
          if (device.elderId && elders.indexOf(device.elderId) === -1) elders.push(device.elderId)
        })
        this.elders = elders
        this.eldersLoaded = true
      } catch (err) {
        // 拉不到设备不阻塞表单：只影响私有归属的候选，公共药品照常可建
        this.elders = []
      } finally {
        this.eldersLoading = false
      }
    },
    async save() {
      this.validateName()
      if (this.nameError) return
      if (this.ownership === 'private' && !this.selectedElder) {
        uni.showToast({ title: '请选择归属老人', icon: 'none' })
        return
      }
      const payload = buildMedicinePayload({
        ...this.form,
        ownerElderId: this.ownership === 'private' ? this.selectedElder : '',
      })
      this.saving = true
      try {
        await saveMedicine(payload, { loading: '保存中' })
        uni.showToast({ title: '已保存', icon: 'success' })
        setTimeout(() => uni.navigateBack(), 300)
      } catch (err) {
        // 请求层已 toast 后端 message（40001 会带首条校验错误）；仅补 40302 的明确文案
        if (err && err.code === CODE.GUARDIAN_PENDING) {
          uni.showToast({ title: '监护关系未生效，请等待老人确认', icon: 'none' })
        }
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
}

.row {
  display: flex;
  gap: 20rpx;
}

/* WXSS 不支持 `.row > *` 通配符（H5 能过、小程序编译报错），用显式类名 */
.row__item {
  flex: 1;
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
    transition: background-color 0.2s, color 0.2s;

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
  margin: 16rpx 0 8rpx;
  font-size: 24rpx;
  color: $mb-text-3;
}

.elders-tip {
  padding: 24rpx 0 8rpx;
  font-size: 26rpx;
  color: $mb-text-3;
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

.save {
  margin-top: 32rpx;
}
</style>
