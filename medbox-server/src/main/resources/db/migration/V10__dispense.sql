-- feat/dispense-consume（07 清单 P0 第 9 步）：服药事件落 med_record + 库存扣减 + 业务幂等
-- 只新增列 / 序列 / 索引，不改 V1~V9（validate-on-migrate=true）。
-- 注意：06 文档里预留的 V10__admin_seed.sql（feat/backend-admin-cleanup）尚未落地，
-- 为避免与本文档冲突，该种子脚本若实现须顺延到 V11（见 CHANGELOG 说明）。

-- 2.7 服药记录：补 source 列（CAMERA 摄像头识别 / MANUAL 手工补录）。
-- 02 第 7 章 record-core 补录会写 MANUAL；设备上行带 source（CAMERA/MANUAL）。
-- 先建表时未含该列，这里补齐，避免下游再出一个迁移脚本。
ALTER TABLE med_record ADD COLUMN IF NOT EXISTS source VARCHAR(16) DEFAULT 'CAMERA';
COMMENT ON COLUMN med_record.source IS '服药来源：CAMERA 摄像头识别 / MANUAL 手工补录';

-- 业务号序列（独立序列，不碰 id BIGSERIAL 自动序列；命名切忌 med_record_id_seq，会与自增序列重名被 IF NOT EXISTS 静默跳过）
CREATE SEQUENCE IF NOT EXISTS med_record_biz_id_seq START 1001;

-- 业务幂等（第二层）：同一 plan_item 的同一计划时间只写一次。
-- 错服 / 补服命中即跳过写记录与扣库存（接受"老人当天同一槽真吃两次会被吞"的副作用）。
-- 部分唯一索引：plan_item_id / plan_time 任一为空时不约束（异常脏数据仍可落库）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_med_record_item_plan_time
  ON med_record (plan_item_id, plan_time)
  WHERE plan_item_id IS NOT NULL AND plan_time IS NOT NULL;

-- 按老人 + 时间倒序查记录（record-core 列表、监护端展示）。
CREATE INDEX IF NOT EXISTS idx_med_record_elder_time
  ON med_record (elder_id, actual_time DESC);

-- 按计划 + 计划时间查（miss-alarm 扫描"某槽有没有记录"）。
CREATE INDEX IF NOT EXISTS idx_med_record_plan_time
  ON med_record (plan_id, plan_time);
