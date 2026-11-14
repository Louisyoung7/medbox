/**
 * Mock 后端的种子数据（medbox-miniapp/tools/mock，前端测试专用，不替代 medbox-server）。
 *
 * 字段与口径严格对照 medbox-spec：06 的 2.1~2.4（user / guardian_relation / device /
 * medicine）、02 第 3~5 章（接口形状）、01 第 3 章（角色与权限）。
 *
 * 日期类种子值相对"运行时刻"计算，保证演示数据不随日期漂移。
 */

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
 * @returns {Object} { users, relations, devices, medicines, plans, records, refreshTokens }
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
    { relationId: 'gr-1', guardianId: 'u-2001', elderId: 'u-1001', status: 'ACTIVE', createdAt: isoOffset(-30 * DAY) },
    { relationId: 'gr-2', guardianId: 'u-2001', elderId: 'u-1002', status: 'ACTIVE', createdAt: isoOffset(-25 * DAY) },
    { relationId: 'gr-3', guardianId: 'u-2002', elderId: 'u-1001', status: 'PENDING', createdAt: isoOffset(-2 * DAY) },
    { relationId: 'gr-4', guardianId: 'u-2003', elderId: 'u-1001', status: 'REVOKED', createdAt: isoOffset(-10 * DAY) },
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

  // 药品档案（06 的 2.4）：ownerElderId 必填（无公共字典）
  const medicines = [
    {
      medicineId: 'm-201', ownerElderId: 'u-1001', name: '阿莫西林胶囊',
      spec: '0.25g×24粒', dosePerTime: '1', unit: '粒',
      description: '主要成分：阿莫西林。适应症：敏感菌所致呼吸道、泌尿道感染。禁忌：对青霉素过敏者禁用。注意事项：服药期间忌酒。',
      storageCond: '遮光、密封、阴凉处保存',
      createdAt: isoOffset(-25 * DAY),
    },
    {
      medicineId: 'm-202', ownerElderId: 'u-1001', name: '阿托伐他汀钙片',
      spec: '20mg×7片', dosePerTime: '1', unit: '片',
      description: '主要成分：阿托伐他汀钙。适应症：高胆固醇血症。禁忌：孕妇及哺乳期妇女禁用。注意事项：避免与葡萄柚汁同服。',
      storageCond: '密封、干燥处',
      createdAt: isoOffset(-25 * DAY),
    },
    {
      medicineId: 'm-203', ownerElderId: 'u-1001', name: '二甲双胍缓释片',
      spec: '0.5g×30片', dosePerTime: '1', unit: '片',
      description: '主要成分：二甲双胍。适应症：2 型糖尿病。禁忌：肾功能不全者慎用。注意事项：随餐服用，定期复查肾功能。',
      storageCond: '密封、干燥处',
      createdAt: isoOffset(-25 * DAY),
    },
    {
      medicineId: 'm-204', ownerElderId: 'u-1001', name: '硝苯地平控释片',
      spec: '30mg×7片', dosePerTime: '1', unit: '片',
      description: '主要成分：硝苯地平。适应症：高血压、心绞痛。禁忌：低血压患者禁用。注意事项：整片吞服，不可掰开。',
      storageCond: '遮光、密封保存',
      createdAt: isoOffset(-12 * DAY),
    },
    {
      medicineId: 'm-205', ownerElderId: 'u-1002', name: '布洛芬混悬液',
      spec: '100ml:2g', dosePerTime: '10', unit: 'ml',
      description: '主要成分：布洛芬。适应症：退热、镇痛。禁忌：消化道溃疡患者禁用。注意事项：餐后服用。',
      storageCond: '阴凉处保存',
      createdAt: isoOffset(-12 * DAY),
    },
    {
      medicineId: 'm-206', ownerElderId: 'u-1001', name: '氯雷他定片',
      spec: '10mg×6片', dosePerTime: '1', unit: '片',
      description: '',
      storageCond: '遮光、密封',
      createdAt: isoOffset(-8 * DAY),
    },
  ]

  // 服药计划（02d / 06 的 2.6）：只描述「几点开几号格」，格号 1/2/3 固定对应早/中/晚；
  // slots[].time 为设备本地墙钟；不关联药品、剂量。缺失的格号 = 该格不排程。
  const plans = [
    {
      planId: 'p-3301', elderId: 'u-1001', name: '日常服药',
      slots: [
        { cellNo: 1, time: '08:00' },
        { cellNo: 2, time: '12:30' },
        { cellNo: 3, time: '18:00' },
      ],
      repeatRule: 'DAILY',
      status: 'ENABLED', syncState: 'SYNCED_TO_DEVICE',
      createdAt: isoOffset(-10 * DAY),
    },
    {
      planId: 'p-3302', elderId: 'u-1001', name: '睡前',
      slots: [{ cellNo: 3, time: '21:30' }],
      repeatRule: 'DAILY',
      status: 'DISABLED', syncState: 'SYNCED_TO_DEVICE',
      createdAt: isoOffset(-10 * DAY),
    },
    {
      planId: 'p-3303', elderId: 'u-1002', name: '早晚各一次',
      slots: [
        { cellNo: 1, time: '08:00' },
        { cellNo: 2, time: '20:00' },
      ],
      repeatRule: 'DAILY',
      status: 'ENABLED', syncState: 'SYNCED_TO_DEVICE',
      createdAt: isoOffset(-6 * DAY),
    },
  ]

  // 服药记录（02e / 06 的 2.7）：一条 = 某计划某号格某次时间窗内的一次取药事实；
  // 只带 cellNo / takenResult / source，不关联药品、剂量。
  const records = [
    {
      recordId: 'rec-9001', elderId: 'u-1001', planId: 'p-3301', cellNo: 1,
      deviceId: 'BOXA1001', captureId: '',
      planTime: isoOffset(-1 * DAY - 8 * HOUR), actualTime: isoOffset(-1 * DAY - 8 * HOUR + 5 * MINUTE),
      onTime: true, takenResult: 'TAKEN', source: 'CAMERA',
    },
    {
      recordId: 'rec-9002', elderId: 'u-1001', planId: 'p-3301', cellNo: 2,
      deviceId: 'BOXA1001', captureId: '',
      planTime: isoOffset(-1 * DAY - 8 * HOUR), actualTime: isoOffset(-1 * DAY - 8 * HOUR + 20 * MINUTE),
      onTime: true, takenResult: 'TAKEN', source: 'CAMERA',
    },
    {
      recordId: 'rec-9003', elderId: 'u-1001', planId: 'p-3302', cellNo: 3,
      deviceId: 'BOXA1001', captureId: '',
      planTime: isoOffset(-2 * DAY - 3 * HOUR), actualTime: isoOffset(-2 * DAY - 3 * HOUR + 45 * MINUTE),
      onTime: false, takenResult: 'UNTAKEN', source: 'CAMERA',
    },
    {
      recordId: 'rec-9004', elderId: 'u-1001', planId: 'p-3301', cellNo: 1,
      deviceId: 'BOXA1001', captureId: '',
      planTime: isoOffset(-3 * DAY - 8 * HOUR), actualTime: isoOffset(-3 * DAY - 8 * HOUR + 60 * MINUTE),
      onTime: false, takenResult: 'UNTAKEN', source: 'MANUAL',
    },
    {
      recordId: 'rec-9005', elderId: 'u-1002', planId: 'p-3303', cellNo: 1,
      deviceId: 'BOXA1002', captureId: 'cap-5001',
      planTime: isoOffset(-1 * DAY - 8 * HOUR), actualTime: isoOffset(-1 * DAY - 8 * HOUR),
      onTime: true, takenResult: 'UNTAKEN', source: 'CAMERA',
    },
    {
      recordId: 'rec-9006', elderId: 'u-1002', planId: 'p-3303', cellNo: 2,
      deviceId: 'BOXA1002', captureId: '',
      planTime: isoOffset(-2 * DAY - 4 * HOUR), actualTime: isoOffset(-2 * DAY - 4 * HOUR),
      onTime: true, takenResult: 'TAKEN', source: 'CAMERA',
    },
  ]

  // 抓拍（02b_设备管理.md §1.1；capture-core 已落地，fileToken 由 /captures/{id} 端点签发）。
  // 只存元数据；fileToken 不在种子里（每次 GET 实时签发），原图二进制由 /file 端点返回占位图。
  // 关联 rec-9005（u-1002）的可视原图。
  const captures = [
    {
      captureId: 'cap-5001', deviceId: 'BOXA1002-S-CAM01', elderId: 'u-1002',
      cellNo: 1, planId: 'p-3303', reason: 'NORMAL',
      mime: 'image/png', sizeBytes: 48213, takenResult: 'UNTAKEN',
      capturedAt: isoOffset(-1 * DAY - 8 * HOUR), viewedAt: null, expireAt: isoOffset(29 * DAY),
    },
    {
      // 监护人主动留证（异常排查）：reason=MANUAL，可不带格号
      captureId: 'cap-5002', deviceId: 'BOXA1002-S-CAM01', elderId: 'u-1002',
      cellNo: null, planId: '', reason: 'MANUAL',
      mime: 'image/png', sizeBytes: 39120, takenResult: '',
      capturedAt: isoOffset(-3 * DAY), viewedAt: null, expireAt: isoOffset(27 * DAY),
    },
    {
      captureId: 'cap-5003', deviceId: 'BOXA1001-S-CAM01', elderId: 'u-1001',
      cellNo: 2, planId: 'p-3301', reason: 'NORMAL',
      mime: 'image/jpeg', sizeBytes: 51234, takenResult: 'UNTAKEN',
      capturedAt: isoOffset(-2 * DAY), viewedAt: null, expireAt: isoOffset(28 * DAY),
    },
    {
      // 已过期样例：expireAt 已在过去，/file 返回 404/401，前端提示"原图已过期"
      captureId: 'cap-5004', deviceId: 'BOXA1001-S-CAM01', elderId: 'u-1001',
      cellNo: null, planId: '', reason: 'MANUAL',
      mime: 'image/png', sizeBytes: 12030, takenResult: '',
      capturedAt: isoOffset(-40 * DAY), viewedAt: null, expireAt: isoOffset(-10 * DAY),
    },
  ]

  // 告警（02 第 8 章；alarm-core 已落地，但 Mock 不接 EMQX，按契约播种样例让列表可读）：
  // 覆盖 MISS（漏服）/ ENV（环境超标）；DEVICE_OFFLINE 类对应后端 alarm-device-offline
  // 在 07 仍为 [ ] 未落地，本期不播（列表对缺类自动少该类）。
  // readState: UNREAD / READ；handled: 是否已处理闭环。
  const alarms = [
    {
      alarmId: 'a-1001-miss-1', elderId: 'u-1001', planId: 'p-3301', type: 'MISS', level: 'WARN',
      message: '08:00 1 号格（早间）未按时取药',
      cellNo: 1, readState: 'UNREAD', handled: false,
      createdAt: isoOffset(-3 * HOUR),
    },
    {
      alarmId: 'a-1001-env-1', elderId: 'u-1001', type: 'ENV', level: 'WARN',
      message: '客厅药箱温度偏高：31℃ 超过上限 30℃',
      cellNo: null, readState: 'READ', handled: false,
      createdAt: isoOffset(-2 * DAY),
    },
    {
      alarmId: 'a-1001-miss-2', elderId: 'u-1001', planId: 'p-3302', type: 'MISS', level: 'WARN',
      message: '21:30 3 号格（晚间）未按时取药',
      cellNo: 3, readState: 'UNREAD', handled: false,
      createdAt: isoOffset(-5 * HOUR),
    },
    {
      alarmId: 'a-1002-miss-1', elderId: 'u-1002', planId: 'p-3303', type: 'MISS', level: 'WARN',
      message: '08:00 1 号格（早间）未按时取药',
      cellNo: 1, readState: 'UNREAD', handled: false,
      createdAt: isoOffset(-4 * HOUR),
    },
  ]

  // 告警阈值设置（02 第 8/9 章 + 06 §2.6.1）：每老人一行（elder_id UNIQUE）；GET 缺则给默认，PUT 合并。
  const alarmSettings = {
    'u-1001': {
      elderId: 'u-1001', advanceMin: 10, missTolerateMin: 15,
      envThreshold: {
        temp: { min: 15, max: 30 },
        humidity: { min: 30, max: 70 },
        light: { min: 50, max: 500 },
      },
    },
    'u-1002': {
      elderId: 'u-1002', advanceMin: 10, missTolerateMin: 15,
      envThreshold: {
        temp: { min: 15, max: 30 },
        humidity: { min: 30, max: 70 },
        light: { min: 50, max: 500 },
      },
    },
  }

  return { users, relations, devices, events, alarms, alarmSettings, medicines, plans, records, captures, envSamples: seedEnvSamples(), llmConfigs: seedLlmConfigs(), aiSchedules: [], refreshTokens: new Set() }
}

/**
 * 环境遥测种子（06 的 2.8 env_sample；feat/mp-env 联调用）：给 BOXA1001 的温湿度
 * （BOXA1001-S-TH01）与光照（BOXA1001-S-LGT01）子设备生成近 30 天、每小时一条的样本，
 * 温湿度随昼夜正弦波动、光照白天有值夜间为 0，使实时读数 / 历史曲线可读。真实 env-core 按
 * MQTT 上报入库（env-core 已落地），Mock 不接 EMQX，只给样例（仅演示用途）。
 *
 * @returns {Array} env_sample 行（device_id=传感器自身、gateway_device_id=主控）
 */
function seedEnvSamples() {
  const HOUR = 60 * 60 * 1000
  const totalHours = 30 * 24
  const samples = []
  for (let i = totalHours; i >= 0; i -= 1) {
    const t = Date.now() - i * HOUR
    const date = new Date(t)
    const hour = date.getHours()
    // 昼夜相位：0 点最低、12 点最高，幅度 1
    const phase = Math.sin((hour / 24) * 2 * Math.PI - Math.PI / 2)
    const temperature = Number((22 + phase * 5 + (Math.random() - 0.5) * 2).toFixed(2))
    const humidity = Number((50 + phase * 10 + (Math.random() - 0.5) * 5).toFixed(2))
    samples.push({
      sampleId: `es-th-${i}`,
      deviceId: 'BOXA1001-S-TH01',
      gatewayDeviceId: 'BOXA1001',
      elderId: 'u-1001',
      temperature,
      humidity,
      lux: null,
      sampleTime: date.toISOString().replace(/\.\d{3}Z$/, 'Z'),
    })
    const lux =
      hour >= 7 && hour <= 19
        ? Number((200 + phase * 300 + (Math.random() - 0.5) * 100).toFixed(2))
        : 0
    samples.push({
      sampleId: `es-lgt-${i}`,
      deviceId: 'BOXA1001-S-LGT01',
      gatewayDeviceId: 'BOXA1001',
      elderId: 'u-1001',
      temperature: null,
      humidity: null,
      lux,
      sampleTime: date.toISOString().replace(/\.\d{3}Z$/, 'Z'),
    })
  }
  return samples
}

/**
 * LLM 配置种子（05 的 FP-8 / 06 的 2.11 `llm_config`；feat/mp-ai-insight 联调用）。
 *
 * <p>给演示老人 `u-1001` 一份**已启用**的 BYOK 占位配置，使按需 / 周期洞察的 happy path
 * 有洞察输出；未配置或 Key 无效的老人由 Mock 返回 `50310`（"AI 助手暂未开通"）。真实
 * `ai-config`（监护人代配 Key）归后端 `feat/ai-insight` 与小程序 `feat/mp-ai-config`，本期未落地。
 *
 * <p>⚠️ 仅演示用途：明文 `apiKey` 只为让 Mock / e2e 跑通契约；真后端按 05 第 2.1 章用 AES 加密存储、回显脱敏。
 *
 * @returns {Array} llm_config 行（按 `elderId`）
 */
function seedLlmConfigs() {
  return [
    {
      elderId: 'u-1001',
      provider: 'openai-compatible',
      apiKey: 'sk-mock-ai-insight-demo',
      model: 'gpt-4o-mini',
      baseUrl: 'https://api.openai.com/v1',
      enabled: true,
    },
  ]
}

module.exports = { createSeed, DEMO_PASSWORD }
