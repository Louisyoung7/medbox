-- V7 · 账号注销（07 清单 P1 `feat/backend-user-delete`，见文档 02 的 3.1 / 06 的 2.1、2.2、2.13）
-- 只新增对象，不改动 V1 ~ V6（flyway.validate-on-migrate=true）

-- **注销是软删除，不删行**（06 的 2.1）：
--   `status` 置 DELETED 只是让账号不可用，行本身保留 —— 历史数据（med_record / capture /
--   guardian_relation ...）里引用的 `user_id` 仍然成立，用药记录得以留痕；
--   登录标识 `phone` / `username` 由注销接口置 NULL —— **PostgreSQL 的 NULL 不参与唯一约束**，
--   因此同一手机号可以重新注册（拿到**新的** user_id，旧数据不继承）。
--
-- **为什么不物理删除**：用药记录属敏感健康数据（01 附录第 5 条要求留痕），且物理删除会让
-- 历史记录里的 elder_id / guardian_id 变成悬空引用，查询侧要到处判空 —— 收益远小于代价。
ALTER TABLE "user"
    ADD COLUMN status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN deleted_at TIMESTAMPTZ;
COMMENT ON COLUMN "user".status IS 'ACTIVE 正常 / DELETED 已注销（软删除，见文档 06 的 2.1）';
COMMENT ON COLUMN "user".deleted_at IS '注销时间（UTC）；status=DELETED 时非空';

-- 不建索引：`status` 只在"按 user_id 查到这一行之后"参与判定（登录 / 刷新 / 注销），
-- 走的都是 `user_id` 上的唯一键，没有"扫全表找已注销账号"的查询（清理任务本期不做）。
