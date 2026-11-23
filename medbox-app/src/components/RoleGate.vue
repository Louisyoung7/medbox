<template>
  <view v-if="visible"><slot /></view>
</template>

<script>
/**
 * 角色门（`feat/mp-ui-kit`）：`canAccess(当前角色, entry)` 通过才渲染默认插槽。
 * 与 `utils/permission.js` 的 `ENTRY_ROLES` 联动；未登记入口恒为可见。
 *
 * ```html
 * <RoleGate entry="alarmSettings"><!-- 仅监护人可见 --></RoleGate>
 * ```
 */
import { getRole } from '@/store/user.js'
import { canAccess } from '@/utils/permission.js'

export default {
  name: 'RoleGate',
  props: {
    entry: { type: String, required: true },
  },
  computed: {
    visible() {
      return canAccess(getRole(), this.entry)
    },
  },
}
</script>
