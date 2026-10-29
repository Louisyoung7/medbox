/**
 * 库存域纯函数（`feat/mp-stock`，对应 medbox-spec/02 第 5 章、06 的 2.5）。
 *
 * <p>只放与页面解耦的可单测逻辑：库存清单容错解析、药品 join、数量文案、
 * 录入与补药的请求体组装。
 */



const text = (value) => (value == null ? '' : String(value).trim())

/** 转正整数；非法（空 / 小数 / 非数字 / ≤0）返回 null。 */
function toPositiveInt(value) {
  const raw = text(value)
  if (!raw) return null
  const n = Number(raw)
  if (!Number.isInteger(n) || n <= 0) return null
  return n
}

/**
 * 库存清单容错解析。
 *
 * <p>⚠️ **后端 `medicine-core` 未落地**，`GET /devices/{id}/stocks` 的响应形状未定：
 * 02 只描述了"按药品：数量、生产日期、有效期"。这里兼容裸数组与 `{list}` / `{records}` /
 * `{items}` 三种包装，后端定稿后（若不一致）只改这一处，页面不受影响。
 *
 * @param {Object|Array|null} data 请求层兑现的 `data`
 * @returns {Array<Object>} 库存行数组；解析失败按空数组处理
 */
export function parseStocks(data) {
  if (Array.isArray(data)) return data
  const src = data || {}
  if (Array.isArray(src.list)) return src.list
  if (Array.isArray(src.records)) return src.records
  if (Array.isArray(src.items)) return src.items
  return []
}

/**
 * 药品字典 → `medicineId -> medicine` 映射（供 {@link joinStockMedicine} 使用）。
 *
 * @param {Array<Object>} medicines 药品列表（`listMedicines` 兑现的 `list`）
 * @returns {Object} 映射；无 `medicineId` 的条目跳过
 */
export function toMedicineMap(medicines) {
  const map = {}
  if (!Array.isArray(medicines)) return map
  medicines.forEach((medicine) => {
    if (medicine && medicine.medicineId) map[medicine.medicineId] = medicine
  })
  return map
}

/**
 * 按 `medicineId` join 药品摘要（名称 / 规格 / 单位）。
 *
 * <p>`medicine_stock` 行没有药品名（06 的 2.5），页面用 `listMedicines` 建的 Map 在此 join；
 * 字典缺失（未拉到 / 超出首页 100 条）时**回退显示 `medicineId`**，不抛错、不发 N+1 请求。
 *
 * @param {Object} stock 库存行
 * @param {Object} [medicineMap] `medicineId -> medicine` 的映射
 * @returns {Object} 原库存行 + `name` / `spec` / `unit`（缺失回退）
 */
export function joinStockMedicine(stock, medicineMap) {
  const row = stock || {}
  const medicine = (medicineMap && medicineMap[row.medicineId]) || null
  return {
    ...row,
    name: (medicine && medicine.name) || row.medicineId || '未知药品',
    spec: (medicine && medicine.spec) || '',
    unit: (medicine && medicine.unit) || '',
  }
}

/**
 * 数量文案：如 `库存 24 粒`；无单位时 `库存 24`；数量缺失时 `库存 -`。
 *
 * @param {Object} stock 库存行
 * @param {Object} [medicineMap] 药品映射（取单位）
 * @returns {string}
 */
export function stockQuantityText(stock, medicineMap) {
  const row = joinStockMedicine(stock, medicineMap)
  const quantity = row.quantity == null || row.quantity === '' ? '-' : row.quantity
  return row.unit ? `库存 ${quantity} ${row.unit}` : `库存 ${quantity}`
}

/**
 * 录入 / 更新 payload（PUT，**数量为绝对值**）。
 *
 * 规则：trim；`quantity` 必须为正整数（否则 `ok:false`，页面先拦、40001 服务端兜底）；
 * 日期选填、空串省略（后端 `non_null` 下省略即不更新该列）。
 *
 * @param {Object} form `{ quantity, productionDate?, expiryDate? }`
 * @returns {{ok: true, payload: Object}|{ok: false, error: string}}
 */
export function buildStockPayload(form) {
  const src = form || {}
  const quantity = toPositiveInt(src.quantity)
  if (quantity == null) return { ok: false, error: '请输入正确的数量（正整数）' }
  const payload = { quantity }
  const productionDate = text(src.productionDate)
  if (productionDate) payload.productionDate = productionDate
  const expiryDate = text(src.expiryDate)
  if (expiryDate) payload.expiryDate = expiryDate
  return { ok: true, payload }
}

/**
 * 补药入库 payload（POST `.../in`，**数量为增量**，其余规则与 {@link buildStockPayload} 相同）。
 *
 * <p>单独导出一个名字是为了调用点语义自明（增量 vs 绝对值），实现复用同一份组装规则。
 *
 * @param {Object} form `{ quantity(增量), productionDate?, expiryDate? }`
 * @returns {{ok: true, payload: Object}|{ok: false, error: string}}
 */
export function buildRestockPayload(form) {
  return buildStockPayload(form)
}
