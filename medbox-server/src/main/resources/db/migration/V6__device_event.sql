-- V6 · 设备事件流水与设备表索引（07 清单 P0 `feat/device-core`，见文档 02 第 4 章 / 06 的 2.3、2.16）
-- 只新增对象，不改动 V1 ~ V5（flyway.validate-on-migrate=true）

-- 2.16 device_event：设备事件流水
--
-- 面向监护人长期可查的"这台设备发生过什么"：登记、上线、下线，
-- 后续 `feat/device-fault`（DOOR_TIMEOUT / CAMERA_FAILED / HW_FAULT）与
-- `feat/device-command`（控制命令下发与回执）沿用同一张表，只加枚举值、不改表结构。
--
-- **为什么不复用 mqtt_message**：那张表是上行消息去重账本，只有 `msg_id` 与 topic，
-- 没有事件类型语义，且 `expires_at` 只有 7 天会被清理（见 2.15）。事件流水是业务数据，
-- 生命周期与语义都不同，不能合并。
--
-- **本表无业务号**：事件只在"某台设备的流水"这个作用域内被引用，
-- 列表接口返回自增主键即可，不额外造 `event_id` 业务列（造了也没有第二个地方引用）。
CREATE TABLE device_event (
    id          BIGSERIAL PRIMARY KEY,
    device_id   VARCHAR(64)  NOT NULL,          -- -> device.device_id（子设备也直接记自己的编号）
    event_type  VARCHAR(24)  NOT NULL,          -- REGISTERED / ONLINE / OFFLINE / FAULT / COMMAND
    detail      VARCHAR(512),                   -- 事件补充描述（如故障原因），可为空
    created_at  TIMESTAMPTZ  DEFAULT NOW()
);
CREATE INDEX idx_device_event_device_time ON device_event (device_id, created_at DESC);
COMMENT ON TABLE device_event IS '设备事件流水；登记/上线/下线由 feat/device-core 写入，故障与命令由后续分支续写';
COMMENT ON COLUMN device_event.device_id IS '-> device.device_id；子设备记自己的编号，不折算成主控';
COMMENT ON COLUMN device_event.event_type IS 'REGISTERED 登记 / ONLINE 上线 / OFFLINE 离线 / FAULT 故障 / COMMAND 命令';
COMMENT ON COLUMN device_event.created_at IS '事件发生时间（UTC）';

-- device 表补索引（V1 建表时只有 device_id 的唯一约束）
-- `idx_device_elder`：设备列表按"我监护的老人集合"行级过滤（`elder_id IN (...)`）；
-- `idx_device_parent`：设备详情查下属传感器子设备（`parent_device_id = ?`）。
-- 设备表数据量极小（每老人个位数），建索引是为了让这两条查询不退化成全表扫描，
-- 顺带避免后续分支在没索引的表上长出慢查询。
CREATE INDEX idx_device_elder ON device (elder_id);
CREATE INDEX idx_device_parent ON device (parent_device_id);
