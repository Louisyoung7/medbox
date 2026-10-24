// 前端安全护栏纯函数（可单测，见 tests/utils/guard.test.js）。
// 口径来自 medbox-spec/09 的「前端安全护栏」与 medbox-spec/02 第 10 章。

// 是否可清理：ACTIVE 账号一律拒绝（后端另有 40902 双保险，这里前端先收敛）。
export function isCleanable(row) {
  return !!row && row.status !== 'ACTIVE'
}

// 默认安全视图（护栏①）：只显 DELETED 或 test*/1380000* 测试号。
// 软删（DELETED）账号的 phone/username 已置 NULL，仅能靠 status 命中。
export function isSafeViewRow(row) {
  if (!row) return false
  if (row.status === 'DELETED') return true
  if (row.username && String(row.username).startsWith('test')) return true
  if (row.phone && String(row.phone).startsWith('1380000')) return true
  return false
}

export function filterSafeView(rows) {
  return (rows || []).filter(isSafeViewRow)
}

// 二次确认（护栏②）：手输值与目标 userId 完全一致才放行。
export function confirmMatch(input, targetUserId) {
  return String(input ?? '').trim() === String(targetUserId ?? '')
}

// 批量目标：当前视图内、通过安全视图且非 ACTIVE 的行（绝不会包含活跃真账号）。
export function collectBatchTargets(rows) {
  return (rows || []).filter((r) => isSafeViewRow(r) && isCleanable(r))
}
