-- V3 · 认证（07 清单地基第 4 项 `feat/backend-auth`）
-- 见文档 06 的 2.13 / 2.14。只新增对象，不改动 V1 / V2（flyway.validate-on-migrate=true）

-- user_id 业务 ID 生成器（u-1001、u-1002 ...）：避免"先插入再回填 user_id"
--
-- 名字不能叫 user_id_seq：**那是 "user" 表 id BIGSERIAL 自动创建的序列**（表名+列名+seq），
-- 复用它会把自增主键的当前值拿去当业务号（首个用户就变成 u-1），两者还会互相踩。
CREATE SEQUENCE IF NOT EXISTS user_biz_id_seq START 1001;

-- 2.13 refresh_token：refresh token 轮换记录（换新即作废旧的）
CREATE TABLE IF NOT EXISTS refresh_token (
    id          BIGSERIAL PRIMARY KEY,
    token_id    VARCHAR(64)  NOT NULL UNIQUE,   -- 与 refresh JWT 的 jti 一致
    user_id     VARCHAR(64)  NOT NULL,          -- -> user.user_id
    token_hash  VARCHAR(128) NOT NULL,          -- refresh token 的 SHA-256，**不存明文**
    status      VARCHAR(16)  NOT NULL,          -- ACTIVE 生效中 / ROTATED 已轮换 / REVOKED 已撤销
    replaced_by VARCHAR(64),                    -- 轮换后接替它的 token_id（可空：撤销时无接替者）
    expires_at  TIMESTAMPTZ  NOT NULL,          -- 与 JWT 的 exp 一致，逾期即不可用
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    updated_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_refresh_token_user ON refresh_token (user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_token_expires ON refresh_token (expires_at);
COMMENT ON TABLE refresh_token IS 'refresh token 轮换记录；只存哈希，重放已轮换/已撤销的 token 会撤销该用户全部会话';

-- 2.14 idempotency_record：X-Request-Id 幂等记录（目前只保护 POST /auth/register）
CREATE TABLE IF NOT EXISTS idempotency_record (
    id                BIGSERIAL PRIMARY KEY,
    request_id        VARCHAR(64) NOT NULL,     -- 客户端 X-Request-Id（经 TraceContext.sanitize 校验）
    endpoint          VARCHAR(64) NOT NULL,     -- 幂等作用域，当前只有 AUTH_REGISTER
    request_hash      VARCHAR(64) NOT NULL,     -- 请求体指纹（不含密码），同号不同内容按 40001 处理
    response_snapshot TEXT        NOT NULL,     -- 首次响应的 data 快照（JSON 字符串）
    expires_at        TIMESTAMPTZ NOT NULL,     -- 过期后不再回放，记录由清理任务删除
    created_at        TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (request_id, endpoint)
);
CREATE INDEX IF NOT EXISTS idx_idempotency_expires ON idempotency_record (expires_at);
COMMENT ON TABLE idempotency_record IS 'X-Request-Id 幂等记录；命中即回放首次响应，过期记录由清理任务删除';
