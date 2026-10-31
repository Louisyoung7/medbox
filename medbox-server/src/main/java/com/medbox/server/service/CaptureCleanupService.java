package com.medbox.server.service;

import com.medbox.server.config.CaptureProperties;
import com.medbox.server.domain.Capture;
import com.medbox.server.mapper.CaptureMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 抓拍过期清理定时任务（feat/capture-cleanup，见文档 02 的 4.1 过期清理小节）。
 *
 * <p><b>职责</b>：每日（默认 04:00，{@code medbox.capture.cleanup.cron} 可配）扫描
 * {@code capture.expire_at < now} 的到期记录，分页批量删除库行并尽力清理磁盘原图；
 * 另对存储目录做 best-effort 孤儿文件扫描（DB 行已不存在的遗留图片）。
 *
 * <p><b>与 {@code CaptureService.delete} 的关系</b>：手动删除耦合了 {@code WRITE_CAPTURE} 权限校验（有用户上下文），
 * 本清理任务无用户上下文，故直接 {@code deleteByCaptureId} + {@code Files.deleteIfExists}（best-effort），
 * 文件删除口径与 {@code CaptureService.delete} 一致、互不调用。
 *
 * <p><b>范式</b>：沿用 {@code MissAlarmScanner} / {@code DeviceOfflineScanService} 的写法——
 * {@code @Scheduled} 仅作系统时钟入口，逻辑收口在可注入 {@link Clock} 的 {@link #scan(Clock)} 便于测试；
 * 整轮与逐条均兜异常，单条失败只记日志不影响其余。
 *
 * <p><b>首读重算过期</b>：{@code expire_at} 由 {@code CaptureService.file()} 首读时按
 * {@code MIN(captured_at+unread, viewed_at+read)} 重算（feat/capture-core 已实现），本任务只消费结果，不重算。
 */
@Service
public class CaptureCleanupService {

    private static final Logger log = LoggerFactory.getLogger(CaptureCleanupService.class);

    /** 孤儿文件最小年龄（分钟）：低于此年龄的文件视为"上传进行中/刚落盘"，不误删。 */
    private static final long ORPHAN_MIN_AGE_MINUTES = 60;

    private final CaptureMapper captureMapper;
    private final CaptureProperties captureProperties;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public CaptureCleanupService(CaptureMapper captureMapper, CaptureProperties captureProperties) {
        this(captureMapper, captureProperties, Clock.systemUTC());
    }

    /** 测试用：注入固定时钟与配置，便于断言过期扫描结果。 */
    public CaptureCleanupService(CaptureMapper captureMapper, CaptureProperties captureProperties, Clock clock) {
        this.captureMapper = captureMapper;
        this.captureProperties = captureProperties;
        this.clock = clock;
    }

    /** 定时入口：每日按 {@code medbox.capture.cleanup.cron} 跑一次（默认 04:00）。 */
    @Scheduled(cron = "${medbox.capture.cleanup.cron:0 0 4 * * *}")
    public void scheduledScan() {
        try {
            scan(clock);
        } catch (RuntimeException ex) {
            // 旁路任务：本轮失败只记日志，不能让调度线程死掉
            log.warn("抓拍过期清理本轮失败：{}", ex.getMessage());
        }
    }

    /**
     * 清理核心（可注入时钟便于测试）。
     *
     * @param scanClock 时钟（测试注入固定时钟）
     * @return 本轮删除的抓拍记录数（库行），文件尽力删除不计入返回
     */
    public int scan(Clock scanClock) {
        if (!captureProperties.isCleanupEnabled()) {
            log.debug("抓拍过期清理已关闭（medbox.capture.cleanup.enabled=false），跳过");
            return 0;
        }

        OffsetDateTime now = OffsetDateTime.now(scanClock);
        int batchSize = captureProperties.getCleanupBatchSize();
        int totalDeleted = 0;

        // 分页循环：每轮最多取 batchSize 条，取满则继续下一轮，避免一次性大删长事务锁表
        while (true) {
            List<Capture> expired = captureMapper.selectExpired(now, batchSize);
            if (expired == null || expired.isEmpty()) {
                break;
            }
            for (Capture c : expired) {
                try {
                    deleteOne(c);
                    totalDeleted++;
                } catch (RuntimeException ex) {
                    log.warn("抓拍过期删除失败 captureId={}：{}", c.getCaptureId(), ex.getMessage());
                }
            }
            if (expired.size() < batchSize) {
                break;
            }
        }

        // 孤儿文件扫描（best-effort，不阻塞主流程）
        int orphanDeleted = cleanupOrphanFiles(now);
        if (totalDeleted > 0 || orphanDeleted > 0) {
            log.info("抓拍过期清理完成：删除记录 {} 条，清理孤儿文件 {} 个", totalDeleted, orphanDeleted);
        }
        return totalDeleted;
    }

    /** 删单条库行 + 尽力删磁盘文件（文件缺失不报错，与 {@code CaptureService.delete} 口径一致）。 */
    private void deleteOne(Capture c) {
        captureMapper.deleteByCaptureId(c.getCaptureId());
        if (c.getFilePath() != null) {
            try {
                Files.deleteIfExists(Paths.get(c.getFilePath()));
            } catch (IOException ignored) {
                log.debug("抓拍文件删除失败（已忽略）captureId={} path={}", c.getCaptureId(), c.getFilePath());
            }
        }
    }

    /**
     * 孤儿文件扫描：遍历存储根目录树，删除文件名反查不到 DB 记录的图片。
     * best-effort，不阻塞主流程；仅限定在 {@code medbox.capture.dir} 内。
     * 低于 {@link #ORPHAN_MIN_AGE_MINUTES} 分钟的文件视为上传进行中，跳过以防误删刚落盘/未提交记录的文件。
     *
     * @param now 当前时刻（用于年龄判定）
     * @return 删除的孤儿文件数
     */
    private int cleanupOrphanFiles(OffsetDateTime now) {
        Path root = Paths.get(captureProperties.getDir());
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int orphanDeleted = 0;
        try (var stream = Files.walk(root)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(CaptureCleanupService::isCaptureImage)
                    .toList();
            for (Path file : files) {
                String captureId = captureIdFromFileName(file);
                if (captureId == null) {
                    continue;
                }
                try {
                    if (isOrphan(file, captureId, now) && Files.deleteIfExists(file)) {
                        orphanDeleted++;
                    }
                } catch (RuntimeException | IOException ex) {
                    log.debug("孤儿文件处理失败（已忽略）path={}：{}", file, ex.getMessage());
                }
            }
        } catch (IOException ex) {
            log.warn("孤儿文件扫描失败 dir={}：{}", root, ex.getMessage());
        }
        return orphanDeleted;
    }

    /** 是否孤儿：DB 无对应行，且文件足够"老"（非上传进行中）。 */
    private boolean isOrphan(Path file, String captureId, OffsetDateTime now) {
        if (captureMapper.existsByCaptureId(captureId)) {
            return false;
        }
        try {
            long ageMinutes = ChronoUnit.MINUTES.between(
                    Files.getLastModifiedTime(file).toInstant().atOffset(now.getOffset()), now);
            return ageMinutes >= ORPHAN_MIN_AGE_MINUTES;
        } catch (IOException ex) {
            return false; // 取不到修改时间则保守保留，不误删
        }
    }

    private static boolean isCaptureImage(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".webp");
    }

    /** 从文件名（不含扩展名）反推 captureId，如 {@code cap-1001.jpg} → {@code cap-1001}。 */
    private static String captureIdFromFileName(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }
}
