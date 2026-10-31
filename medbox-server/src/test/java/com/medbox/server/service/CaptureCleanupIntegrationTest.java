package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medbox.server.config.CaptureProperties;
import com.medbox.server.domain.Capture;
import com.medbox.server.mapper.CaptureMapper;
import com.medbox.server.support.TableInfoWarmup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link CaptureCleanupService} 集成测试：真实 H2（Flyway 在 test 任务已关，见 build.gradle.kts）+ 真实
 * {@link CaptureMapper} + 真实临时目录落盘，走完整 {@code scan(Clock)} 链路断言「库行与磁盘文件均被删」。
 *
 * <p>参照 CaptureCoreWebIntegrationTest 的 H2 @Primary DataSource 基座；此处不 stub CaptureMapper，
 * 而是在 @Primary DataSource 里手动建最小 {@code capture(capture_id, file_path, expire_at)} 表以承载真实 SQL。
 */
@SpringBootTest
@DisplayName("CaptureCleanupService 集成：真实 H2 + 真实 Mapper，过期删库行与文件")
class CaptureCleanupIntegrationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-31T04:00:00Z");
    private static Path tmpDir;
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        if (tmpDir == null) {
            tmpDir = Files.createTempDirectory("capture-cleanup-it");
        }
        r.add("medbox.capture.dir", () -> tmpDir.toString());
    }

    @Autowired CaptureCleanupService cleanupService;
    @Autowired JdbcTemplate jdbcTemplate;

    @TestConfiguration
    static class Cfg {
        @Bean
        @Primary
        public DataSource dataSource() throws Exception {
            EmbeddedDatabase db = new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("capture-cleanup-it")
                    .build();
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "CREATE TABLE capture ("
                                 + "capture_id VARCHAR(64) PRIMARY KEY, "
                                 + "file_path VARCHAR(1024), "
                                 + "expire_at TIMESTAMP)")) {
                ps.execute();
            }
            return db;
        }

        @Bean
        @Primary
        public CaptureProperties captureProperties() throws IOException {
            if (tmpDir == null) {
                tmpDir = Files.createTempDirectory("capture-cleanup-it");
            }
            CaptureProperties p = new CaptureProperties();
            p.setDir(tmpDir.toString());
            p.setCleanupEnabled(true);
            p.setCleanupBatchSize(500);
            p.setRetentionDaysUnread(30);
            p.setRetentionDaysRead(7);
            return p;
        }
    }

    @BeforeAll
    static void warmUp() {
        TableInfoWarmup.init(Capture.class);
    }

    @BeforeEach
    void setUp() throws IOException {
        jdbcTemplate.update("DELETE FROM capture");
        clearTmpDir();
    }

    private static void clearTmpDir() throws IOException {
        if (tmpDir == null) {
            return;
        }
        try (Stream<Path> s = Files.list(tmpDir)) {
            s.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        }
    }

    private void insertCapture(String id, Path file, OffsetDateTime expireAt) {
        jdbcTemplate.update(
                "INSERT INTO capture (capture_id, file_path, expire_at) VALUES (?, ?, ?)",
                id, file.toString(), java.sql.Timestamp.from(expireAt.toInstant()));
    }

    private int countCapture(String id) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM capture WHERE capture_id = ?", Integer.class, id);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("过期记录：库行与磁盘文件均被删，未到期记录保留")
    void 过期删除真实库行与文件() throws IOException {
        Path expiredFile = tmpDir.resolve("exp-1.jpg");
        Path keptFile = tmpDir.resolve("fut-1.jpg");
        Files.writeString(expiredFile, "expired");
        Files.writeString(keptFile, "kept");

        insertCapture("exp-1", expiredFile, NOW.minusDays(1));
        insertCapture("fut-1", keptFile, NOW.plusDays(1));

        int deleted = cleanupService.scan(CLOCK);

        assertEquals(1, deleted);
        assertEquals(0, countCapture("exp-1"), "到期记录库行应被删");
        assertEquals(1, countCapture("fut-1"), "未到期记录库行应保留");
        assertFalse(Files.exists(expiredFile), "到期记录磁盘文件应被删");
        assertTrue(Files.exists(keptFile), "未到期记录磁盘文件应保留");
    }

    @Test
    @DisplayName("孤儿文件：DB 无记录且文件够老则被清理")
    void 孤儿文件被清理() throws IOException {
        Path orphan = tmpDir.resolve("orphan-x.jpg");
        Files.writeString(orphan, "orphan");
        // 设为 2 小时前，超过 ORPHAN_MIN_AGE_MINUTES(60)
        Files.setLastModifiedTime(orphan, FileTime.from(NOW.toInstant().minus(2, ChronoUnit.HOURS)));

        // selectExpired 返回空（无到期记录），仅触发孤儿扫描
        cleanupService.scan(CLOCK);

        assertFalse(Files.exists(orphan), "DB 无对应记录的遗留文件应被清理");
    }
}
