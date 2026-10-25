<template>
  <view class="page">
    <!-- ① 服务器地址：运行时覆盖 HOST，写本地缓存，下次启动生效（真机调试不必重新编译） -->
    <MbCard title="服务器地址（HOST）">
      <MbCell title="当前地址" :value="currentHostText" />
      <view class="preview">
        <text class="preview__label">生效地址</text>
        <text class="preview__url">{{ previewUrl }}</text>
      </view>

      <MbField
        v-model="hostInput"
        label="后端 HOST"
        placeholder="局域网 IP 或域名，如 192.168.1.23"
      />

      <view class="actions">
        <MbButton class="actions__btn" type="primary" @click="onSave">保存</MbButton>
        <MbButton class="actions__btn" type="ghost" @click="onReset">恢复默认</MbButton>
      </view>

      <text class="hint">
        写入本机缓存后需重启小程序 / 重新进入才生效（BASE_URL 在启动时算定）。
        可粘贴整串地址，保存时自动剥掉协议、端口与路径；端口固定 {{ PORT }}。
      </text>
    </MbCard>

    <!-- ② 局域网联调说明：把"不校验合法域名"与真机调试流程搬进应用内，可复现 -->
    <MbCard title="局域网联调说明">
      <view class="step">
        <text class="step__no">1</text>
        <view class="step__body">
          <text class="step__title">微信开发者工具</text>
          <text class="step__text">
            详情 → 本地设置 → 勾选「不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书」，
            否则局域网的 http / ws 请求会被工具拦截。该勾选存在 project.private.config.json，属个人配置，不要提交。
          </text>
        </view>
      </view>

      <view class="step">
        <text class="step__no">2</text>
        <view class="step__body">
          <text class="step__title">真机预览 / 真机调试</text>
          <text class="step__text">
            真机默认强制校验域名并禁用 http。用「真机调试（开发版）」模式可忽略域名校验；
            或改走 App 端（uni-app 打包 Android / iOS）直连局域网 IP。手机需与后端在同一 WiFi。
          </text>
        </view>
      </view>

      <view class="step">
        <text class="step__no">3</text>
        <view class="step__body">
          <text class="step__title">只需配一个 HOST</text>
          <text class="step__text">
            小程序不直连 MQTT，设备消息由后端中转。REST 与 WebSocket 共用同一 HOST，
            故上面改一处即可同时切换两个地址。
          </text>
        </view>
      </view>
    </MbCard>

    <!-- ③ 后端调试端点（feat/backend-devtools）：没有真设备时在服务端造数据 / 模拟上报 -->
    <MbCard title="没有真设备？服务端模拟上报">
      <text class="ref">
        需先在 medbox-server 置 <text class="ref__code">medbox.devtools.enabled: true</text> 并重启；
        MQTT 类预设还要求 EMQX 已启动。
      </text>

      <MbField
        v-model="simDeviceId"
        label="设备 ID"
        placeholder="药箱主控 deviceId，如 BOXA-DEBUG-01"
      />

      <MbCell
        title="一键播种数据"
        label="监护人 / 老人 / 药箱 / 药品 / 库存 / 计划"
        value="POST /_debug/seed"
        arrow
        clickable
        @click="onSeed"
      />

      <!-- 播种结果：token 是本卡的核心产出，给出可复制入口 -->
      <view v-if="seedResult" class="seed">
        <view class="seed__row">
          <text class="seed__key">监护人</text>
          <text class="seed__val">{{ seedResult.guardianPhone }} · {{ seedResult.guardianUserId }}</text>
        </view>
        <view class="seed__row">
          <text class="seed__key">老人</text>
          <text class="seed__val">{{ seedResult.elderPhone }} · {{ seedResult.elderUserId }}</text>
        </view>
        <view class="seed__row">
          <text class="seed__key">设备</text>
          <text class="seed__val">{{ seedResult.deviceId }}</text>
        </view>
        <view class="seed__row">
          <text class="seed__key">guardianToken</text>
          <text class="seed__val seed__val--token" @click="copyText(seedResult.guardianToken)">{{ seedResult.guardianToken }}</text>
        </view>
        <view class="seed__row">
          <text class="seed__key">elderToken</text>
          <text class="seed__val seed__val--token" @click="copyText(seedResult.elderToken)">{{ seedResult.elderToken }}</text>
        </view>
        <text class="seed__hint">点击 token 复制；{{ seedResult.created ? '本次新建' : '种子已存在，跳过写入' }}</text>
      </view>

      <!-- 预设直接罗列，不再藏进 action sheet：7 项滚一下就可能漏看（实测踩过），
           漏发的那个在日志里毫无痕迹，会误判成"后端没收到" -->
      <text class="sim__title">模拟设备上报（POST /_debug/mqtt/publish，点哪行发哪条）</text>
      <MbCell
        v-for="item in simPresets"
        :key="item.key"
        :title="item.label"
        :label="item.desc"
        arrow
        clickable
        @click="onSendSim(item)"
      >
        <!-- 发之前就标清楚会不会落库：目前只有 dispense 有专属处理器，
              其余四个 topic 的后端分支未落地，发了也不改数据 -->
        <template #extra>
          <text class="tag" :class="item.effect === 'record' ? 'tag--live' : 'tag--log'">
            {{ item.effect === 'record' ? '落库' : '仅日志' }}
          </text>
        </template>
      </MbCell>

      <!-- 发送结果：给可核对的证据（topic / msgId），而不是只有一个toast -->
      <view v-if="lastSim" class="sim-result">
        <view class="sim-result__head">
          <text class="sim-result__ok">✓ 已发送</text>
          <text class="sim-result__label">{{ lastSim.label }}</text>
        </view>
        <view class="sim-result__row">
          <text class="sim-result__key">topic</text>
          <text class="sim-result__val">up/{{ lastSim.subTopic }} → {{ lastSim.deviceId }}</text>
        </view>
        <view v-if="lastSim.slotDate" class="sim-result__row">
          <text class="sim-result__key">计划槽</text>
          <text class="sim-result__val">{{ lastSim.slotDate }} 08:00</text>
        </view>
        <view class="sim-result__row">
          <text class="sim-result__key">msgId</text>
          <text class="sim-result__val sim-result__val--copy" @click="copyText(lastSim.msgId)">
            {{ lastSim.msgId }}
          </text>
        </view>
        <text class="sim-result__note" :class="lastSim.effect === 'record' ? 'is-live' : 'is-log'">
          {{ lastSim.effectNote }}
        </text>
        <view v-if="lastSim.effect === 'record'" class="sim-result__actions">
          <MbButton type="ghost" @click="goRecords">去记录页查看</MbButton>
        </view>
      </view>

      <text class="ref ref--foot">
        等价于 mosquitto_pub，报文与 medbox-spec/03 第 5 章示例同形态；完整用法见该章。
        msgId 可在后端日志里 grep 核对。
      </text>
      <text class="ref ref--foot">
        服药类每次点击都会换一个**新的计划时间槽**（后端 `uk_med_record_item_plan_time` 要求同槽只落一条，
        而种子只有 1 个计划项 × 每日 08:00，槽位有限），计数器记在本地、刷新不归零。
        <text v-if="nextSlotDate">下一个槽：{{ nextSlotDate }} 08:00。</text>
      </text>
    </MbCard>

    <MbTabBar />
  </view>
</template>

<script>
import MbCard from '@/components/MbCard.vue'
import MbCell from '@/components/MbCell.vue'
import MbField from '@/components/MbField.vue'
import MbButton from '@/components/MbButton.vue'
import MbTabBar from '@/components/MbTabBar.vue'
import { DEFAULT_HOST, PORT } from '@/config/index.js'
import { readServerHost, saveServerHost, resetServerHost, takeNextSlotOffset, readSlotSeq } from '@/utils/devtools.js'
import { seedDebugData, publishDeviceEvent } from '@/api/devtools.js'
import {
  SIM_DEFAULT_DEVICE_ID,
  SIM_EVENT_PRESETS,
  buildSimRequest,
  devtoolsErrorMessage,
  slotDateText,
} from '@/utils/devtools-sim.js'

export default {
  name: 'DevtoolsPage',
  components: { MbCard, MbCell, MbField, MbButton, MbTabBar },
  data() {
    return {
      hostInput: '',
      currentHost: '',
      // 卡片③：设备 ID（播种后回填后端返回的 deviceId）、播种结果、最近一次模拟上报的回执
      simDeviceId: SIM_DEFAULT_DEVICE_ID,
      // 预设直接进 data：模板只能访问实例属性，import 进来的常量不会自动可见
      simPresets: SIM_EVENT_PRESETS,
      seedResult: null,
      // 最近一次模拟上报的回执（null = 还没发过）：用于渲染下方结果面板
      lastSim: null,
      // 下一个计划时间槽会是哪天（计数器持久化，每次发送前移一天）
      nextSlotDate: '',
    }
  },
  computed: {
    /** 当前地址：已覆盖显示覆盖值，未覆盖显示编译期默认值（让用户知道现在连的是谁） */
    currentHostText() {
      return this.currentHost || `默认 ${DEFAULT_HOST}`
    },
    /** 按当前生效 HOST 拼出 REST 预览地址，改完保存即可对照 */
    previewUrl() {
      return `http://${this.currentHost || DEFAULT_HOST}:${PORT}/medbox/api/v1`
    },
  },
  onShow() {
    this.refreshCurrent()
    // 输入框只在为空时预填：用户正在编辑中的内容不被 onShow 冲掉
    if (!this.hostInput) {
      this.hostInput = this.currentHost
    }
    // 预告下一个计划时间槽（服药类每次点击换新槽，见 utils/devtools.js 的计数器）
    this.nextSlotDate = slotDateText(-readSlotSeq())
  },
  methods: {
    /** 重新读缓存里的覆盖值（保存 / 恢复后都要刷新展示） */
    refreshCurrent() {
      this.currentHost = readServerHost()
    },

    /**
     * 保存：内部先归一化（容忍粘贴整串地址），非法输入不写缓存并提示重填。
     *
     * <p>**保存后必须重启才生效**（`BASE_URL` 是模块加载时算的快照），所以 toast 要说清楚，
     * 否则用户会以为"改了没生效"。保存成功顺手把输入框收敛成归一化后的值。
     */
    onSave() {
      const ok = saveServerHost(this.hostInput)
      if (!ok) {
        uni.showToast({ title: '请输入有效的服务器地址', icon: 'none' })
        return
      }
      this.refreshCurrent()
      this.hostInput = this.currentHost
      uni.showToast({ title: '已保存，重启小程序后生效', icon: 'success' })
    },

    /** 恢复默认：清掉运行时覆盖，回落到 .env.local / .env 的编译期默认值 */
    onReset() {
      if (!this.currentHost) {
        uni.showToast({ title: `当前已是默认 ${DEFAULT_HOST}`, icon: 'none' })
        return
      }
      uni.showModal({
        title: '恢复默认地址',
        content: `将清除已保存的 ${this.currentHost}，回落到默认 ${DEFAULT_HOST}，重启后生效`,
        success: (res) => {
          if (!res.confirm) return
          resetServerHost()
          this.refreshCurrent()
          this.hostInput = ''
          uni.showToast({ title: '已恢复默认，重启后生效', icon: 'success' })
        },
      })
    },

    // ——— 卡片③：后端调试端点（_debug）———
    // 两个调用都传 silent：错误由本页用 devtoolsErrorMessage 归一后自行提示。
    // 请求层默认的 toast 只会说"服务端内部错误"，而"后端没开 devtools"这种最常见的原因
    // 会被它盖掉，用户完全不知道该改哪里。

    /**
     * 一键播种最小数据集，并把返回的 deviceId 回填到输入框（紧接着就能模拟上报）。
     *
     * <p>**不传 reset**：种子是幂等的（已存在则跳过写入），reset=true 会先清后种，
     * 属于破坏性操作，页面不提供这个入口。
     */
    async onSeed() {
      uni.showLoading({ title: '播种中', mask: true })
      try {
        const result = await seedDebugData(false, { silent: true })
        this.seedResult = result || null
        if (result && result.deviceId) {
          this.simDeviceId = result.deviceId
        }
        uni.showToast({
          title: result && result.created ? '已播种' : '种子已存在',
          icon: 'success',
        })
      } catch (err) {
        uni.showToast({ title: devtoolsErrorMessage(err), icon: 'none', duration: 3000 })
      } finally {
        uni.hideLoading()
      }
    },

    /**
     * 发一条模拟上报（预设已直接罗列在卡片里，点哪行发哪条）。
     *
     * <p>**为什么回显这么全**（topic / msgId / 效果说明）：早期只弹一个"已发送"toast，
     * 用户点了看不出任何变化 —— 而 7 个预设里只有 3 个服药类真落库，其余四个 topic 的
     * 后端分支（env-core / device-sensor / device-fault / inventory-consume）还没落地，
     * 发了就是"仅记日志"。不把这话说清楚，用户只会以为功能坏了。
     */
    async onSendSim(preset) {
      const deviceId = (this.simDeviceId || '').trim()
      if (!deviceId) {
        uni.showToast({ title: '请先填写设备 ID', icon: 'none' })
        return
      }
      const { subTopic, msgId, payload, slotDate } = buildSimRequest(
        preset.key,
        deviceId,
        preset.effect === 'record' ? takeNextSlotOffset() : 0,
      )
      uni.showLoading({ title: '发送中', mask: true })
      try {
        await publishDeviceEvent({ deviceId, subTopic, payload }, { silent: true })
        this.lastSim = {
          label: preset.label,
          subTopic,
          msgId,
          deviceId,
          effect: preset.effect,
          effectNote: preset.effectNote,
          slotDate,
        }
        this.nextSlotDate = slotDateText(-readSlotSeq())
        uni.showToast({
          title: preset.effect === 'record' ? '已发送，已落库' : '已发送（仅记日志）',
          icon: 'success',
        })
      } catch (err) {
        this.lastSim = null
        uni.showToast({ title: devtoolsErrorMessage(err), icon: 'none', duration: 3000 })
      } finally {
        uni.hideLoading()
      }
    },

    /** 跳记录页：该页 onShow 会 reload，回来即可看到新落的服药记录。 */
    goRecords() {
      uni.switchTab({ url: '/pages/record/record' })
    },

    /** 复制 token 到剪贴板（播种结果的用途就是拿去给 curl / 其它工具用）。 */
    copyText(text) {
      if (!text) return
      uni.setClipboardData({
        data: text,
        success: () => uni.showToast({ title: '已复制', icon: 'success' }),
      })
    },
  },
}
</script>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 24rpx calc(110rpx + env(safe-area-inset-bottom) + 24rpx);
  box-sizing: border-box;
}

/* 生效地址预览：等宽一行放得下整条 URL，超出省略 */
.preview {
  display: flex;
  align-items: center;
  padding: 16rpx 20rpx;
  margin-bottom: 20rpx;
  border-radius: $mb-radius-sm;
  background: $mb-bg;

  &__label {
    flex-shrink: 0;
    margin-right: 16rpx;
    font-size: 24rpx;
    color: $mb-text-3;
  }

  &__url {
    flex: 1;
    font-size: 24rpx;
    color: $mb-text-2;
    word-break: break-all;
  }
}

.actions {
  display: flex;
  margin-top: 4rpx;

  &__btn {
    flex: 1;

    & + & {
      margin-left: 20rpx;
    }
  }
}

.hint {
  display: block;
  margin-top: 16rpx;
  font-size: 24rpx;
  line-height: 1.6;
  color: $mb-text-3;
}

/* 联调说明：编号圆点 + 标题 + 正文 */
.step {
  display: flex;
  padding: 16rpx 0;

  & + & {
    border-top: 2rpx solid $mb-border;
  }

  &__no {
    flex-shrink: 0;
    width: 40rpx;
    height: 40rpx;
    margin-right: 16rpx;
    border-radius: 50%;
    background: $mb-primary-bg;
    color: $mb-primary;
    font-size: 24rpx;
    font-weight: 600;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  &__body {
    flex: 1;
    display: flex;
    flex-direction: column;
  }

  &__title {
    font-size: 28rpx;
    font-weight: 500;
    color: $mb-text;
    margin-bottom: 6rpx;
  }

  &__text {
    font-size: 26rpx;
    line-height: 1.6;
    color: $mb-text-3;
  }
}

.ref {
  display: block;
  font-size: 26rpx;
  line-height: 1.6;
  color: $mb-text-3;

  &--foot {
    margin-top: 12rpx;
  }

  &__code {
    padding: 2rpx 10rpx;
    border-radius: 8rpx;
    background: $mb-primary-bg;
    color: $mb-primary;
    font-size: 24rpx;
  }
}

/* 预设列表小标题：把"模拟设备上报"和上面的"一键播种"区分开 */
.sim__title {
  display: block;
  margin: 20rpx 0 8rpx;
  font-size: 26rpx;
  font-weight: 500;
  color: $mb-text-2;
}

/* 预设行右侧的效果标签：落库（主色）/ 仅日志（灰）—— 发之前就设好预期 */
.tag {
  flex-shrink: 0;
  margin-left: 16rpx;
  padding: 4rpx 14rpx;
  border-radius: $mb-radius-pill;
  font-size: 20rpx;

  &--live {
    background: $mb-primary-bg;
    color: $mb-primary;
  }

  &--log {
    background: $mb-bg;
    color: $mb-text-3;
  }
}

/* 发送结果面板：给可核对的证据，而不是只有一个 toast */
.sim-result {
  margin-top: 16rpx;
  padding: 20rpx;
  border-radius: $mb-radius-sm;
  background: $mb-bg;

  &__head {
    display: flex;
    align-items: center;
    margin-bottom: 12rpx;
  }

  &__ok {
    font-size: 26rpx;
    font-weight: 600;
    color: $mb-success;
    margin-right: 12rpx;
  }

  &__label {
    font-size: 26rpx;
    color: $mb-text;
  }

  &__row {
    display: flex;
    align-items: flex-start;
    padding: 4rpx 0;
  }

  &__key {
    flex-shrink: 0;
    width: 110rpx;
    font-size: 24rpx;
    color: $mb-text-3;
  }

  &__val {
    flex: 1;
    font-size: 24rpx;
    color: $mb-text-2;
    word-break: break-all;

    &--copy {
      color: $mb-primary;
    }
  }

  &__note {
    display: block;
    margin-top: 10rpx;
    font-size: 24rpx;
    line-height: 1.6;

    &.is-live {
      color: $mb-primary;
    }

    &.is-log {
      color: $mb-text-3;
    }
  }

  &__actions {
    margin-top: 14rpx;
  }
}

/* 播种结果：账号一行 + token 两行（token 可点，点击复制） */
.seed {
  padding: 16rpx 20rpx;
  margin: 16rpx 0 4rpx;
  border-radius: $mb-radius-sm;
  background: $mb-bg;

  &__row {
    display: flex;
    align-items: flex-start;
    padding: 6rpx 0;
  }

  &__key {
    flex-shrink: 0;
    width: 180rpx;
    font-size: 24rpx;
    color: $mb-text-3;
  }

  &__val {
    flex: 1;
    font-size: 24rpx;
    color: $mb-text-2;
    word-break: break-all;

    /* token 很长，限两行 + 省略，避免把卡片撑爆；点它可复制全文 */
    &--token {
      display: -webkit-box;
      -webkit-box-orient: vertical;
      -webkit-line-clamp: 2;
      overflow: hidden;
      color: $mb-primary;
    }
  }

  &__hint {
    display: block;
    margin-top: 8rpx;
    font-size: 22rpx;
    color: $mb-text-4;
  }
}
</style>
