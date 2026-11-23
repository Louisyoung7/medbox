/**
 * 依从性报告导出的跨端落地（`feat/app-runtime`，对应 medbox-spec/02 第 7 章 `GET /records/export`）。
 *
 * <p>后端返回文件流，`downloadFile` 只负责把内容落到临时文件；**真正给用户"拿到 CSV"** 分两端：
 * - H5：`<a download>` 触发浏览器下载（开发联调用，不对外部署）；
 * - App：复制到公共下载目录（`plus.io.PUBLIC_DOWNLOADS`），文件管理器可见。
 *
 * <p>判定与副作用分开：{@link pickExportMode} 是纯函数（单测覆盖），
 * {@link saveExportedFile} 才触达 `document` / `plus.io`。
 */

/** 后端没给 `Content-Disposition` 时的兜底文件名。 */
export const EXPORT_FALLBACK_NAME = 'medication-records.csv'

/**
 * 取导出文件名：优先后端 `filename`，空值回落 {@link EXPORT_FALLBACK_NAME}。
 * @param {{filename?:string}|null} res `downloadFile` 的兑现值
 * @returns {string}
 */
export function exportFilename(res) {
  const name = res && res.filename
  return name && String(name).trim() ? String(name).trim() : EXPORT_FALLBACK_NAME
}

/**
 * 选择落地方式（纯函数）：
 * - 有 `plus.io` → `app-downloads`（App 公共下载目录）
 * - 有 `document` → `h5-download`（浏览器 `<a download>`）
 * - 都没有 → `none`（如单测/未知容器，页面提示失败）
 *
 * @param {{hasPlusIo?:boolean, hasDocument?:boolean}} [env]
 * @returns {'app-downloads'|'h5-download'|'none'}
 */
export function pickExportMode(env = {}) {
  if (env.hasPlusIo) return 'app-downloads'
  if (env.hasDocument) return 'h5-download'
  return 'none'
}

/** 读取当前运行时的能力标志（副作用只在这里）。 */
export function runtimeExportEnv() {
  const hasPlusIo =
    typeof plus !== 'undefined' &&
    plus &&
    plus.io &&
    typeof plus.io.resolveLocalFileSystemURL === 'function'
  const hasDocument = typeof document !== 'undefined' && typeof document.createElement === 'function'
  return { hasPlusIo: Boolean(hasPlusIo), hasDocument }
}

/** H5：用 `<a download>` 触发下载。 */
function h5Download(src, filename) {
  const a = document.createElement('a')
  a.href = src
  a.download = filename
  a.style.display = 'none'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

/** App：把临时文件复制到公共下载目录，返回可读路径。 */
function appCopyToDownloads(src, filename) {
  const downloads = (plus.io && plus.io.PUBLIC_DOWNLOADS) || '_downloads/'
  return new Promise((resolve, reject) => {
    plus.io.resolveLocalFileSystemURL(
      src,
      (entry) => {
        plus.io.resolveLocalFileSystemURL(
          downloads,
          (dir) => {
            entry.copyTo(
              dir,
              filename,
              (copied) => resolve((copied && copied.fullPath) || downloads + filename),
              reject,
            )
          },
          reject,
        )
      },
      reject,
    )
  })
}

/**
 * 把导出结果交给当前平台落地。
 *
 * @param {{filePath?:string, tempFilePath?:string, filename?:string}} res `downloadFile` 的兑现值
 * @param {{hasPlusIo?:boolean, hasDocument?:boolean}} [env] 默认读运行时能力（测试可注入）
 * @returns {Promise<{mode:string, filename:string, path:string}>}
 *   `mode` 同 {@link pickExportMode}；`path` 为临时路径或下载目录路径
 */
export async function saveExportedFile(res, env) {
  const filename = exportFilename(res)
  const src = (res && (res.filePath || res.tempFilePath)) || ''
  if (!src) return { mode: 'none', filename, path: '' }

  const mode = pickExportMode(env || runtimeExportEnv())
  if (mode === 'h5-download') {
    h5Download(src, filename)
    return { mode, filename, path: src }
  }
  if (mode === 'app-downloads') {
    const path = await appCopyToDownloads(src, filename)
    return { mode, filename, path }
  }
  return { mode, filename, path: src }
}
