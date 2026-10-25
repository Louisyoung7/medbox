-- 依据：medbox-spec/06_数据库设计.md 的 2.12（capture 表由 V1 建好，本迁移只补业务号序列）
-- 约定：只新增序列，不改动 V1~V13（flyway.validate-on-migrate=true，改了会启动失败）
-- 注：当前最大版本为 V13（plan_reminder），故本迁移取 V14；**合并时若已存在更大版本，按 max+1 重排**。

-- capture_id 业务号生成器：'cap-' || nextval('capture_biz_id_seq')
-- 名字不能叫 capture_id_seq：capture 表的 capture_id 是 VARCHAR 业务号，不存在 BIGSERIAL 自动序列，
-- 但为与其他业务号（alarm_biz_id_seq / med_plan_biz_id_seq 等）命名约定一致，统一用 _biz_id_seq 后缀，防误用。
CREATE SEQUENCE IF NOT EXISTS capture_biz_id_seq START 1001;
