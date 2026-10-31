-- 监护人统一为单一 GUARDIAN 角色（文档 10 §1.2 / 06 §2.2）：guardian_relation 不再区分
-- 家属 / 医生 / 护理，删除 role 列。旧迁移 V1 已建该列，按规约不回改，新增本迁移清理。
ALTER TABLE guardian_relation DROP COLUMN IF EXISTS role;
