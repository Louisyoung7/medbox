-- V4 · 权限与监护关系校验（07 清单地基第 5 项 `feat/backend-authz`）
-- 依据：medbox-spec/06_数据库设计.md 的 2.2
-- 约定：只新增对象 / 列 / 索引，**不改动 V1~V3**（flyway.validate-on-migrate=true，改了会启动失败）

-- relation_id 业务号生成器：'r-' || nextval('guardian_relation_biz_id_seq')
--
-- 名字不能叫 guardian_relation_id_seq：**那是 guardian_relation 表 id BIGSERIAL 自动创建的序列**
-- （表名 + 列名 + _seq），CREATE SEQUENCE IF NOT EXISTS 会静默跳过，取到的会是自增主键的值
-- （第一条关系变成 r-1）。与 user_biz_id_seq 同一个坑，统一用 _biz_id_seq 后缀。
CREATE SEQUENCE IF NOT EXISTS guardian_relation_biz_id_seq START 1001;

ALTER TABLE guardian_relation ADD COLUMN IF NOT EXISTS relation_id VARCHAR(64);

-- 历史数据兜底：已有行按顺序补号（空表时无影响）
UPDATE guardian_relation
   SET relation_id = 'r-' || nextval('guardian_relation_biz_id_seq')
 WHERE relation_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_guardian_relation_biz ON guardian_relation (relation_id);

-- 正向判定：`where elder_id = ? and guardian_id = ?`（已有 UNIQUE(elder_id, guardian_id) 覆盖，此索引为带 status 的复合查询留余量）
CREATE INDEX IF NOT EXISTS idx_guardian_relation_elder ON guardian_relation (elder_id, status);
-- 反向查询：我监护了哪些老人（P1 `feat/guardian-relation` 的监护列表 / 告警推送用）
CREATE INDEX IF NOT EXISTS idx_guardian_relation_guardian ON guardian_relation (guardian_id, status);

COMMENT ON COLUMN guardian_relation.relation_id IS '业务ID，如 r-1001；与 user.user_id / med_plan.plan_id 等业务号同风格';
