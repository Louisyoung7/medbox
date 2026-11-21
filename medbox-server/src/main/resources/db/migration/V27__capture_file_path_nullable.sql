-- 修复 TAKEN（已取药、不上图）记录无法落库的问题：
-- 自 capture-core 起协议约定「仅 UNTAKEN 附原图，TAKEN 不上图」，但 V1 初版 DDL 把 file_path 定为 NOT NULL，
-- 导致无图记录写入违反非空约束（真机 PostgreSQL 报 null value in column "file_path"）。
-- 这里放开非空约束；V1~V26 不改（validate-on-migrate=true，改了会启动失败）。
-- 版本号取当前最大 V26 + 1；按铁律 3.2，合并后尽快发布，不在特性分支长期累积。

ALTER TABLE capture ALTER COLUMN file_path DROP NOT NULL;
COMMENT ON COLUMN capture.file_path IS '服务端本地存储路径，不对外暴露；TAKEN 无图记录为 NULL';
