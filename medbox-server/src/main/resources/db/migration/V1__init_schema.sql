-- V1 · 初始化 schema（对应 07 清单地基第 3 项 feat/backend-db）
-- 依据：medbox-spec/06_数据库设计.md（V1.14）第 2.1~2.12、3.1~3.2 节
-- 约定：主键 BIGSERIAL 或业务主键；时间一律 TIMESTAMPTZ（UTC）；user 是保留字，建表须加双引号

-- pgvector：说明书向量存本地（drug_manual_chunk.embedding vector(1024)）
CREATE EXTENSION IF NOT EXISTS vector;

-- 2.1 用户（老人 + 监护人）
CREATE TABLE "user" (
    id             BIGSERIAL PRIMARY KEY,
    user_id        VARCHAR(64)  NOT NULL UNIQUE,   -- 业务ID
    username       VARCHAR(64)  UNIQUE,            -- 登录账号（可空，手机号亦可直接登录）
    password_hash  VARCHAR(100) NOT NULL,          -- BCrypt 单向哈希，永不存明文
    name           VARCHAR(64),
    phone          VARCHAR(20)  UNIQUE,            -- 登录账号（与 username 二选一）
    role           VARCHAR(16)  NOT NULL,          -- ELDER / GUARDIAN
    wechat_open_id VARCHAR(64) UNIQUE,             -- 【暂不使用】预留
    created_at     TIMESTAMPTZ DEFAULT NOW()
);
COMMENT ON TABLE "user" IS '用户（老人 ELDER / 监护人 GUARDIAN）';

-- 2.2 老人↔监护人绑定（两步生效：PENDING → ACTIVE）
CREATE TABLE guardian_relation (
    id          BIGSERIAL PRIMARY KEY,
    elder_id    VARCHAR(64) NOT NULL,   -- -> user.user_id (ELDER)
    guardian_id VARCHAR(64) NOT NULL,   -- -> user.user_id (GUARDIAN)
    role        VARCHAR(16),            -- FAMILY / DOCTOR / NURSE
    status      VARCHAR(16) NOT NULL,   -- PENDING / ACTIVE / REVOKED
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (elder_id, guardian_id)
);
COMMENT ON TABLE guardian_relation IS '老人与监护人的绑定关系，仅 ACTIVE 期间具备读写权限';

-- 2.3 设备（药箱主控 MAIN + 传感器子设备）
CREATE TABLE device (
    id               BIGSERIAL PRIMARY KEY,
    device_id        VARCHAR(64) NOT NULL UNIQUE,
    device_type      VARCHAR(24) NOT NULL,          -- MAIN / TEMP_HUMI / LIGHT / CAMERA
    parent_device_id VARCHAR(64),                   -- 子设备指向所属药箱（MAIN）；主控为空
    product_key      VARCHAR(64),
    secret           VARCHAR(128),                  -- 设备凭据（预留，本期匿名连接不使用）
    elder_id         VARCHAR(64),                    -- -> user.user_id (ELDER)，子设备同样填写
    timezone         VARCHAR(32) DEFAULT 'Asia/Shanghai',
    online_state     VARCHAR(16) DEFAULT 'OFFLINE', -- ONLINE / OFFLINE
    last_heartbeat   TIMESTAMPTZ,
    created_at       TIMESTAMPTZ DEFAULT NOW()
);
COMMENT ON TABLE device IS '药箱主控与其传感器子设备（药箱不划分仓位）';

-- 2.4 药品档案（owner_elder_id 为空 = 公共药品字典）
CREATE TABLE medicine (
    id               BIGSERIAL PRIMARY KEY,
    medicine_id      VARCHAR(64) NOT NULL UNIQUE,
    owner_elder_id   VARCHAR(64),            -- 归属老人（可空=公共药品，全体可见只读）
    name             VARCHAR(128) NOT NULL,
    spec             VARCHAR(64),            -- 规格
    dose_per_time    VARCHAR(32),            -- 默认单次剂量
    unit             VARCHAR(16),            -- 片/粒/ml...
    contraindication TEXT,                   -- 禁忌
    storage_cond     VARCHAR(128),           -- 储存条件文字说明（仅供展示）
    created_at       TIMESTAMPTZ DEFAULT NOW()
);
COMMENT ON TABLE medicine IS '药品档案；判定阈值统一取 alarm_setting.env_threshold';

-- 2.5 库存（药箱 + 药品维度，不划分仓位；生产日期与有效期由监护人手填）
CREATE TABLE medicine_stock (
    id              BIGSERIAL PRIMARY KEY,
    stock_id        VARCHAR(64) NOT NULL UNIQUE,
    device_id       VARCHAR(64) NOT NULL,   -- -> device.device_id（药箱 MAIN）
    elder_id        VARCHAR(64),            -- 冗余：-> user.user_id (ELDER)
    medicine_id     VARCHAR(64) NOT NULL,   -- -> medicine.medicine_id
    quantity        INT         DEFAULT 0,
    production_date DATE,
    expiry_date     DATE,                   -- 过期判定唯一依据
    updated_at      TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (device_id, medicine_id)
);
COMMENT ON TABLE medicine_stock IS '药箱 + 药品维度的库存；expiry_date 由监护人录入';

-- 2.6 服药计划（计划头：一次提醒 = 一个计划）
CREATE TABLE med_plan (
    id                   BIGSERIAL PRIMARY KEY,
    plan_id              VARCHAR(64) NOT NULL UNIQUE,
    elder_id             VARCHAR(64) NOT NULL,      -- -> user.user_id (ELDER)
    name                 VARCHAR(64),               -- 方案名，如"早餐后"
    times                JSONB NOT NULL,            -- ["08:00","20:00"] 设备本地墙钟时间
    repeat_rule          VARCHAR(16),               -- DAILY / WEEKLY ...
    miss_alarm_after_min INT,                       -- 漏服容忍（分钟），可空=沿用上一级
    alarm_rule           JSONB,                     -- {miss,wrongDrug,expired,env}
    status               VARCHAR(16) DEFAULT 'ENABLED',
    created_at           TIMESTAMPTZ DEFAULT NOW()
);
COMMENT ON TABLE med_plan IS '服药计划头；times 为设备本地墙钟时间，时区取 device.timezone';

-- 2.6.1 服药计划明细（一次可含多种药，无仓位字段）
CREATE TABLE med_plan_item (
    id          BIGSERIAL PRIMARY KEY,
    item_id     VARCHAR(64) NOT NULL UNIQUE,
    plan_id     VARCHAR(64) NOT NULL,   -- -> med_plan.plan_id
    medicine_id VARCHAR(64) NOT NULL,   -- -> medicine.medicine_id
    dose        VARCHAR(32),            -- 单次剂量，如 "1"
    unit        VARCHAR(16),
    note        VARCHAR(64),            -- 服用说明，如"餐后"
    sort_order  INT DEFAULT 0,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_plan_item_plan ON med_plan_item (plan_id);
COMMENT ON TABLE med_plan_item IS '服药计划明细；任一 item 缺失即判 MISS';

-- 2.7 服药记录（设备上报生成）
CREATE TABLE med_record (
    id           BIGSERIAL PRIMARY KEY,
    record_id    VARCHAR(64) NOT NULL UNIQUE,
    elder_id     VARCHAR(64),           -- 冗余：-> user.user_id (ELDER)
    plan_id      VARCHAR(64),           -- -> med_plan.plan_id
    plan_item_id VARCHAR(64),           -- -> med_plan_item.item_id
    medicine_id  VARCHAR(64),           -- 冗余便于按药品统计
    capture_id   VARCHAR(64),           -- -> capture.capture_id（可空）
    actual_dose  VARCHAR(32),
    plan_time    TIMESTAMPTZ,
    actual_time  TIMESTAMPTZ,
    on_time      BOOLEAN,               -- 是否按时
    wrong_drug   BOOLEAN,               -- 是否错服
    device_id    VARCHAR(64),           -- -> device.device_id
    created_at   TIMESTAMPTZ DEFAULT NOW()
);
COMMENT ON TABLE med_record IS '服药记录；on_time 由后端按计划时间 + 容忍判定';

-- 2.8 环境遥测（device_id 记传感器自身，gateway_device_id 记上报它的主控）
CREATE TABLE env_sample (
    id                BIGSERIAL PRIMARY KEY,
    device_id         VARCHAR(64) NOT NULL,   -- 传感器自身 -> device.device_id
    gateway_device_id VARCHAR(64),            -- 药箱主控 -> device.device_id
    elder_id          VARCHAR(64),            -- 冗余：-> user.user_id (ELDER)
    temperature NUMERIC(5,2),
    humidity    NUMERIC(5,2),
    lux         NUMERIC(8,2),
    sample_time TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_env_sample_device_time ON env_sample (device_id, sample_time);
COMMENT ON TABLE env_sample IS '环境遥测（温湿度 / 光照各一条上报）';

-- 2.9 告警
CREATE TABLE alarm (
    id           BIGSERIAL PRIMARY KEY,
    alarm_id     VARCHAR(64) NOT NULL UNIQUE,
    elder_id     VARCHAR(64),           -- -> user.user_id (ELDER)
    device_id    VARCHAR(64),           -- -> device.device_id
    plan_id      VARCHAR(64),           -- 关联计划（ENV / DEVICE_OFFLINE 等可空）
    medicine_id  VARCHAR(64),           -- 关联药品（MISS / WRONG_DRUG / EXPIRED 必带）
    type         VARCHAR(24) NOT NULL,  -- MISS / WRONG_DRUG / EXPIRED / ENV / DEVICE_OFFLINE / DEVICE_FAULT
    level        VARCHAR(16),           -- INFO / WARN / CRITICAL
    message      TEXT,
    read_state   BOOLEAN DEFAULT FALSE,
    handle_state VARCHAR(16) DEFAULT 'OPEN',  -- OPEN / HANDLED
    created_at   TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_alarm_elder_read ON alarm (elder_id, read_state, created_at);
COMMENT ON TABLE alarm IS '告警；DEVICE_OFFLINE 仅主控产生，传感器离线不告警';

-- 2.10 告警规则与阈值（每老人一份）
CREATE TABLE alarm_setting (
    id                  BIGSERIAL PRIMARY KEY,
    elder_id            VARCHAR(64) NOT NULL UNIQUE,  -- -> user.user_id (ELDER)
    expiring_ahead_days INT,               -- 临期提前预警天数
    miss_tolerate_min   INT,               -- 漏服容忍时长
    env_threshold       JSONB,             -- {tempMin,tempMax,humiMin,humiMax,luxMax}
    updated_at          TIMESTAMPTZ DEFAULT NOW()
);
COMMENT ON TABLE alarm_setting IS '老人级告警阈值；漏服容忍优先级 计划级 > 老人级 > 15 分钟';

-- 2.11 大模型配置（监护人代配，每老人一份；Key 加密存储）
CREATE TABLE llm_config (
    id           BIGSERIAL PRIMARY KEY,
    config_id    VARCHAR(64) NOT NULL UNIQUE,
    elder_id     VARCHAR(64) NOT NULL UNIQUE,   -- -> user.user_id (ELDER)
    provider     VARCHAR(32),                   -- openai-compatible 等
    api_key_enc  TEXT,                          -- AES 加密存储，回显脱敏
    model        VARCHAR(64),
    base_url     VARCHAR(255),
    enabled      BOOLEAN DEFAULT TRUE,
    updated_at   TIMESTAMPTZ DEFAULT NOW()
);
COMMENT ON TABLE llm_config IS '老人级大模型配置，API Key 加密存储';

-- 3.1 说明书（监护人上传的原始图片 / PDF）
CREATE TABLE drug_manual (
    id           BIGSERIAL PRIMARY KEY,
    manual_id    VARCHAR(64) NOT NULL UNIQUE,
    medicine_id  VARCHAR(64) NOT NULL,   -- -> medicine.medicine_id
    elder_id     VARCHAR(64),            -- 关联老人（公共药品可空）
    source_type  VARCHAR(32) NOT NULL,   -- IMAGE / PDF
    file_url     VARCHAR(512) NOT NULL,  -- 原始文件存储路径
    page         INT,
    status       VARCHAR(16) NOT NULL DEFAULT 'PARSING',  -- PARSING / INDEXED / FAILED
    created_at   TIMESTAMPTZ DEFAULT NOW()
);
COMMENT ON TABLE drug_manual IS '药品说明书原始文件与解析状态';

-- 3.2 说明书分块（RAG 召回单元）；HNSW 索引见 V2__vector_hnsw.sql
CREATE TABLE drug_manual_chunk (
    chunk_id     BIGSERIAL PRIMARY KEY,
    manual_id    BIGINT NOT NULL,        -- -> drug_manual.id
    medicine_id  VARCHAR(64) NOT NULL,   -- 召回过滤维度
    elder_id     VARCHAR(64),            -- 召回过滤维度（可空=公共药品）
    page         INT,
    chunk_index  INT,                    -- 块序号，便于答案标注出处
    content      TEXT NOT NULL,          -- 切块后正文（OCR 转写结果）
    source_type  VARCHAR(32) NOT NULL,   -- OCR / MANUAL / PDF
    embedding    vector(1024),           -- 本地 bge-m3 = 1024 维；换模型须重建向量
    created_at   TIMESTAMPTZ DEFAULT NOW()
);
COMMENT ON TABLE drug_manual_chunk IS '说明书分块向量（pgvector，1024 维）';

-- 2.12 设备抓拍图片（本地存储，不对外暴露真实路径；expire_at = MIN(captured+30d, viewed+7d)）
CREATE TABLE capture (
    id           BIGSERIAL PRIMARY KEY,
    capture_id   VARCHAR(64) NOT NULL UNIQUE,
    device_id    VARCHAR(64) NOT NULL,   -- -> device.device_id
    elder_id     VARCHAR(64) NOT NULL,   -- -> user.user_id (ELDER)
    medicine_id  VARCHAR(64),            -- 抓拍涉及的药品（可空）
    plan_id      VARCHAR(64),            -- 关联计划（可空）
    reason       VARCHAR(24) NOT NULL,   -- NORMAL / WRONG_DRUG / LOW_CONFIDENCE / MANUAL
    file_path    VARCHAR(512) NOT NULL,  -- 服务端本地存储路径，不对外暴露
    mime         VARCHAR(32) DEFAULT 'image/jpeg',
    size_bytes   INT,
    confidence   NUMERIC(4,3),           -- 识别置信度（可空）
    captured_at  TIMESTAMPTZ NOT NULL,   -- 抓拍时刻（UTC）
    viewed_at    TIMESTAMPTZ,            -- 首次被查看的时刻（可空=未读）
    expire_at    TIMESTAMPTZ NOT NULL,   -- 清理时刻
    created_at   TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_capture_elder_time ON capture (elder_id, captured_at DESC);
CREATE INDEX idx_capture_expire ON capture (expire_at);
COMMENT ON TABLE capture IS '服药抓拍留证；仅老人本人与 ACTIVE 监护人可见';
