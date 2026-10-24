package com.medbox.server.db.migration;

import com.medbox.server.common.security.PasswordHasher;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.springframework.stereotype.Component;

/**
 * V11 · 管理后台种子账号（feat/backend-admin-cleanup，见文档 02 第 10 章 / 06 的 2.1）。
 *
 * <p>插入种子管理员 {@code admin} / {@code public}（BCrypt，复用 {@link PasswordHasher} 保证强度与业务一致），
 * {@code role=ADMIN}、{@code status=ACTIVE}、**不做首次强制改密**。仅用于 {@code medbox-admin/}
 * 测试数据清理（dev/QA 专用），生产库不应出现此类账号。
 *
 * <p>用 Java-based 迁移而非 SQL：SQL 无法 BCrypt，写死哈希会偏离 {@link PasswordHasher} 配置的强度；
 * 复用同一 {@code PasswordHasher} 既保证一致，也与 {@code DevtoolsSeedService} 风格统一。
 * {@code ON CONFLICT (user_id) DO NOTHING} 保证幂等（重复执行 / 重置库不报错）。
 *
 * <p>编号说明：V10 已被 {@code dispense} 占用，依 06 行 17 顺延为 V11；V12 是 {@code miss_alarm}，本迁移在其前不冲突，且不改 V1~V12。
 */
@Component
public class V11__admin_seed implements JavaMigration {

    private final PasswordHasher passwordHasher;

    public V11__admin_seed(PasswordHasher passwordHasher) {
        this.passwordHasher = passwordHasher;
    }

    @Override
    public MigrationVersion getVersion() {
        return MigrationVersion.fromVersion("11");
    }

    @Override
    public String getDescription() {
        return "admin seed account for test-data cleanup";
    }

    @Override
    public Integer getChecksum() {
        return null; // 交由 Flyway 自动计算
    }

    @Override
    public boolean canExecuteInTransaction() {
        return true;
    }

    @Override
    public void migrate(Context context) throws Exception {
        String hash = passwordHasher.hash("public");
        try (var ps = context.getConnection().prepareStatement(
                "INSERT INTO \"user\" (user_id, username, password_hash, name, role, status, created_at) "
                        + "VALUES ('u-admin', 'admin', ?, 'Admin', 'ADMIN', 'ACTIVE', NOW()) "
                        + "ON CONFLICT (user_id) DO NOTHING")) {
            ps.setString(1, hash);
            ps.executeUpdate();
        }
    }
}
