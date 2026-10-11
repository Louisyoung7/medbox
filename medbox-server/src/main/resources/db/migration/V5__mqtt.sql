-- V5 · MQTT 上行消息去重表（07 清单地基 `feat/backend-mqtt`，见文档 03 第 1 章 / 06 的 2.15）
-- 只新增对象，不改动 V1 ~ V4（flyway.validate-on-migrate=true）

-- 2.15 mqtt_message：上行消息去重记录
--
-- 每条上行消息（`up/telemetry`、`up/event/*`、`up/sensor/heartbeat`、`up/inventory`、`up/ack`）都必须带 `msgId`，
-- 格式固定 `{deviceId}-{10位递增序号}`（如 `BOXA1001-0000000124`，序号持久化、重启不归零，见文档 03 第 1 章）。
-- 断线重连时设备会重发，服务端按 `msgId` 去重，避免重复写服药记录 / 告警。
--
-- 用法：**先 INSERT 抢唯一键**，成功才放行给业务处理；命中 `UNIQUE` 冲突即视为重复，直接丢弃。
-- 不能"先查再插" —— 并发下两条重复消息可以同时通过 SELECT 检查。
CREATE TABLE mqtt_message (
    id          BIGSERIAL PRIMARY KEY,
    msg_id      VARCHAR(96)  NOT NULL UNIQUE,   -- 上行消息 msgId（**全局唯一**：协议保证它已含 deviceId）
    device_id   VARCHAR(64)  NOT NULL,          -- -> device.device_id（解析自 topic 第 3 层）
    topic       VARCHAR(128) NOT NULL,          -- 完整上行 topic，便于排障（同 msgId 不同 topic 也视为同一条）
    received_at TIMESTAMPTZ  DEFAULT NOW(),     -- 首次处理时间
    expires_at  TIMESTAMPTZ  NOT NULL           -- 过期后可删除；去重只需要覆盖"设备重发窗口"（默认 7 天，见 application.yml）
);
CREATE INDEX idx_mqtt_message_expires ON mqtt_message (expires_at);
COMMENT ON TABLE mqtt_message IS '上行 MQTT 消息去重表；先 INSERT 抢 msg_id 唯一键，冲突即丢弃重发消息';
COMMENT ON COLUMN mqtt_message.msg_id IS '设备侧 msgId（{deviceId}-{10位序号}），全局唯一';
COMMENT ON COLUMN mqtt_message.expires_at IS '超过重发窗口后记录失效，由清理任务删除（默认 7 天）';
