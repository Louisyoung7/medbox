package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medbox.server.common.security.AccessIdentity;
import com.medbox.server.common.security.DeviceOwnerResolver;
import com.medbox.server.config.CaptureProperties;
import com.medbox.server.domain.Capture;
import com.medbox.server.domain.MedRecord;
import com.medbox.server.dto.capture.CaptureUploadResponse;
import com.medbox.server.mapper.CaptureMapper;
import com.medbox.server.service.DispenseConsumeService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@link CaptureService} 单测（不依赖 Spring、Mapper/Resolver/AccessService 全打桩，落盘指向临时目录）。
 * 覆盖：上传去重 / 过期计算 / 扩展名白名单 / 首读标记 / 删除落盘。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CaptureService：去重 / 过期 / 落盘 / 首读标记")
class CaptureServiceTest {

    private static final OffsetDateTime CAP = OffsetDateTime.parse("2026-10-04T00:00:00Z");

    @TempDir Path tempDir;
    @Mock CaptureMapper captureMapper;
    @Mock DeviceOwnerResolver deviceOwnerResolver;
    @Mock AccessService accessService;
    @Mock CaptureFileTokenService fileTokenService;
    @Mock DispenseConsumeService dispenseConsumeService;

    private CaptureProperties props;
    private CaptureService service;

    @BeforeEach
    void setUp() {
        props = new CaptureProperties();
        props.setDir(tempDir.toString());
        props.setRetentionDaysUnread(30);
        props.setRetentionDaysRead(7);
        props.setUploadSecret("sec");
        props.setFileTokenSecret("ftsec");
        props.setFileTokenTtlMinutes(5);
        service = new CaptureService(captureMapper, props, deviceOwnerResolver,
                accessService, fileTokenService, dispenseConsumeService,
                Clock.fixed(CAP.toInstant(), ZoneOffset.UTC));
        when(dispenseConsumeService.consume(any())).thenReturn(Optional.empty());
    }

    private MultipartFile jpeg(String name) {
        return new MockMultipartFile("file", name, "image/jpeg",
                "hello-capture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("上传：解析老人 + 落盘 + expireAt = captured+30d")
    void 上传落盘与过期() {
        when(deviceOwnerResolver.resolveElderId("CAM01")).thenReturn("u-1001");
        when(captureMapper.selectByPlanWithinWindow(any(), any(), anyInt())).thenReturn(null);
        when(captureMapper.nextCaptureId()).thenReturn("cap-1001");
        when(captureMapper.insert(any(Capture.class))).thenReturn(1);

        CaptureUploadResponse r = service.upload("CAM01", jpeg("shot.jpg"), "NORMAL",
                "m-1", "p-1", null, null, null, null, new java.math.BigDecimal("0.9"), CAP);

        assertEquals("cap-1001", r.getCaptureId());
        assertEquals(CAP.plusDays(30), r.getExpireAt());
        Path expected = tempDir.resolve("2026-10-04").resolve("cap-1001.jpg");
        assertTrue(Files.exists(expected), "图片应已落盘");
    }

    @Test
    @DisplayName("上传带 faceResult + planId：capture.faceResult 落库；planId 非空触发判定核心并回带 recordId")
    void 上传带FaceResult与Record() {
        when(deviceOwnerResolver.resolveElderId("CAM01")).thenReturn("u-1001");
        when(captureMapper.selectByPlanWithinWindow(any(), any(), anyInt())).thenReturn(null);
        when(captureMapper.nextCaptureId()).thenReturn("cap-1001");
        when(captureMapper.insert(any(Capture.class))).thenReturn(1);
        MedRecord rec = new MedRecord();
        rec.setRecordId("mr-1");
        when(dispenseConsumeService.consume(any())).thenReturn(Optional.of(rec));

        CaptureUploadResponse r = service.upload("CAM01", jpeg("shot.jpg"), "NORMAL",
                "m-1", "p-1", "pi-1", "1", "片", "TAKEN", new java.math.BigDecimal("0.9"), CAP);

        assertEquals("cap-1001", r.getCaptureId());
        assertEquals("mr-1", r.getRecordId());
        ArgumentCaptor<Capture> cap = ArgumentCaptor.forClass(Capture.class);
        verify(captureMapper).insert(cap.capture());
        assertEquals("TAKEN", cap.getValue().getFaceResult());
        verify(dispenseConsumeService).consume(any());
    }

    @Test
    @DisplayName("去重：同 planId + 60s 窗口内只保留第一张，不再落盘")
    void 去重返回既有() {
        Capture existing = new Capture();
        existing.setCaptureId("cap-9001");
        existing.setExpireAt(CAP.plusDays(30));
        when(deviceOwnerResolver.resolveElderId("CAM01")).thenReturn("u-1001");
        when(captureMapper.selectByPlanWithinWindow(any(), any(), anyInt())).thenReturn(existing);

        CaptureUploadResponse r = service.upload("CAM01", jpeg("shot.jpg"), "NORMAL",
                "m-1", "p-1", null, null, null, null, null, CAP);

        assertEquals("cap-9001", r.getCaptureId());
        verify(captureMapper, never()).insert(any(Capture.class));
        verify(captureMapper, never()).nextCaptureId();
    }

    @Test
    @DisplayName("去重仅当 planId 非空：planId 空时正常新建")
    void planId空不去重() {
        when(deviceOwnerResolver.resolveElderId("CAM01")).thenReturn("u-1001");
        when(captureMapper.nextCaptureId()).thenReturn("cap-1001");
        when(captureMapper.insert(any(Capture.class))).thenReturn(1);

        service.upload("CAM01", jpeg("shot.jpg"), null, null, null, null, null, null, null, null, CAP);
        verify(captureMapper).nextCaptureId();
        verify(captureMapper).insert(any(Capture.class));
    }

    @Test
    @DisplayName("扩展名白名单：png/webp 各自落对应后缀，默认 jpg，不使用原始文件名")
    void 扩展名白名单() throws IOException {
        when(deviceOwnerResolver.resolveElderId("CAM01")).thenReturn("u-1001");
        when(captureMapper.selectByPlanWithinWindow(any(), any(), anyInt())).thenReturn(null);
        when(captureMapper.nextCaptureId()).thenReturn("cap-1001");
        when(captureMapper.insert(any(Capture.class))).thenReturn(1);

        MultipartFile png = new MockMultipartFile("file", "x.png", "image/png", "p".getBytes());
        service.upload("CAM01", png, "NORMAL", null, "p-1", null, null, null, null, null, CAP);
        assertTrue(Files.exists(tempDir.resolve("2026-10-04").resolve("cap-1001.png")));

        when(captureMapper.nextCaptureId()).thenReturn("cap-1002");
        MultipartFile webp = new MockMultipartFile("file", "x.webp", "image/webp", "w".getBytes());
        service.upload("CAM01", webp, "NORMAL", null, "p-1", null, null, null, null, null, CAP);
        assertTrue(Files.exists(tempDir.resolve("2026-10-04").resolve("cap-1002.webp")));
    }

    @Test
    @DisplayName("minExpire：查看使过期提前（viewed+7d < captured+30d）")
    void minExpire计算() {
        OffsetDateTime captured = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        OffsetDateTime viewed = OffsetDateTime.parse("2026-01-01T00:05:00Z");
        assertEquals(OffsetDateTime.parse("2026-01-08T00:05:00Z"),
                CaptureService.minExpire(captured, viewed, 30, 7));
        // 已过期：captured+30d 更早
        OffsetDateTime viewed2 = OffsetDateTime.parse("2026-02-01T00:00:00Z");
        assertEquals(OffsetDateTime.parse("2026-01-31T00:00:00Z"),
                CaptureService.minExpire(captured, viewed2, 30, 7));
    }

    @Test
    @DisplayName("文件流：首读写 viewed_at，仅标记一次")
    void 首读标记() throws IOException {
        Path img = tempDir.resolve("img.jpg");
        Files.write(img, "IMG".getBytes());
        Capture c = new Capture();
        c.setCaptureId("cap-1");
        c.setElderId("u-1001");
        c.setFilePath(img.toAbsolutePath().toString());
        c.setMime("image/jpeg");
        c.setCapturedAt(CAP);
        c.setExpireAt(CAP.plusDays(30));
        c.setViewedAt(null);
        when(captureMapper.selectByCaptureId("cap-1")).thenReturn(c);
        when(accessService.require("u-1001", com.medbox.server.common.security.Permission.READ_CAPTURE))
                .thenReturn(AccessIdentity.self("u-1001"));
        when(fileTokenService.verify("tok")).thenReturn("cap-1");

        ResponseEntity<Resource> resp = service.file("tok", "cap-1");
        assertEquals(200, resp.getStatusCode().value());
        assertEquals("IMG", new String(resp.getBody().getContentAsByteArray()));

        verify(captureMapper).markViewed(eq("cap-1"), any(), any());
    }

    @Test
    @DisplayName("文件流：已读不再标记 viewed_at")
    void 已读不重复标记() throws IOException {
        Path img = tempDir.resolve("img.jpg");
        Files.write(img, "IMG".getBytes());
        Capture c = new Capture();
        c.setCaptureId("cap-1");
        c.setElderId("u-1001");
        c.setFilePath(img.toAbsolutePath().toString());
        c.setMime("image/jpeg");
        c.setCapturedAt(CAP);
        c.setExpireAt(CAP.plusDays(30));
        c.setViewedAt(CAP.plusHours(1));
        when(captureMapper.selectByCaptureId("cap-1")).thenReturn(c);
        when(accessService.require("u-1001", com.medbox.server.common.security.Permission.READ_CAPTURE))
                .thenReturn(AccessIdentity.self("u-1001"));
        when(fileTokenService.verify("tok")).thenReturn("cap-1");

        service.file("tok", "cap-1");
        verify(captureMapper, never()).markViewed(any(), any(), any());
    }

    @Test
    @DisplayName("删除：删库行 + 删磁盘文件（文件缺失也不报错）")
    void 删除删文件() throws IOException {
        Path img = tempDir.resolve("img.jpg");
        Files.write(img, "IMG".getBytes());
        Capture c = new Capture();
        c.setCaptureId("cap-1");
        c.setElderId("u-1001");
        c.setFilePath(img.toAbsolutePath().toString());
        when(captureMapper.selectByCaptureId("cap-1")).thenReturn(c);
        when(accessService.require("u-1001", com.medbox.server.common.security.Permission.WRITE_CAPTURE))
                .thenReturn(AccessIdentity.self("u-1001"));
        when(captureMapper.deleteByCaptureId("cap-1")).thenReturn(1);

        service.delete("cap-1");
        verify(captureMapper).deleteByCaptureId("cap-1");
        assertFalse(Files.exists(img));
    }
}
