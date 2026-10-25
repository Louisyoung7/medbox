-- feat/plan-reminder（07 清单）：服药提醒去重账本
-- 说明：REMINDER 不是告警，隔离表避免污染 alarm 列表；dedup_key 唯一约束保证同一槽只推一次。
-- 版本号：当前最大为 V12（miss_alarm），本迁移顺延 V13（合并时若已有更高版本则取 max+1）。
CREATE TABLE plan_reminder (
    id          BIGSERIAL PRIMARY KEY,
    dedup_key   VARCHAR(128) NOT NULL UNIQUE,   -- REMINDER:{planId}:{slotUTC}
    plan_id     VARCHAR(64),                     -- -> med_plan.plan_id
    elder_id    VARCHAR(64),                     -- -> user.user_id (ELDER)
    slot_utc    TIMESTAMPTZ,                      -- 计划服药槽（UTC 瞬时）
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
