-- feat/device-command：控制命令下发与回执状态（见 medbox-spec/06 的 2.17）
CREATE TABLE device_command (
    id           BIGSERIAL PRIMARY KEY,
    command_id   VARCHAR(64)  NOT NULL UNIQUE,   -- 业务 ID，c- 前缀，走序列
    device_id    VARCHAR(64)  NOT NULL,          -- 目标药箱主控
    cmd          VARCHAR(24)  NOT NULL,          -- BUZZ / UNLOCK / REBOOT / CALIBRATE
    params       JSONB,                          -- 命令参数（如 {"durationSec":5}），可空
    operator_id  VARCHAR(64)  NOT NULL,          -- 操作人（服务端取鉴权用户，不信客户端传值）
    state        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING / SUCCESS / FAILED
    reason       VARCHAR(256),                   -- 失败原因（回执 FAILED / 超时放弃 / 发布失败）
    msg_id       VARCHAR(64),                    -- 下行消息 msgId：up/ack 按它结算（持久化，重启不丢关联）
    created_at   TIMESTAMPTZ  DEFAULT NOW(),
    completed_at TIMESTAMPTZ                     -- 到达终态的时刻
);
CREATE SEQUENCE device_command_biz_id_seq START 1001;
CREATE INDEX idx_device_command_device ON device_command (device_id, created_at DESC);
CREATE INDEX idx_device_command_msg    ON device_command (msg_id);
