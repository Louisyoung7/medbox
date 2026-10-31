ALTER TABLE llm_config
    ADD COLUMN IF NOT EXISTS insight_enabled BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS insight_cadence VARCHAR(16) DEFAULT 'DAILY',
    ADD COLUMN IF NOT EXISTS last_insight_at TIMESTAMPTZ;

COMMENT ON COLUMN llm_config.insight_enabled IS '周期洞察开关（05 §2.3）';
COMMENT ON COLUMN llm_config.insight_cadence IS '周期节奏：DAILY / WEEKLY';
COMMENT ON COLUMN llm_config.last_insight_at IS '上次周期洞察生成时间，用于去重';