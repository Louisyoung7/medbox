/**
 * "我的申请"本地缓存（`feat/mp-guardian`）—— 监护人发起的监护申请在本机的记录。
 *
 * <p>**为什么需要本地缓存**：后端只有老人端的待确认列表（`GET /users/guardians/pending`），
 * **没有"我发起的申请列表"接口**（07 的跨端依赖表里也没有）。监护人要看到自己发起过哪些
 * 申请、等待谁确认、能否撤销，只能在本机留一份。申请响应（`applyGuardian` 的返回）即时写入。
 *
 * <p>⚠️ **这是设备本地视图，不是服务端真相**：老人确认 / 拒绝后本机不会收到通知，列表里的
 * `PENDING` 可能已过期。由此派生两条操作语义（页面严格区分，见 `pages/guardian/guardian.vue`）：
 *
 * - **撤销申请**（仅本地状态为 PENDING 时显示）：调 `DELETE /users/guardians/{relationId}`
 *   真解除 + 清本地。服务端对 `REVOKED` 幂等，老人已拒绝时调用也安全；
 * - **移除记录**：只清本地缓存，**不调接口** —— 此时关系可能已被老人确认成 `ACTIVE`，
 *   误调 DELETE 会真的解除监护。
 *
 * <p>**与登录态的关系**：缓存属于"当前监护人"。`store/auth.js` 的 `clearSession()`
 * （登出 / 续期失败）会调 {@link clearMyApplications}，避免换账号后看到上一个账号的申请。
 */

/** 本地缓存的 storage key（与 `store/user.js` 的 `USER_INFO_KEY` 同风格，全端唯一）。 */
export const APPLICATIONS_KEY = 'guardianApplications'

/** 单条本地申请记录的形状（`loadMyApplications` 的返回元素）。 */
// { relationId: string, elderId: string, status: string, createdAt: string }

/**
 * 读取本机的申请列表（**同步**，页面 `onShow` 直接渲染，同 `store/user.js` 的 `getUserInfo`）。
 *
 * <p>容错：存储损坏 / 非数组 / 元素缺 `relationId` 一律按空列表处理（不抛异常，
 * 页面走空态）。
 *
 * @returns {Array<{relationId: string, elderId: string, status: string, createdAt: string}>}
 *   按申请时间**倒序**（最新在前）
 */
export function loadMyApplications() {
  const raw = uni.getStorageSync(APPLICATIONS_KEY)
  if (!raw || typeof raw !== 'string') {
    return []
  }
  let parsed = null
  try {
    parsed = JSON.parse(raw)
  } catch (err) {
    console.warn('[guardian] 申请缓存解析失败，按空列表处理', err)
    return []
  }
  if (!Array.isArray(parsed)) {
    return []
  }
  return parsed
    .filter((item) => item && typeof item.relationId === 'string' && item.relationId)
    .map((item) => ({
      relationId: item.relationId,
      elderId: typeof item.elderId === 'string' ? item.elderId : '',
      status: typeof item.status === 'string' ? item.status : 'PENDING',
      createdAt: typeof item.createdAt === 'string' ? item.createdAt : '',
    }))
}

/**
 * 写入一条申请记录（申请成功后调；**同 elderId 去重**，保留最新）。
 *
 * <p>为什么按 `elderId` 去重：一个监护人对同一老人只可能有一条未失效关系
 * （服务端 `REVOKED` → 重新申请会复活同一 `relationId`）。本地若残留旧条目（如换设备前
 * 的陈旧记录），以本次申请响应为准覆盖。
 *
 * @param {{relationId: string, elderId: string, status?: string, createdAt?: string}} relation
 *   `applyGuardian` 的响应（`status` 缺省按 `PENDING`）
 * @returns {Array<Object>} 写入后的列表（页面可直接用它渲染）
 */
export function addMyApplication(relation) {
  if (!relation || typeof relation.relationId !== 'string' || !relation.relationId) {
    return loadMyApplications()
  }
  const next = {
    relationId: relation.relationId,
    elderId: typeof relation.elderId === 'string' ? relation.elderId : '',
    status: typeof relation.status === 'string' && relation.status ? relation.status : 'PENDING',
    createdAt: typeof relation.createdAt === 'string' ? relation.createdAt : '',
  }
  const list = loadMyApplications().filter(
    (item) => item.relationId !== next.relationId && item.elderId !== next.elderId
  )
  list.unshift(next)
  save(list)
  return list
}

/**
 * 移除一条本地申请记录（撤销成功 / 用户点"移除记录"后调）。
 *
 * @param {string} relationId 关系编号
 * @returns {Array<Object>} 移除后的列表
 */
export function removeMyApplication(relationId) {
  const list = loadMyApplications().filter((item) => item.relationId !== relationId)
  save(list)
  return list
}

/**
 * 清空本机申请缓存（`store/auth.js` 的 `clearSession()` 调：登出 / 续期失败即清）。
 */
export function clearMyApplications() {
  uni.removeStorageSync(APPLICATIONS_KEY)
}

/** 统一落盘（同步写；写失败只告警不抛 —— 缓存是体验优化，不该阻断主流程）。 */
function save(list) {
  try {
    uni.setStorageSync(APPLICATIONS_KEY, JSON.stringify(list))
  } catch (err) {
    console.warn('[guardian] 申请缓存写入失败', err)
  }
}
