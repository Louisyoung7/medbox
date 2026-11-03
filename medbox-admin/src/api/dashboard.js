import request from './request'

// 监控看板聚合接口（medbox-spec/02 第 10.4 章，需 ADMIN 角色）。
// 全部只读聚合，由 AdminAuthInterceptor 校验 role==ADMIN（非 ADMIN → 40301），未登录 → 40101。
// request 已解包 R<T> 直接返回 body.data，错误走响应拦截器归一化 reject。

// 总览：设备网络状态 + 今日服药 + 老人/计划数（§10.4.1）
// 返回 { devices:{total,online,offline,mqttConnected}, todayDose:{totalSlots,taken,missed,pending}, elderCount, planCount }
export function getOverview() {
  return request.get('/admin/dashboard/overview')
}

// 设备网络状态：最近设备列表，按 last_heartbeat 倒序取最近（§10.4.2）
// 返回 List<{ deviceId, elderId, elderName, status, lastHeartbeat }>
export function getDevices(limit = 20) {
  return request.get('/admin/dashboard/devices', { params: { limit } })
}

// 服药依从率：趋势 / 漏服时段 / 按老人排行（§10.4.3）
// granularity: 'week' | 'month'，非法 → 40001
// 返回 { trend:[{bucket,totalSlots,taken,missed,rate}], missByHour:[{hour,count}], byElder:[{elderId,elderName,totalSlots,taken,missed,rate}] }
export function getAdherence(granularity = 'week') {
  return request.get('/admin/dashboard/adherence', { params: { granularity } })
}

// MQTT 消息流：上行/下行累计与近一分钟吞吐（§10.4.4）
// 返回 { upTotal, upLastMinute, downTotal, downLastMinute }
export function getMessages() {
  return request.get('/admin/dashboard/messages')
}
