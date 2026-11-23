/**
 * 用户域接口（medbox-spec/02 第 3 章）—— 与 `api/auth.js` 的分界：**这里全是需鉴权的接口**。
 *
 * <p>`feat/mp-user-delete` 建立本文件的第一个接口 {@link deleteAccount}，`feat/mp-profile`
 * 补用户资料类接口 {@link getElderProfile}（`/users/me` 等归本的早已在 `api/auth.js` 的
 * `getMe`，不再搬迁）。
 *
 * <p>**为什么不放 `api/auth.js`**：那里清一色是**免鉴权**的凭证接口（登录 / 注册 / 刷新，
 * 都传 `auth: false`），而这里要带 Bearer；混在一起会让"`auth.js` 里的都不用登录"这条直觉失效。
 *
 * ```js
 * import { deleteAccount, getElderProfile } from '@/api/user.js'
 * await deleteAccount('******')        // 页面不要直接调，走 '@/store/auth.js' 的编排
 * await getElderProfile('u-1001')      // 监护人看老人基础信息（02 第 3 章）
 * ```
 */

import { get, post } from '@/api/request.js'

/**
 * 注销当前账号（不可逆，见文档 02 的 3.1）。
 *
 * <p>**必须传密码**：注销不可逆，服务端用 BCrypt 校验当前账号密码做二次确认 ——
 * 前端弹窗只是交互，**接口才是唯一入口**（拿到 access token 就能注销的话，丢一次手机足以毁号）。
 *
 * <p>**路径为什么是 `/users/me/delete`**：`/api/v1/auth/**` 在后端 `AuthInterceptor` 里是
 * **放行**的，而注销必须先登录，故不能放 `/auth/logout` 一类路径；动作型子路径 + `POST`，
 * 与 `PATCH /users/guardians/{relationId}/accept` 同风格。
 *
 * <p>**为什么不带 `noAuthRetry`**：它是正常鉴权接口 —— access token 过期时应该走请求层标准的
 * "refresh 续期 + 自动重发一次"（见 `src/store/auth.js` 的 `handleUnauthorized`），带上该选项
 * 反而会让过期用户直接被踢到登录页。
 *
 * <p>**为什么 `silent: true`**：错误要渲染在注销弹窗内部（红字），请求层再 toast 一次会重复；
 * `loading` 用自定义文案，注销是慢操作且不可打断。
 *
 * @param {string} password 当前账号明文密码（局域网阶段明文传，服务端 BCrypt 校验）
 * @returns {Promise<{userId: string, deletedAt: string}>} `deletedAt` 为 UTC（ISO-8601）
 * @throws {ApiError} 40001（密码为空）/ 40101（未登录或账号已注销）/ 40102（密码错误）/ -1（网络）
 */
export function deleteAccount(password) {
  return post('/users/me/delete', { password }, { silent: true, loading: '注销中' })
}

/**
 * 老人基础信息（`feat/mp-profile`，对应 medbox-spec/02 第 3 章，后端 `feat/user-profile` 已落地）。
 *
 * <p>响应结构与 `/users/me` 同构：`{userId, role, name, phone, username}`，**null 字段按后端
 * 全局 non_null 规则省略**（如老人无 `username` 则不返回该键），页面展示走
 * `src/utils/profile.js` 的 {@link profileFieldText} 兜底。
 *
 * <p>**可见性**：老人本人，或与老人存在 `ACTIVE` 监护关系的监护人（服务端按
 * `AccessService.require(elderId, READ_GUARDIAN)` 强校验）：
 * 关系 `PENDING` → **40302**（老人端尚未确认）；无关系 / `REVOKED` / 目标不是老人 → **40301**
 * （与"资源不存在"同码，防枚举）。
 *
 * <p>**当前唯一消费点**：`pages/guardian/guardian.vue` 的「我的申请」卡把 elderId 解析成
 * 老人姓名（`utils/profile.js` 的 {@link elderNameText}）。姓名是**增强信息**：调用方一律传
 * `silent: true`，40302 / 40301 / 网络失败**静默回退 elderId 原文**，不 toast、不弹错。
 *
 * @param {string} elderId 老人用户ID（`user_id`，如 `u-1001`）
 * @param {Object} [options] 透传请求选项（页面自绘时传 `silent: true`）
 * @returns {Promise<{userId: string, role: string, name?: string, phone?: string, username?: string}>}
 * @throws {ApiError} 40101 / 40301（无关系 / REVOKED / 目标非老人）/ 40302（PENDING）/ -1
 */
export function getElderProfile(elderId, options) {
  return get(`/users/${elderId}/profile`, undefined, options)
}
