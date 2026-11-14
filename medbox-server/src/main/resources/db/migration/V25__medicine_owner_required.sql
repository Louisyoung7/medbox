-- V25 · 药品归属收敛为老人私有（取消公共药品字典）
-- 存量公共药品（owner_elder_id 为空）按口径直接删除，再把归属收紧为非空。
DELETE FROM medicine WHERE owner_elder_id IS NULL;

ALTER TABLE medicine ALTER COLUMN owner_elder_id SET NOT NULL;

COMMENT ON TABLE medicine IS '药品档案（纯手工记录，归属老人必填，无公共字典）';
COMMENT ON COLUMN medicine.owner_elder_id IS '归属老人业务 ID（必填，创建后不可变）';
