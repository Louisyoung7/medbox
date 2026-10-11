<template>
  <view class="mb-field" :class="{ 'mb-field--focus': focused, 'mb-field--error': !!error }">
    <text v-if="label" class="mb-field__label">{{ label }}</text>
    <view class="mb-field__control">
      <input
        class="mb-field__input"
        :value="modelValue"
        :type="inputType"
        :placeholder="placeholder"
        :password="type === 'password' && !showPwd"
        :disabled="disabled"
        placeholder-class="mb-field__placeholder"
        @input="onInput"
        @focus="focused = true"
        @blur="focused = false"
      />
      <text v-if="type === 'password'" class="mb-field__action" @click="showPwd = !showPwd">
        {{ showPwd ? '隐藏' : '显示' }}
      </text>
    </view>
    <text v-if="error" class="mb-field__err">{{ error }}</text>
  </view>
</template>

<script>
/**
 * 表单输入（`feat/mp-ui-kit`）：label / input / 错误提示，密码类型带显隐切换。
 * `v-model` 双向绑定（modelValue）。`type` = text / password / number。
 */
export default {
  name: 'MbField',
  props: {
    modelValue: { type: [String, Number], default: '' },
    label: { type: String, default: '' },
    placeholder: { type: String, default: '' },
    type: { type: String, default: 'text' },
    error: { type: String, default: '' },
    disabled: { type: Boolean, default: false },
  },
  data() {
    return { focused: false, showPwd: false }
  },
  computed: {
    inputType() {
      if (this.type === 'number') return 'number'
      return 'text'
    },
  },
  methods: {
    onInput(e) {
      this.$emit('update:modelValue', e.detail.value)
      this.$emit('input', e.detail.value)
    },
  },
}
</script>

<style lang="scss" scoped>
.mb-field {
  margin-bottom: 24rpx;

  &__label {
    display: block;
    font-size: 28rpx;
    color: $mb-text;
    margin-bottom: 12rpx;
  }

  &__control {
    display: flex;
    align-items: center;
    height: 100rpx;
    padding: 0 24rpx;
    border: 2rpx solid $mb-border;
    border-radius: $mb-radius-sm;
    background: $mb-bg;
    transition: border-color 0.2s, background-color 0.2s;
  }

  &--focus &__control {
    border-color: $mb-primary;
    background: $mb-card-bg;
  }

  &--error &__control {
    border-color: $mb-danger;
  }

  &__input {
    flex: 1;
    height: 100rpx;
    font-size: 30rpx;
    color: $mb-text;
  }

  &__placeholder {
    color: $mb-placeholder;
    font-size: 28rpx;
  }

  &__action {
    padding-left: 20rpx;
    font-size: 28rpx;
    color: $mb-primary;
  }

  &__err {
    display: block;
    min-height: 32rpx;
    margin: 8rpx 6rpx 0;
    font-size: 24rpx;
    color: $mb-danger;
  }
}
</style>
