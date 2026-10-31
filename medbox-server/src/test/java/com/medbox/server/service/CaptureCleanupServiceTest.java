package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medbox.server.config.CaptureProperties;
import com.medbox.server.domain.Capture;
import com.medbox.server.mapper.CaptureMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * {@link CaptureCleanupService} 单测（纯 Mockito + 真实临时目录落盘，不连库）。
 * 覆盖：到期删库行+删文件 / 文件缺失不报错 / 开关跳过 / 分页循环 / 孤儿文件扫描。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CaptureCleanupService：过期删库行+删文件 / 孤儿扫描 / 开关 / 分页")
class CaptureCleanupServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-31T04:00:00Z");

    @TempDir Path tempDir;
    @Mock CaptureMapper captureMapper;

    private CaptureProperties props;
    private Clock clock;
    private CaptureCleanupService service;

    @BeforeEach
    void setUp() {
        props = new CaptureProperties();
        props.setDir(tempDir.toString());
        props.setCleanupEnabled(true);
        props.setCleanupBatchSize(500);
        clock = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
        service = new CaptureCleanupService(captureMapper, props, clock);
    }

    private Capture cap(String id, Path file) {
        Capture c = new Capture();
        c.setCaptureId(id);
        c.setFilePath(file.toString());
        return c;
    }

    @Test
    @DisplayName("到期记录：删库行 + 删磁盘文件")
    void 到期删除库行与文件() throws IOException {
        Path f = tempDir.resolve("cap-1.jpg");
        Files.writeString(f, "img");
        when(captureMapper.selectExpired(any(), anyInt())).thenReturn(List.of(cap("cap-1", f)));

        int deleted = service.scan(clock);

        assertEquals(1, deleted);
        verify(captureMapper).deleteByCaptureId("cap-1");
        assertFalse(Files.exists(f), "到期记录的磁盘文件应被删除");
    }

    @Test
    @DisplayName("文件路径不存在：仅删库行、不抛异常")
    void 文件缺失不报错() {
        Path missing = tempDir.resolve("cap-2.jpg"); // 不创建
        when(captureMapper.selectExpired(any(), anyInt())).thenReturn(List.of(cap("cap-2", missing)));

        int deleted = service.scan(clock);

        assertEquals(1, deleted);
        verify(captureMapper).deleteByCaptureId("cap-2");
    }

    @Test
    @DisplayName("开关关闭：直接跳过、不查库")
    void 关闭时跳过() {
        props.setCleanupEnabled(false);

        int deleted = service.scan(clock);

        assertEquals(0, deleted);
        verify(captureMapper, never()).selectExpired(any(), anyInt());
    }

    @Test
    @DisplayName("分页循环：取满 batchSize 继续下一轮，全部删除")
    void 分页循环删全部() {
        props.setCleanupBatchSize(2);
        Capture a = cap("a", tempDir.resolve("a.jpg"));
        Capture b = cap("b", tempDir.resolve("b.jpg"));
        Capture c = cap("c", tempDir.resolve("c.jpg"));
        when(captureMapper.selectExpired(any(), eq(2)))
                .thenReturn(List.of(a, b))
                .thenReturn(List.of(c));

        int deleted = service.scan(clock);

        assertEquals(3, deleted);
        verify(captureMapper, times(2)).selectExpired(any(), eq(2));
        verify(captureMapper).deleteByCaptureId("a");
        verify(captureMapper).deleteByCaptureId("b");
        verify(captureMapper).deleteByCaptureId("c");
    }

    @Test
    @DisplayName("孤儿文件：DB 无记录且文件够老则删；存在记录 / 近期文件保留")
    void 孤儿文件清理() throws IOException {
        // 旧孤儿文件（应删）
        Path orphan = tempDir.resolve("orphan-1.jpg");
        Files.writeString(orphan, "x");
        Files.setLastModifiedTime(orphan, FileTime.from(NOW.toInstant().minus(2, ChronoUnit.HOURS)));
        // 存在记录的同名文件（应保留）
        Path keep = tempDir.resolve("keep-1.jpg");
        Files.writeString(keep, "y");
        Files.setLastModifiedTime(keep, FileTime.from(NOW.toInstant().minus(2, ChronoUnit.HOURS)));
        // 近期文件（应保留，防误删上传进行中）
        Path recent = tempDir.resolve("recent-1.jpg");
        Files.writeString(recent, "z");
        Files.setLastModifiedTime(recent, FileTime.from(NOW.toInstant()));

        when(captureMapper.selectExpired(any(), anyInt())).thenReturn(List.of());
        when(captureMapper.existsByCaptureId("orphan-1")).thenReturn(false);
        when(captureMapper.existsByCaptureId("keep-1")).thenReturn(true);
        when(captureMapper.existsByCaptureId("recent-1")).thenReturn(false);

        service.scan(clock);

        assertFalse(Files.exists(orphan), "孤儿文件应被删除");
        assertTrue(Files.exists(keep), "DB 有记录的同名文件应保留");
        assertTrue(Files.exists(recent), "近期文件（上传进行中）应保留");
    }
}
