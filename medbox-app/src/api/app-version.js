/**
 * 客户端版本检查（`medbox-spec/02j`，`feat/app-release`）。
 *
 * <p>`GET /app/version` 免登录：App 启动、尚未登录时也要能查最新版本，因此固定 `auth: false`
 * （不注入 Bearer，避免带着过期 token 触发无谓的 40101 续期）。
 */

import { get } from '@/api/request.js'

/**
 * 查询最新版本。
 *
 * @param {number} [versionCode] 客户端当前 versionCode（Android manifest 同口径）
 * @param {Object} [options] 透传给请求层（如 `silent`）
 * @returns {Promise<{latestVersionName: string, latestVersionCode: number, downloadUrl: string, releaseNotes?: string, upToDate: boolean}>}
 */
export function getAppVersion(versionCode, options) {
  return get('/app/version', { versionCode }, { auth: false, ...options })
}
