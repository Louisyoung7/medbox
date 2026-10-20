-- V8 · 药品档案与库存业务号（07 清单 P0 第 6 项 `feat/medicine-core`）
-- 依据：medbox-spec/06_数据库设计.md 的 2.4 / 2.5
-- 约定：只新增序列，不改动 V1~V7（flyway.validate-on-migrate=true，改了会启动失败）

-- medicine_id 业务号生成器：'m-' || nextval('medicine_biz_id_seq')
-- 名字不能叫 medicine_id_seq：那是 medicine 表 id BIGSERIAL自动创建的序列（表名+列名+_seq），
-- IF NOT EXISTS 会静默跳过、取到自增主键的值（第一条药变成 m-1）。与 user_biz_id_seq同一个坑。
CREATE SEQUENCE IF NOT EXISTS medicine_biz_id_seq START 1001;

-- stock_id 业务号生成器：'s-' || nextval('medicine_stock_biz_id_seq')
-- 同理不能叫 medicine_stock_id_seq（撞本表 id BIGSERIAL 自动序列）
CREATE SEQUENCE IF NOT EXISTS medicine_stock_biz_id_seq START 1001;
