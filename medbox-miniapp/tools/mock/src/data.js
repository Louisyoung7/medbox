/**
 * Mock 后端的种子数据（medbox-miniapp/tools/mock，前端测试专用，不替代 medbox-server）。
 *
 * 字段与口径严格对照 medbox-spec：06 的 2.1~2.5（user / guardian_relation / device /
 * medicine / medicine_stock）、02 第 3~5 章（接口形状）、01 第 3 章（角色与权限）。
 *
 * 日期类种子值相对"运行时刻"计算，保证演示状态（正常 / 临期 / 过期 / 未填）不随日期漂移。
 */

/** 相对今天偏移 N 天的 YYYY-MM-DD。 */
function dateOffset(days) {
  const d = new Date()
  d.setDate(d.getDate() + days)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** 相对现在偏移 N 毫秒的 ISO-8601 UTC（后端时间一律 UTC，见 02 的 1.）。 */
function isoOffset(ms) {
  return new Date(Date.now() + ms).toISOString().replace(/\.\d{3}Z$/, 'Z')
}

const MINUTE = 60 * 1000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/** 演示账号统一密码（README 会列给测试同学）。 */
const DEMO_PASSWORD = '123456'

/**
 * 生成一份全新的内存状态（`/__mock__/reset` 与每次启动都调它）。
 *
 * @returns {Object} { users, relations, devices, medicines, stocks, refreshTokens }
 */
function createSeed() {
  const users = [
    { userId: 'u-1001', role: 'ELDER', name: '张桂英', phone: '13900000001', username: 'guiying', password: DEMO_PASSWORD, status: 'ACTIVE' },
    { userId: 'u-1002', role: 'ELDER', name: '李德海', phone: '13900000002', username: 'dehai', password: DEMO_PASSWORD, status: 'ACTIVE' },
    { userId: 'u-2001', role: 'GUARDIAN', name: '张伟', phone: '13800001234', username: 'wei888', password: DEMO_PASSWORD, status: 'ACTIVE' },
    { userId: 'u-2002', role: 'GUARDIAN', name: '王芳', phone: '13800001235', username: 'fangfang', password: DEMO_PASSWORD, status: 'ACTIVE' },
    { userId: 'u-2003', role: 'GUARDIAN', name: '周杰', phone: '13800001236', username: 'zhoujie', password: DEMO_PASSWORD, status: 'ACTIVE' },
  ]

  // 监护关系（06 的 2.2）：PENDING 用于验证 40302；REVOKED 用于验证"看不到任何数据"。
  // createdAt 为 UTC（02 的 3.2：pending 列表按 createdAt 升序、申请响应回带）
  const relations = [
    { relationId: 'gr-1', guardianId: 'u-2001', elderId: 'u-1001', role: 'FAMILY', status: 'ACTIVE', createdAt: isoOffset(-30 * DAY) },
    { relationId: 'gr-2', guardianId: 'u-2001', elderId: 'u-1002', role: 'FAMILY', status: 'ACTIVE', createdAt: isoOffset(-25 * DAY) },
    { relationId: 'gr-3', guardianId: 'u-2002', elderId: 'u-1001', role: 'FAMILY', status: 'PENDING', createdAt: isoOffset(-2 * DAY) },
    { relationId: 'gr-4', guardianId: 'u-2003', elderId: 'u-1001', role: 'FAMILY', status: 'REVOKED', createdAt: isoOffset(-10 * DAY) },
  ]

  // 设备（06 的 2.3）：MAIN 主控 + 传感器子设备；子设备 elderId 与父一致
  const devices = [
    {
      deviceId: 'BOXA1001', deviceType: 'MAIN', elderId: 'u-1001',
      productKey: 'medbox-box', timezone: 'Asia/Shanghai',
      onlineState: 'ONLINE', lastHeartbeat: isoOffset(-2 * MINUTE),
      createdAt: isoOffset(-30 * DAY),
    },
    {
      deviceId: 'BOXA1001-S-TH01', deviceType: 'TEMP_HUMI', parentDeviceId: 'BOXA1001', elderId: 'u-1001',
      onlineState: 'ONLINE', lastHeartbeat: isoOffset(-2 * MINUTE),
      createdAt: isoOffset(-30 * DAY),
    },
    {
      deviceId: 'BOXA1001-S-LGT01', deviceType: 'LIGHT', parentDeviceId: 'BOXA1001', elderId: 'u-1001',
      onlineState: 'OFFLINE', lastHeartbeat: isoOffset(-3 * HOUR),
      createdAt: isoOffset(-30 * DAY),
    },
    {
      deviceId: 'BOXA1001-S-CAM01', deviceType: 'CAMERA', parentDeviceId: 'BOXA1001', elderId: 'u-1001',
      onlineState: 'ONLINE', lastHeartbeat: isoOffset(-2 * MINUTE),
      createdAt: isoOffset(-30 * DAY),
    },
    {
      deviceId: 'BOXA1002', deviceType: 'MAIN', elderId: 'u-1002',
      productKey: 'medbox-box', timezone: 'Asia/Shanghai',
      onlineState: 'OFFLINE', lastHeartbeat: isoOffset(-3 * HOUR),
      createdAt: isoOffset(-20 * DAY),
    },
    {
      deviceId: 'BOXA1002-S-TH01', deviceType: 'TEMP_HUMI', parentDeviceId: 'BOXA1002', elderId: 'u-1002',
      onlineState: 'OFFLINE', lastHeartbeat: isoOffset(-3 * HOUR),
      createdAt: isoOffset(-20 * DAY),
    },
    {
      deviceId: 'BOXA1002-S-CAM01', deviceType: 'CAMERA', parentDeviceId: 'BOXA1002', elderId: 'u-1002',
      onlineState: 'OFFLINE', lastHeartbeat: isoOffset(-3 * HOUR),
      createdAt: isoOffset(-20 * DAY),
    },
  ]

  // 设备事件流水（06 的 2.16）：REGISTERED/ONLINE/OFFLINE 由 device-core 写；
  // FAULT/COMMAND 由 device-fault/device-command 续写（后端 device-command/device-fault 在 07 尚未落地）。
  // Mock 播种样例数据，让"上下线 / 心跳异常 / 设备侧异常"可读；真实 FAULT 摄入走后端 MQTT
  // up/event/error，Mock 不接 EMQX，只给样例；命令下发（POST /commands）会即时追加一条 COMMAND 事件。
  const events = []
  // 每台 MAIN 主控：登记 + 上线/离线（用 lastHeartbeat 作时间，与设备状态一致）
  devices
    .filter((d) => d.deviceType === 'MAIN')
    .forEach((d) => {
      events.push({ eventId: `ev-${d.deviceId}-reg`, deviceId: d.deviceId, eventType: 'REGISTERED', detail: null, createdAt: d.createdAt })
      events.push({
        eventId: `ev-${d.deviceId}-ol`,
        deviceId: d.deviceId,
        eventType: d.onlineState === 'ONLINE' ? 'ONLINE' : 'OFFLINE',
        detail: null,
        createdAt: d.lastHeartbeat || d.createdAt,
      })
    })
  // 种子若干"设备侧异常"（FAULT）与一条历史命令（COMMAND），使事件列表可读
  events.push({ eventId: 'ev-1001-door', deviceId: 'BOXA1001', eventType: 'FAULT', detail: 'DOOR_TIMEOUT', createdAt: isoOffset(-3 * HOUR) })
  events.push({ eventId: 'ev-1001-hw', deviceId: 'BOXA1001', eventType: 'FAULT', detail: 'HW_FAULT', createdAt: isoOffset(-1 * DAY) })
  events.push({ eventId: 'ev-1001-cmd', deviceId: 'BOXA1001', eventType: 'COMMAND', detail: { commandId: 'c-seed-1', cmd: 'BUZZ', state: 'SUCCESS' }, createdAt: isoOffset(-2 * DAY) })

  // 药品档案（06 的 2.4）：ownerElderId 为空 = 公共字典
  const medicines = [
    {
      medicineId: 'm-201', ownerElderId: '', name: '阿莫西林胶囊',
      spec: '0.25g×24粒', dosePerTime: '1', unit: '粒',
      contraindication: '青霉素过敏者禁用；服药期间忌酒',
      storageCond: '遮光、密封、阴凉处保存',
      createdAt: isoOffset(-25 * DAY),
    },
    {
      medicineId: 'm-202', ownerElderId: '', name: '阿托伐他汀钙片',
      spec: '20mg×7片', dosePerTime: '1', unit: '片',
      contraindication: '孕妇禁用；避免与葡萄柚汁同服',
      storageCond: '密封、干燥处',
      createdAt: isoOffset(-25 * DAY),
    },
    {
      medicineId: 'm-203', ownerElderId: '', name: '二甲双胍缓释片',
      spec: '0.5g×30片', dosePerTime: '1', unit: '片',
      contraindication: '肾功能不全者慎用',
      storageCond: '密封、干燥处',
      createdAt: isoOffset(-25 * DAY),
    },
    {
      medicineId: 'm-204', ownerElderId: 'u-1001', name: '硝苯地平控释片',
      spec: '30mg×7片', dosePerTime: '1', unit: '片',
      contraindication: '低血压患者禁用',
      storageCond: '遮光、密封保存',
      createdAt: isoOffset(-12 * DAY),
    },
    {
      medicineId: 'm-205', ownerElderId: 'u-1002', name: '布洛芬混悬液',
      spec: '100ml:2g', dosePerTime: '10', unit: 'ml',
      contraindication: '消化道溃疡患者禁用',
      storageCond: '阴凉处保存',
      createdAt: isoOffset(-12 * DAY),
    },
    {
      medicineId: 'm-206', ownerElderId: '', name: '氯雷他定片',
      spec: '10mg×6片', dosePerTime: '1', unit: '片',
      contraindication: '',
      storageCond: '遮光、密封',
      createdAt: isoOffset(-8 * DAY),
    },
  ]

  // 库存（06 的 2.5）：按"药箱 + 药品"，日期由监护人手动录入；故意覆盖四种临期状态
  const stocks = [
    { stockId: 'st-1', deviceId: 'BOXA1001', elderId: 'u-1001', medicineId: 'm-201', quantity: 24, productionDate: dateOffset(-45), expiryDate: dateOffset(60), updatedAt: isoOffset(-2 * DAY) },
    { stockId: 'st-2', deviceId: 'BOXA1001', elderId: 'u-1001', medicineId: 'm-202', quantity: 7, productionDate: dateOffset(-35), expiryDate: dateOffset(10), updatedAt: isoOffset(-2 * DAY) },
    { stockId: 'st-3', deviceId: 'BOXA1001', elderId: 'u-1001', medicineId: 'm-203', quantity: 30, productionDate: dateOffset(-60), expiryDate: dateOffset(-5), updatedAt: isoOffset(-1 * DAY) },
    { stockId: 'st-4', deviceId: 'BOXA1001', elderId: 'u-1001', medicineId: 'm-204', quantity: 14, productionDate: '', expiryDate: '', updatedAt: isoOffset(-1 * DAY) },
    { stockId: 'st-5', deviceId: 'BOXA1002', elderId: 'u-1002', medicineId: 'm-205', quantity: 2, productionDate: dateOffset(-20), expiryDate: dateOffset(45), updatedAt: isoOffset(-3 * DAY) },
    { stockId: 'st-6', deviceId: 'BOXA1002', elderId: 'u-1002', medicineId: 'm-206', quantity: 6, productionDate: dateOffset(-10), expiryDate: dateOffset(70), updatedAt: isoOffset(-3 * DAY) },
    { stockId: 'st-7', deviceId: 'BOXA1002', elderId: 'u-1002', medicineId: 'm-201', quantity: 12, productionDate: dateOffset(-15), expiryDate: dateOffset(30), updatedAt: isoOffset(-3 * DAY) },
  ]

  // 服药计划（06 的 2.6 / 2.6.1）：times 为设备本地墙钟；覆盖 ENABLED / DISABLED、
  // 单药 / 多药 / 带备注 / 多时间 / 两位老人 / DAILY / WEEKLY
  const plans = [
    {
      planId: 'p-3301', elderId: 'u-1001', name: '早餐后',
      times: ['08:00'], repeatRule: 'DAILY',
      status: 'ENABLED', syncState: 'SYNCED_TO_DEVICE',
      createdAt: isoOffset(-10 * DAY),
    },
    {
      planId: 'p-3302', elderId: 'u-1001', name: '睡前',
      times: ['21:30'], repeatRule: 'DAILY',
      status: 'DISABLED', syncState: 'SYNCED_TO_DEVICE',
      createdAt: isoOffset(-10 * DAY),
    },
    {
      planId: 'p-3303', elderId: 'u-1002', name: '早晚各一次',
      times: ['08:00', '20:00'], repeatRule: 'WEEKLY',
      status: 'ENABLED', syncState: 'SYNCED_TO_DEVICE',
      createdAt: isoOffset(-6 * DAY),
    },
  ]

  // 计划明细：一次提醒可含多种药；sortOrder 即展示顺序
  const planItems = [
    { itemId: 'pi-9001', planId: 'p-3301', medicineId: 'm-201', dose: '1', unit: '粒', note: '', sortOrder: 0 },
    { itemId: 'pi-9002', planId: 'p-3301', medicineId: 'm-202', dose: '1', unit: '片', note: '', sortOrder: 1 },
    { itemId: 'pi-9003', planId: 'p-3302', medicineId: 'm-204', dose: '1', unit: '片', note: '嚼服', sortOrder: 0 },
    { itemId: 'pi-9004', planId: 'p-3303', medicineId: 'm-205', dose: '10', unit: 'ml', note: '摇匀后服', sortOrder: 0 },
  ]

  // 服药记录（06 的 2.7）：planTime / actualTime 一律 UTC；覆盖按时 / 未按时 / 错服 / 已确认 / 两位老人。
  // captureId 部分有值，但 capture-core 属 P1，Mock 只存 id，缩略图由前端留空占位（feat/mp-capture 接入）。
  const records = [
    {
      recordId: 'rec-9001', elderId: 'u-1001', planId: 'p-3301', planItemId: 'pi-9001', medicineId: 'm-201',
      deviceId: 'BOXA1001', captureId: '', actualDose: '1',
      planTime: isoOffset(-1 * DAY - 8 * HOUR), actualTime: isoOffset(-1 * DAY - 8 * HOUR + 5 * MINUTE),
      onTime: true, wrongDrug: false, confirmedBy: '', confirmedAt: '',
    },
    {
      recordId: 'rec-9002', elderId: 'u-1001', planId: 'p-3301', planItemId: 'pi-9002', medicineId: 'm-202',
      deviceId: 'BOXA1001', captureId: '', actualDose: '1',
      planTime: isoOffset(-1 * DAY - 8 * HOUR), actualTime: isoOffset(-1 * DAY - 8 * HOUR + 20 * MINUTE),
      onTime: true, wrongDrug: false, confirmedBy: '', confirmedAt: '',
    },
    {
      recordId: 'rec-9003', elderId: 'u-1001', planId: 'p-3302', planItemId: 'pi-9003', medicineId: 'm-204',
      deviceId: 'BOXA1001', captureId: '', actualDose: '1',
      planTime: isoOffset(-2 * DAY - 3 * HOUR), actualTime: isoOffset(-2 * DAY - 3 * HOUR + 45 * MINUTE),
      onTime: false, wrongDrug: false, confirmedBy: 'u-2001', confirmedAt: isoOffset(-2 * DAY),
    },
    {
      recordId: 'rec-9004', elderId: 'u-1001', planId: 'p-3301', planItemId: 'pi-9001', medicineId: 'm-201',
      deviceId: 'BOXA1001', captureId: '', actualDose: '1',
      planTime: isoOffset(-3 * DAY - 8 * HOUR), actualTime: isoOffset(-3 * DAY - 8 * HOUR + 60 * MINUTE),
      onTime: false, wrongDrug: true, confirmedBy: '', confirmedAt: '',
    },
    {
      recordId: 'rec-9005', elderId: 'u-1002', planId: 'p-3303', planItemId: 'pi-9004', medicineId: 'm-205',
      deviceId: 'BOXA1002', captureId: 'cap-5001', actualDose: '10',
      planTime: isoOffset(-1 * DAY - 8 * HOUR), actualTime: isoOffset(-1 * DAY - 8 * HOUR),
      onTime: true, wrongDrug: false, confirmedBy: '', confirmedAt: '',
    },
    {
      recordId: 'rec-9006', elderId: 'u-1002', planId: 'p-3303', planItemId: 'pi-9004', medicineId: 'm-205',
      deviceId: 'BOXA1002', captureId: '', actualDose: '10',
      planTime: isoOffset(-2 * DAY - 4 * HOUR), actualTime: isoOffset(-2 * DAY - 4 * HOUR),
      onTime: true, wrongDrug: false, confirmedBy: '', confirmedAt: '',
    },
  ]

  return { users, relations, devices, events, medicines, stocks, plans, planItems, records, refreshTokens: new Set() }
}

module.exports = { createSeed, DEMO_PASSWORD }
