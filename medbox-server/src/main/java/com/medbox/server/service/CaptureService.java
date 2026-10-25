package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.common.security.DeviceOwnerResolver;
import com.medbox.server.service.AccessService;
import org.springframework.beans.factory.annotation.Autowired;
import com.medbox.server.common.security.Permission;
import com.medbox.server.config.CaptureProperties;
import com.medbox.server.domain.Capture;
import com.medbox.server.dto.PageResult;
import com.medbox.server.dto.capture.CaptureResponse;
import com.medbox.server.dto.capture.CaptureUploadResponse;
import com.medbox.server.mapper.CaptureMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 抓拍图片业务（见文档 02 的 4.1 / 01 的 4.6 / 06 的 2.12）。
 *
 * <p><b>职责</b>：上传（设备 HMAC 鉴权后的落盘 + 去重 + 过期计算）、列表、详情、文件流、删除；
 * 所有用户侧鉴权都在本类里通过 {@link AccessService#require} 完成（权限矩阵见 02 的 1.3：
 * {@code READ_CAPTURE} 仅老人本人与 FAMILY 监护人、{@code WRITE_CAPTURE} 仅 FAMILY）。
 *
 * <p><b>规则（确定性，详见测试）</b>：
 * <ul>
 *   <li>去重：{@code planId} 非空时，{@code |capturedAt - 已有 capturedAt| <= 60s} 视为重复，只保留第一张
 *       （命中即返回既有 {@code captureId}/{@code expireAt}，本次文件流丢弃、不落盘）；{@code planId} 空不去重。</li>
 *   <li>过期：未读 {@code expire_at = captured_at + retentionDaysUnread}（默认 30d）；
 *       首次读取写 {@code viewed_at} 后重算 {@code expire_at = MIN(captured_at+unread, viewed_at+read)}（默认 7d），
 *       查看只会提前、不会延长。</li>
 *   <li>存储：本地目录 {@code dir/YYYY-MM-DD/captureId.ext}，{@code file_path} 入库但绝不出现在响应里。</li>
 * </ul>
 */
@Service
public class CaptureService {

    /** 去重时间窗（秒）：同一 planId 内 60 秒只留一张。 */
    static final int DEDUP_WINDOW_SECONDS = 60;

    private static final Set<String> ALLOWED_REASONS =
            Set.of("NORMAL", "WRONG_DRUG", "LOW_CONFIDENCE", "MANUAL");

    private static final Set<String> EXT_WHITELIST = Set.of("jpg", "jpeg", "png", "webp");

    private final CaptureMapper captureMapper;
    private final CaptureProperties captureProperties;
    private final DeviceOwnerResolver deviceOwnerResolver;
    private final AccessService accessService;
    private final CaptureFileTokenService fileTokenService;
    private final Clock clock;

    @Autowired
    public CaptureService(CaptureMapper captureMapper,
                          CaptureProperties captureProperties,
                          DeviceOwnerResolver deviceOwnerResolver,
                          AccessService accessService,
                          CaptureFileTokenService fileTokenService) {
        this(captureMapper, captureProperties, deviceOwnerResolver, accessService,
                fileTokenService, Clock.systemUTC());
    }

    /** 测试用：注入固定时钟，便于断言过期计算。 */
    CaptureService(CaptureMapper captureMapper,
                          CaptureProperties captureProperties,
                          DeviceOwnerResolver deviceOwnerResolver,
                          AccessService accessService,
                          CaptureFileTokenService fileTokenService,
                          Clock clock) {
        this.captureMapper = captureMapper;
        this.captureProperties = captureProperties;
        this.deviceOwnerResolver = deviceOwnerResolver;
        this.accessService = accessService;
        this.fileTokenService = fileTokenService;
        this.clock = clock;
    }

    /**
     * 设备上传抓拍（调用前已由 {@code DeviceCaptureAuth} 完成 HMAC 校验）。
     *
     * @return 本次（或命中的既有）抓拍的 {@code captureId} 与 {@code expireAt}
     */
    public CaptureUploadResponse upload(String deviceId, MultipartFile file, String reason,
                                       String medicineId, String planId, BigDecimal confidence,
                                       OffsetDateTime capturedAt) {
        String elderId = deviceOwnerResolver.resolveElderId(deviceId);
        BizAssert.forbiddenIf(elderId == null, "设备归属老人解析失败（设备未注册或父设备缺失）");

        BizAssert.isTrue(file != null && !file.isEmpty(), ErrorCode.BAD_REQUEST, "上传文件不能为空");

        String reasonNorm = (reason == null || reason.isBlank()) ? "NORMAL" : reason.trim().toUpperCase(Locale.ROOT);
        BizAssert.isTrue(ALLOWED_REASONS.contains(reasonNorm), ErrorCode.BAD_REQUEST, "非法的 reason：" + reason);

        OffsetDateTime capAt = (capturedAt == null) ? clock.instant().atOffset(ZoneOffset.UTC) : capturedAt;

        // 去重：planId 非空时才查窗口
        if (planId != null && !planId.isBlank()) {
            Capture duplicate = captureMapper.selectByPlanWithinWindow(planId, capAt, DEDUP_WINDOW_SECONDS);
            if (duplicate != null) {
                return new CaptureUploadResponse(duplicate.getCaptureId(), duplicate.getExpireAt());
            }
        }

        String captureId = captureMapper.nextCaptureId();
        OffsetDateTime expireAt = capAt.plusDays(captureProperties.getRetentionDaysUnread());

        String ext = resolveExtension(file);
        Path dir = Paths.get(captureProperties.getDir(), capAt.toLocalDate().toString()); // YYYY-MM-DD
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(captureId + "." + ext);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            String filePath = target.toAbsolutePath().toString();

            Capture entity = new Capture();
            entity.setCaptureId(captureId);
            entity.setDeviceId(deviceId);
            entity.setElderId(elderId);
            entity.setMedicineId(medicineId);
            entity.setPlanId(planId);
            entity.setReason(reasonNorm);
            entity.setFilePath(filePath);
            entity.setMime(file.getContentType() != null ? file.getContentType() : "image/jpeg");
            entity.setSizeBytes((int) file.getSize());
            entity.setConfidence(confidence);
            entity.setCapturedAt(capAt);
            entity.setExpireAt(expireAt);
            captureMapper.insert(entity);
        } catch (IOException e) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "抓拍文件落盘失败：" + e.getMessage());
        }
        return new CaptureUploadResponse(captureId, expireAt);
    }

    /** 抓拍列表（仅元数据，不含文件流），支持按 reason 过滤与时间区间筛选。 */
    public PageResult<CaptureResponse> list(String elderId, String reason,
                                           OffsetDateTime from, OffsetDateTime to, int page, int size) {
        accessService.require(elderId, Permission.READ_CAPTURE);

        LambdaQueryWrapper<Capture> w = new LambdaQueryWrapper<>();
        w.eq(Capture::getElderId, elderId);
        if (reason != null && !reason.isBlank()) {
            w.eq(Capture::getReason, reason.trim().toUpperCase(Locale.ROOT));
        }
        if (from != null) {
            w.ge(Capture::getCapturedAt, from);
        }
        if (to != null) {
            w.le(Capture::getCapturedAt, to);
        }
        w.orderByDesc(Capture::getCapturedAt);

        int p = page < 1 ? 1 : page;
        int s = size < 1 ? 20 : Math.min(size, 200);
        Page<Capture> pageObj = captureMapper.selectPage(new Page<>(p, s), w);

        List<CaptureResponse> list = pageObj.getRecords().stream().map(this::toResponse).toList();
        return PageResult.of(list, pageObj.getTotal(), p, s);
    }

    /** 抓拍详情（含 fileToken 供 {@code <image src>} 使用）。 */
    public CaptureResponse detail(String captureId) {
        Capture capture = captureMapper.selectByCaptureId(captureId);
        BizAssert.notFound(capture, "抓拍不存在");
        accessService.require(capture.getElderId(), Permission.READ_CAPTURE);
        return toResponse(capture);
    }

    /**
     * 读取图片流：先校验 fileToken（含 captureId 绑定），再二次复核监护关系，首次读取写 viewed_at 并重算过期。
     * 返回 {@code ResponseEntity<Resource>}，由 Spring 直接吐二进制（不走统一响应体包裹）。
     */
    public ResponseEntity<Resource> file(String token, String captureId) {
        String tokenCaptureId = fileTokenService.verify(token);
        BizAssert.badRequestIf(!captureId.equals(tokenCaptureId), "fileToken 与路径不符");

        Capture capture = captureMapper.selectByCaptureId(captureId);
        BizAssert.notFound(capture, "抓拍不存在");
        accessService.require(capture.getElderId(), Permission.READ_CAPTURE);

        // 首读标记：viewed_at 为空才写，且只写一次
        if (capture.getViewedAt() == null) {
            OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
            OffsetDateTime expireAt = minExpire(capture.getCapturedAt(),
                    now, captureProperties.getRetentionDaysUnread(), captureProperties.getRetentionDaysRead());
            captureMapper.markViewed(captureId, now, expireAt);
        }

        Path path = Paths.get(capture.getFilePath());
        BizAssert.notFoundIf(!Files.exists(path), "抓拍文件不存在或已被清理");
        try {
            byte[] bytes = Files.readAllBytes(path);
            ByteArrayResource resource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return captureId;
                }
            };
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(capture.getMime()))
                    .contentLength(bytes.length)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(resource);
        } catch (IOException e) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "读取抓拍文件失败：" + e.getMessage());
        }
    }

    /** 删除抓拍（仅 FAMILY 监护人，见权限矩阵）。删库行 + 尽力删磁盘文件。 */
    public void delete(String captureId) {
        Capture capture = captureMapper.selectByCaptureId(captureId);
        BizAssert.notFound(capture, "抓拍不存在");
        accessService.require(capture.getElderId(), Permission.WRITE_CAPTURE);

        captureMapper.deleteByCaptureId(captureId);
        // 文件删除尽力而为：已被清理任务删掉也不算失败
        if (capture.getFilePath() != null) {
            try {
                Files.deleteIfExists(Paths.get(capture.getFilePath()));
            } catch (IOException ignored) {
                // 忽略：磁盘文件缺失不影响"记录已删除"的结果
            }
        }
    }

    // ————————————————— 内部工具 —————————————————

    private CaptureResponse toResponse(Capture c) {
        return new CaptureResponse(
                c.getCaptureId(),
                c.getDeviceId(),
                c.getElderId(),
                c.getMedicineId(),
                c.getPlanId(),
                c.getReason(),
                c.getMime(),
                c.getSizeBytes(),
                c.getConfidence(),
                c.getCapturedAt(),
                c.getViewedAt(),
                c.getExpireAt(),
                fileTokenService.issue(c.getCaptureId()));
    }

    /**
     * 首次读取后的过期时间：{@code MIN(captured_at + unread, viewed_at + read)}。
     * 查看只会让过期提前、不会延长（read < unread 时取 viewed+read）。
     */
    static OffsetDateTime minExpire(OffsetDateTime capturedAt, OffsetDateTime viewedAt,
                                    int unreadDays, int readDays) {
        OffsetDateTime byCaptured = capturedAt.plusDays(unreadDays);
        OffsetDateTime byViewed = viewedAt.plusDays(readDays);
        return byCaptured.isBefore(byViewed) ? byCaptured : byViewed;
    }

    /** 从上传内容推导存储后缀，仅取白名单内的值（防路径穿越：绝不使用原始文件名拼路径）。 */
    private static String resolveExtension(MultipartFile file) {
        String ct = file.getContentType();
        if (ct != null) {
            return switch (ct) {
                case "image/png" -> "png";
                case "image/webp" -> "webp";
                default -> "jpg";
            };
        }
        String name = file.getOriginalFilename();
        if (name != null && name.contains(".")) {
            String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (EXT_WHITELIST.contains(ext)) {
                return "jpeg".equals(ext) ? "jpg" : ext;
            }
        }
        return "jpg";
    }
}
