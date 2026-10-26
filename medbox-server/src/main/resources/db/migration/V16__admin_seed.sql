-- V16 · 管理后台种子账号（fix/backend-admin-seed，见 02 第 10 章 / 06 的 2.1）
--
-- 背景：原 V11__admin_seed.java（JavaMigration @Component）在 Spring Boot 4 的 Flyway 自动装配下
--       不一定会被发现并执行，导致 admin 种子账号从未入库；admin 后台登录后访问 /admin/users 因
--       请求没有有效 ADMIN token 而 401（见 issue：admin 访问 /admin/users 报 401）。
--       故改用语义等价的 **SQL 迁移**——SQL 文件由 Flyway 经 classpath 扫描「必定」发现，
--       不依赖 JavaMigration Bean 的发现机制，彻底消除「账号没建出来」这一类静默失败。
--
-- 账号：username=admin / password=public / role=ADMIN / status=ACTIVE；不做首次强制改密（见 02 第 10 章）。
--       仅用于 medbox-admin/ 测试数据清理（dev/QA 专用），生产库不应出现此类账号。
--
-- password_hash 为 BCrypt(strength=10) 对明文 "public" 的哈希，与业务 PasswordHasher 强度一致
-- （由 spring-security-crypto 的 BCryptPasswordEncoder().encode("public") 生成）；
-- 若日后 PasswordHasher 强度变更，重置库后本行会重新插入新哈希。
--
-- 幂等：ON CONFLICT (user_id) DO NOTHING，重复执行 / 重置库不报错；
--       若原 V11__admin_seed.java 已先行建过 u-admin，本迁移为 no-op。

INSERT INTO "user" (user_id, username, password_hash, name, role, status, created_at)
VALUES ('u-admin', 'admin', '$2a$10$nKpIJx11rrakwq/v3w3KfuruWGmghGeJiMSjsCc.ZVfLR6V3ltoHK', 'Admin', 'ADMIN', 'ACTIVE', NOW())
ON CONFLICT (user_id) DO NOTHING;
