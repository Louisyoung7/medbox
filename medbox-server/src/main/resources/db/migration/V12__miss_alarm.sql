-- feat/miss-alarm（07 清单 P0 第 10 步）：漏服告警业务号序列 + 告警去重键
-- 依据：medbox-spec/06_数据库设计.md 的 2.9（alarm 表）
-- 约定：只新增序列 / 列 / 索引，不改动 V1~V11（flyway.validate-on-migrate=true，改了会启动失败）
-- 注：V11 已被文档 06 预留给 feat/backend-admin-cleanup 的 admin 种子账号（V10__admin_seed.sql 顺延），
--     故本分支用 V12。若 admin 分支先落地 V11，本迁移（V12）编号仍在其后，不冲突。

-- 2.9 告警：补 alarm_id 业务号生成器（'a-' || nextval('alarm_biz_id_seq')）。
-- 命名切忌 alarm_id_seq：那是 alarm 表 id BIGSERIAL 自动创建的序列，复用会取到自增主键值（与 med_record 等同一个坑）。
CREATE SEQUENCE IF NOT EXISTS alarm_biz_id_seq START 1001;

-- 告警去重键：MISS 定时扫描会重复扫到同一已判定槽，用唯一索引保证"一个槽最多一条"。
-- 值形如 'MISS:p-1001:2026-10-22T16:00:00Z'（planId + 槽的 UTC 瞬时）。
-- 后续 ENV / DEVICE_OFFLINE 告警可复用同一列（各自前缀），统一收敛告警去重逻辑。
ALTER TABLE alarm ADD COLUMN IF NOT EXISTS dedup_key VARCHAR(128);
COMMENT ON COLUMN alarm.dedup_key IS '告警去重键；MISS=MISS:{planId}:{slotUTC}；ENV/DEVICE_OFFLINE 复用同一列';

-- 部分唯一索引：dedup_key 非空时才约束（异常脏数据允许为空落库，不阻断告警）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_alarm_dedup_key
  ON alarm (dedup_key)
  WHERE dedup_key IS NOT NULL;
