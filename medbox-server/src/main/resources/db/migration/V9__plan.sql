-- V9 · 服药计划业务号与同步状态列（07 清单 P0 第 7 项 `feat/plan-core`）
-- 依据：medbox-spec/06_数据库设计.md 的 2.6 / 2.6.1
-- 约定：只新增序列 / 列 / 索引，不改动 V1~V8（flyway.validate-on-migrate=true，改了会启动失败）
-- 说明：`med_plan` / `med_plan_item` 两张表由 V1 建好，本迁移只补"业务号序列"与 06 §2.6 遗漏的 `sync_state` 列。

-- plan_id 业务号生成器：'p-' || nextval('med_plan_biz_id_seq')
-- 名字不能叫 med_plan_id_seq：那是 med_plan 表 id BIGSERIAL 自动创建的序列，复用会取到自增主键的值。
-- 与 medicine-core 的 medicine_biz_id_seq 同一个坑。
CREATE SEQUENCE IF NOT EXISTS med_plan_biz_id_seq START 1001;

-- plan_item_id 业务号生成器：'pi-' || nextval('med_plan_item_biz_id_seq')
CREATE SEQUENCE IF NOT EXISTS med_plan_item_biz_id_seq START 1001;

-- 06 §2.6 的 DDL 漏了 sync_state 列，而 02 §6 的响应要回显 syncState（SYNCED_TO_DEVICE 等）。
-- 本迁移补列，默认 PENDING：plan-core 只负责把计划落库并置 PENDING；
-- 真正的 SYNCED_TO_DEVICE / SYNC_FAILED 状态机由后续 feat/plan-sync（下发到药箱）接管。
ALTER TABLE med_plan ADD COLUMN IF NOT EXISTS sync_state VARCHAR(16) DEFAULT 'PENDING';
COMMENT ON COLUMN med_plan.sync_state IS '计划下发状态；plan-core 仅置 PENDING，真实 SYNCED_TO_DEVICE/SYNC_FAILED 由 feat/plan-sync 接管';

-- 列表查询按老人过滤频繁，建一个索引（V1 没建）。
CREATE INDEX IF NOT EXISTS idx_plan_elder ON med_plan (elder_id);
