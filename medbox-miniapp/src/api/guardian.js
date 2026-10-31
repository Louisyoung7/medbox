/**
 * 监护关系接口（`feat/mp-guardian`，对应 medbox-spec/02 第 3 章的 3.2）。
 *
 * <p>**后端 `guardian-relation` 已落地**（07 清单已勾选）：四条接口的路径、字段名、错误码
 * 均按 02 的 3.2 实现，**无前端先行契约**，联调以真后端为准。
 *
 * <p>**两步绑定**：① 监护人发起申请（{@link applyGuardian}）→ 关系 `PENDING`；
 * ② 老人端确认（{@link acceptGuardian}）→ 关系 `ACTIVE`，监护人才真正获得读写权限。
 * **监护关系无角色**：spec 已统一为单一 `GUARDIAN`（02 的 1.3 矩阵仅 `ELDER` / `GUARDIAN` 两行），
 * 确认时不再指定 `FAMILY` / `DOCTOR` / `NURSE`。**未确认前访问该老人数据返回 40302**
 * （{@link CODE.GUARDIAN_PENDING}）。
 *
 * <p>**没有"我发起的申请列表"接口**：后端只提供老人端的待确认列表（{@link listPendingGuardians}）。
 * 监护人侧"我的申请"是**设备本地视图**，由 `src/store/guardian.js` 维护（申请响应即时写入），
 * 详见该文件的边界说明。
 *
 * ```js
 * import { applyGuardian, acceptGuardian } from '@/api/guardian.js'
 * await applyGuardian('u-1001')                    // 监护人：输入老人用户ID 发起申请
 * await acceptGuardian('gr-3')                     // 老人：确认监护请求（无角色，确认即生效）
 * ```
 */

import { get, del, post, request } from '@/api/request.js'

/**
 * 发起监护申请（**监护人发起**，创建 `PENDING` 关系；**无请求体**）。
 *
 * <p>规则表（02 的 3.2，服务端强校验）：当前角色非 `GUARDIAN` → 40301；目标 `elderId`
 * 不是真实老人 → 40301（防枚举）；已有 `PENDING` / `ACTIVE` → 40901；已有 `REVOKED` →
 * **复活为 `PENDING`**（重新申请）。
 *
 * <p>**入参是老人的用户ID（`userId`）**：02 第 3 章没有"按手机号 / 用户名查找老人"的接口，
 * 页面输入框即 `elderId`（引导监护人向老人索取，见 `src/pages/guardian/guardian.vue`）。
 *
 * @param {string} elderId 老人用户ID（`user_id`，如 `u-1001`）
 * @param {Object} [options] 透传请求选项（页面自绘错误时传 `silent: true`）
 * @returns {Promise<Object>} 新建 / 复活的关系：
 *   `{relationId, elderId, guardianId, status:'PENDING', createdAt}`（`createdAt` 为 UTC）
 * @throws {ApiError} 40101 / 40301（非监护人或目标不是老人）/ 40901（已有进行中或已生效的关系）/ -1
 */
export function applyGuardian(elderId, options) {
  return post(`/users/${elderId}/guardians`, undefined, options)
}

/**
 * 老人端待确认列表（**老人可用**，join 带监护人姓名 + 手机，按 `createdAt` 升序）。
 *
 * <p>非 `ELDER` 角色调用返回 40301；返回值为**裸数组**（无分页），解析走
 * `src/utils/guardian.js` 的 {@link parsePendingList}（对 `{list}` 包装做容错）。
 *
 * @param {Object} [options] 透传请求选项（页面自绘错误时传 `silent: true`）
 * @returns {Promise<Array<Object>|Object|null>} 每项
 *   `{relationId, elderId, guardianId, guardianName, guardianPhone, createdAt}`
 * @throws {ApiError} 40101 / 40301（非老人）/ -1
 */
export function listPendingGuardians(options) {
  return get('/users/guardians/pending', undefined, options)
}

/**
 * 确认监护请求（**老人本人**，置 `ACTIVE`）。
 *
 * <p>监护关系**无角色**（spec 统一为单一 `GUARDIAN`），确认即生效，无需指定角色。
 * 关系查不到或非本人 → 40301（防枚举）；已非 `PENDING` → 40901。
 *
 * @param {string} relationId 关系编号（`relation_id`，如 `gr-3`）
 * @param {Object} [options] 透传请求选项（弹窗提交场景传 `silent: true`）
 * @returns {Promise<Object>} 确认后的关系：
 *   `{relationId, elderId, guardianId, status:'ACTIVE', createdAt}`
 * @throws {ApiError} 40101 / 40301（查不到或非本人）/
 *   40901（已非 PENDING）/ -1
 */
export function acceptGuardian(relationId, options) {
  return request({
    url: `/users/guardians/${relationId}/accept`,
    method: 'PATCH',
    data: {},
    ...(options || {}),
  })
}

/**
 * 拒绝 / 解除监护关系（**关系任一方**可调；软删置 `REVOKED`，**幂等**）。
 *
 * <p>老人端"拒绝"待确认请求与监护人"撤销"申请走同一接口；关系查不到 → 40301（防枚举），
 * 当前用户既非老人也非监护人 → 40301。已是 `REVOKED` 时服务端幂等跳过（仍返回 `code=0`）。
 *
 * <p>⚠️ **对已 `ACTIVE` 的关系调用即真解除**：`src/store/guardian.js` 的"移除记录"因此
 * **不调本接口**（老人可能已确认，本地列表状态过期），只清本地缓存。
 *
 * @param {string} relationId 关系编号
 * @param {Object} [options] 透传请求选项（页面自绘错误时传 `silent: true`）
 * @returns {Promise<null>} 成功时无 `data`
 * @throws {ApiError} 40101 / 40301（查不到或非关系双方）/ -1
 */
export function revokeGuardian(relationId, options) {
  return del(`/users/guardians/${relationId}`, undefined, options)
}
