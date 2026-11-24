/**
 * 版本号读取、更新判定与下载跳转（`feat/app-release`）。
 *
 * <p>纯逻辑集中在这里，便于单测；带 `uni` / `plus` 副作用的只有 {@link getLocalVersion} 与
 * {@link openDownloadUrl}，两者都按“能力探测”处理，H5 上不报错。
 */

/**
 * 是否运行在 App（APK）环境。
 *
 * <p>用 `plus` 能力探测而不是 `process.env.UNI_PLATFORM`：单测与 H5 都没有 `plus`，
 * 判定为 false 即可，不需要为各平台维护分支表。
 *
 * @returns {boolean}
 */
export function isAppRuntime() {
  return typeof plus !== 'undefined' && !!plus && !!plus.runtime
}

/**
 * 读取本机安装包版本。
 *
 * <p>App 端 `uni.getSystemInfoSync()` 返回 `appVersion`（versionName）与 `appVersionCode`
 * （Android versionCode，与 manifest 同口径）；H5 上两者缺失，返回 0 由调用方降级。
 *
 * @returns {{versionName: string, versionCode: number}}
 */
export function getLocalVersion() {
  let info = {}
  try {
    info = typeof uni !== 'undefined' && typeof uni.getSystemInfoSync === 'function'
      ? uni.getSystemInfoSync() || {}
      : {}
  } catch (e) {
    info = {}
  }
  const code = Number(info.appVersionCode)
  return {
    versionName: typeof info.appVersion === 'string' ? info.appVersion : '',
    versionCode: Number.isFinite(code) && code > 0 ? code : 0,
  }
}

/**
 * 展示用版本文本，如 `1.0.0（100）`。
 *
 * @param {{versionName?: string, versionCode?: number}} [version]
 * @returns {string}
 */
export function formatLocalVersion(version) {
  if (!version || !version.versionName) {
    return '未知'
  }
  return version.versionCode ? `${version.versionName}（${version.versionCode}）` : version.versionName
}

/**
 * 是否真的有新版本。
 *
 * <p>以**版本号大小**判断，不只看后端 `upToDate`：客户端 versionCode 缺失（0）或后端没收到参数时，
 * `upToDate` 恒为 false，若直接弹窗会对已是最新的用户反复提示。
 *
 * @param {{latestVersionCode?: number, upToDate?: boolean}} [info] `GET /app/version` 的 data
 * @param {{versionCode?: number}} [local] 本机版本
 * @returns {boolean}
 */
export function hasUpdate(info, local) {
  if (!info || info.upToDate !== false) {
    return false
  }
  const latest = Number(info.latestVersionCode)
  if (!Number.isFinite(latest) || latest <= 0) {
    return false
  }
  const current = Number(local && local.versionCode) || 0
  return latest > current
}

/**
 * 更新弹窗正文：优先后端 `releaseNotes`，缺省给一句可读说明。
 *
 * @param {{releaseNotes?: string}} [info]
 * @returns {string}
 */
export function updateContent(info) {
  const notes = info && typeof info.releaseNotes === 'string' ? info.releaseNotes.trim() : ''
  return notes || '建议更新到最新版本，以获得更稳定的使用体验。'
}

/**
 * 用系统浏览器 / 应用商店打开 APK 下载地址。
 *
 * <p>APK 内用 `plus.runtime.openURL` 交给系统浏览器（下载、校验、安装都由系统完成，避免自建
 * 下载器踩 Android 8+ 安装未知来源与 FileProvider 的坑）；H5 联调时退化为新窗口。
 *
 * @param {string} url
 * @returns {boolean} 是否已发起跳转
 */
export function openDownloadUrl(url) {
  if (!url) {
    return false
  }
  if (isAppRuntime() && typeof plus.runtime.openURL === 'function') {
    plus.runtime.openURL(url)
    return true
  }
  if (typeof window !== 'undefined' && typeof window.open === 'function') {
    window.open(url, '_blank')
    return true
  }
  return false
}
