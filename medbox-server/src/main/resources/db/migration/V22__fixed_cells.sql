-- V22 · 固定 3 药格模型（docs/cell-model → feat/backend-cell-model）
-- 依据：medbox-spec/06 的 2.6 / 2.6.x / 2.7 / 2.9 / 2.12，07 的 §1 / §2 / §4
-- 约定：只做结构变更，不回填历史数据（本地 dev 库按 AGENTS.md 3.2 重建）；
--       V1~V21 一律不改（spring.flyway.validate-on-migrate=true）。

-- 1. 计划：times → slots，一老人一计划（elder_id 唯一）
ALTER TABLE med_plan RENAME COLUMN times TO slots;
COMMENT ON COLUMN med_plan.slots IS '格号→开格时间 [{"cellNo":1,"time":"08:00"}]；设备本地墙钟；1~3 项、cellNo 不重复';
COMMENT ON TABLE med_plan IS '一老人一计划；slots 只描述几点开几号格，不关联药品';
CREATE UNIQUE INDEX IF NOT EXISTS uk_med_plan_elder ON med_plan (elder_id);

-- 2. 计划药品明细不再存在（表与其业务号序列一并删除）
DROP TABLE IF EXISTS med_plan_item;
DROP SEQUENCE IF EXISTS med_plan_item_biz_id_seq;

-- 3. 服药记录：药品 / 剂量 / 置信度 → 格号，重建幂等与扫描索引
DROP INDEX IF EXISTS uk_med_record_item_plan_time;
DROP INDEX IF EXISTS idx_med_record_plan_time;
ALTER TABLE med_record DROP COLUMN IF EXISTS plan_item_id;
ALTER TABLE med_record DROP COLUMN IF EXISTS medicine_id;
ALTER TABLE med_record DROP COLUMN IF EXISTS actual_dose;
ALTER TABLE med_record DROP COLUMN IF EXISTS confidence;
ALTER TABLE med_record ADD COLUMN IF NOT EXISTS cell_no INT;
COMMENT ON COLUMN med_record.cell_no IS '药格格号 1/2/3（早/中/晚）；缺失表示未识别';
COMMENT ON TABLE med_record IS '服药记录；一条=某计划某号格某次时间窗的一次取药事实，不带药品与剂量';
CREATE UNIQUE INDEX IF NOT EXISTS uk_med_record_cell_plan_time
  ON med_record (plan_id, cell_no, plan_time)
  WHERE plan_id IS NOT NULL AND cell_no IS NOT NULL AND plan_time IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_med_record_plan_cell_time
  ON med_record (plan_id, cell_no, plan_time);

-- 4. 抓拍留证：去药品 / 置信度，加格号
ALTER TABLE capture DROP COLUMN IF EXISTS medicine_id;
ALTER TABLE capture DROP COLUMN IF EXISTS confidence;
ALTER TABLE capture ADD COLUMN IF NOT EXISTS cell_no INT;
COMMENT ON COLUMN capture.cell_no IS '药格格号 1/2/3；服药上报必带，MANUAL 留证可空';

-- 5. 告警：关联药品 → 格号（MISS 用），去重键口径带格号
ALTER TABLE alarm DROP COLUMN IF EXISTS medicine_id;
ALTER TABLE alarm ADD COLUMN IF NOT EXISTS cell_no INT;
COMMENT ON COLUMN alarm.cell_no IS 'MISS 关联格号 1/2/3；其它类型为空';
COMMENT ON COLUMN alarm.dedup_key IS 'MISS=MISS:{planId}:{cellNo}:{slotUTC}；ENV / DEVICE_* 复用同一列';

-- 6. 提醒去重账本：加格号，去重键带格号防两格同时间互相吞
ALTER TABLE plan_reminder ADD COLUMN IF NOT EXISTS cell_no INT;
COMMENT ON COLUMN plan_reminder.cell_no IS '格号 1/2/3';
COMMENT ON COLUMN plan_reminder.dedup_key IS 'REMINDER:{planId}:{cellNo}:{slotUTC}';
