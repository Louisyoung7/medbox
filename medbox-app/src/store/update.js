/**
 * App 检查更新（`feat/app-release`，消费 `GET /app/version`）。
 *
 * <p>入口两处：`App.vue` 启动静默检查（仅 APK）、`pages/profile` 手动“检查更新”。
 * H5 联调不参与更新（{@link checkUpdate} 直接返回 `unsupported`，不发请求）。
 */

import { getAppVersion } from '@/api/app-version.js'
import {
  getLocalVersion,
  hasUpdate,
  isAppRuntime,
  openDownloadUrl,
  updateContent,
} from '@/utils/app-version.js'

/** 弹窗重入保护：启动检查与手动检查同时命中时只弹一次。 */
let prompting = false

/**
 * 查询是否有新版本（不弹窗）。
 *
 * @returns {Promise<{status: 'unsupported'|'latest'|'available'|'failed', info?: Object, local?: Object, error?: Error}>}
 *   `unsupported`：非 APK（H5）；`failed`：网络 / 后端不可用，调用方按静默处理
 */
export async function checkUpdate() {
  if (!isAppRuntime()) {
    return { status: 'unsupported' }
  }
  const local = getLocalVersion()
  try {
    const info = await getAppVersion(local.versionCode, { silent: true })
    return { status: hasUpdate(info, local) ? 'available' : 'latest', info, local }
  } catch (error) {
    return { status: 'failed', error, local }
  }
}

/**
 * 弹更新提示；“去下载”交给系统浏览器。
 *
 * @param {Object} info `GET /app/version` 的 data
 */
export function promptUpdate(info) {
  if (!info || prompting) {
    return
  }
  prompting = true
  const title = info.latestVersionName
    ? `发现新版本 ${info.latestVersionName}`
    : '发现新版本'
  uni.showModal({
    title,
    content: updateContent(info),
    confirmText: '去下载',
    cancelText: '稍后',
    success: (res) => {
      if (!res.confirm) {
        return
      }
      if (!openDownloadUrl(info.downloadUrl)) {
        uni.showToast({ title: '下载地址不可用，请稍后再试', icon: 'none' })
      }
    },
    complete: () => {
      prompting = false
    },
  })
}

/**
 * 启动时静默检查：有更新才弹窗，任何失败都不打扰用户。
 *
 * @returns {Promise<{status: string, info?: Object, local?: Object, error?: Error}>}
 */
export async function checkAndPromptUpdate() {
  const result = await checkUpdate()
  if (result.status === 'available') {
    promptUpdate(result.info)
  }
  return result
}
