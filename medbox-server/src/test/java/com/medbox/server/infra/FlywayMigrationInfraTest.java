package com.medbox.server.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medbox.server.testsupport.PostgresInfraTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Flyway 迁移在真实 PostgreSQL 上的落地验证。
 *
 * <p>本地 {@code ./gradlew test} 关掉了 Flyway（CI 无 PG），迁移脚本从未真正执行过；
 * 本测试把 {@code db/migration} 下的 V1~V27 全量跑到真 PG，断言：
 * ① 全部脚本 success、无失败记录；② 关键表 / 业务号序列 / 改名列真实存在。
 */
@Tag("infra")
@DisplayName("Flyway 迁移：真实 PostgreSQL 全量执行")
class FlywayMigrationInfraTest extends PostgresInfraTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("全部迁移脚本执行成功")
    void allMigrationsSucceed() {
        Integer failed = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);
        assertEquals(0, failed, "存在失败的迁移脚本");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT version, description FROM flyway_schema_history "
                        + "WHERE version IS NOT NULL ORDER BY installed_rank");
        assertTrue(rows.size() >= 27, "已执行迁移数应不小于 27，实际 " + rows.size());
    }

    @Test
    @DisplayName("关键表与业务号序列存在（V3 / V8 / V9 生效）")
    void tablesAndSequencesExist() {
        assertExists("SELECT to_regclass('\"user\"')");
        assertExists("SELECT to_regclass('medicine')");
        assertExists("SELECT to_regclass('med_plan')");
        assertExists("SELECT to_regclass('refresh_token')");

        assertExists("SELECT to_regclass('user_biz_id_seq')");
        assertExists("SELECT to_regclass('medicine_biz_id_seq')");
        assertExists("SELECT to_regclass('med_plan_biz_id_seq')");
    }

    @Test
    @DisplayName("固定药格模型生效：med_plan.slots 存在、times 已改名（V22）")
    void fixedCellColumnsApplied() {
        Integer slots = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'med_plan' AND column_name = 'slots'", Integer.class);
        assertEquals(1, slots, "med_plan.slots 不存在");

        Integer times = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'med_plan' AND column_name = 'times'", Integer.class);
        assertEquals(0, times, "med_plan.times 应已被 V22 改名为 slots");
    }

    private void assertExists(String sql) {
        assertNotNull(jdbc.queryForObject(sql, String.class), "对象不存在：" + sql);
    }
}
