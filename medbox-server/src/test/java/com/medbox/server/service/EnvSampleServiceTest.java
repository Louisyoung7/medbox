package com.medbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.exception.BizException;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.EnvSample;
import com.medbox.server.dto.env.EnvHistoryPoint;
import com.medbox.server.dto.env.EnvHistoryResponse;
import com.medbox.server.dto.env.EnvRealtimeResponse;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.EnvSampleMapper;
import com.medbox.server.mqtt.payload.TelemetryPayload;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link EnvSampleService} 的纯单测（不走 Spring）：Mapper / AccessService 全部打桩，
 * 覆盖入库字段映射与归属跳过、实时读数合并、历史边界校验。
 */
class EnvSampleServiceTest {

    private final EnvSampleMapper envSampleMapper = mock(EnvSampleMapper.class);
    private final DeviceMapper deviceMapper = mock(DeviceMapper.class);
    private final AccessService accessService = mock(AccessService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneOffset.UTC);

    private final EnvSampleService service =
            new EnvSampleService(envSampleMapper, deviceMapper, accessService, clock);

    private static final String MAIN = "BOXA1001";

    private static Device mainDevice() {
        Device d = new Device();
        d.setDeviceId(MAIN);
        d.setElderId("u-1001");
        return d;
    }

    // ————————————————— 入库 —————————————————

    @Test
    @DisplayName("ingest：温湿度入库，device_id 记主控、归属取老人、ts 转 UTC")
    void ingestTempHumi() {
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(mainDevice());

        TelemetryPayload p = new TelemetryPayload();
        p.setTs(1759474487000L);
        p.setTemperature(24.6);
        p.setHumidity(52.1);

        service.ingest(MAIN, p);

        ArgumentCaptor<EnvSample> captor = ArgumentCaptor.forClass(EnvSample.class);
        verify(envSampleMapper).insert(captor.capture());
        EnvSample s = captor.getValue();
        assertThat(s.getDeviceId()).isEqualTo(MAIN);
        assertThat(s.getElderId()).isEqualTo("u-1001");
        assertThat(s.getTemperature()).isEqualByComparingTo("24.6");
        assertThat(s.getHumidity()).isEqualByComparingTo("52.1");
        assertThat(s.getLux()).isNull();
        assertThat(s.getSampleTime())
                .isEqualTo(OffsetDateTime.ofInstant(Instant.ofEpochMilli(1759474487000L), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("ingest：未登记主控跳过、不建脏行")
    void ingestUnknownDevice() {
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        TelemetryPayload p = new TelemetryPayload();
        p.setTemperature(24.6);

        service.ingest(MAIN, p);

        verify(envSampleMapper, never()).insert(any(EnvSample.class));
    }

    // ————————————————— 实时读数 —————————————————

    @Test
    @DisplayName("realtime：合并温湿度行与光照行，sampleTime 取较新者")
    void realtimeMergesReadings() {
        EnvSample th = new EnvSample();
        th.setTemperature(new BigDecimal("24.6"));
        th.setHumidity(new BigDecimal("52.1"));
        th.setSampleTime(OffsetDateTime.parse("2026-10-26T01:00:00Z"));
        EnvSample lx = new EnvSample();
        lx.setLux(new BigDecimal("120"));
        lx.setSampleTime(OffsetDateTime.parse("2026-10-26T01:05:00Z"));

        when(envSampleMapper.selectLatestTempHumi(eq(MAIN))).thenReturn(th);
        when(envSampleMapper.selectLatestLux(eq(MAIN))).thenReturn(lx);

        EnvRealtimeResponse r = service.realtime(MAIN);
        assertThat(r.temperature()).isEqualByComparingTo("24.6");
        assertThat(r.humidity()).isEqualByComparingTo("52.1");
        assertThat(r.lux()).isEqualByComparingTo("120");
        assertThat(r.sampleTime()).isEqualTo(OffsetDateTime.parse("2026-10-26T01:05:00Z"));
    }

    @Test
    @DisplayName("realtime：仅光照有数据，温湿度为 null")
    void realtimeOnlyLux() {
        EnvSample lx = new EnvSample();
        lx.setLux(new BigDecimal("120"));
        lx.setSampleTime(OffsetDateTime.parse("2026-10-26T01:05:00Z"));
        when(envSampleMapper.selectLatestTempHumi(eq(MAIN))).thenReturn(null);
        when(envSampleMapper.selectLatestLux(eq(MAIN))).thenReturn(lx);

        EnvRealtimeResponse r = service.realtime(MAIN);
        assertThat(r.temperature()).isNull();
        assertThat(r.humidity()).isNull();
        assertThat(r.lux()).isEqualByComparingTo("120");
    }

    // ————————————————— 历史 —————————————————

    @Test
    @DisplayName("history：成功时按主控透传给 mapper 并返回分桶点")
    void historyCallsMapper() {
        EnvHistoryPoint point = new EnvHistoryPoint(
                OffsetDateTime.parse("2026-10-26T01:00:00Z"),
                new BigDecimal("24.6"), new BigDecimal("52.1"), new BigDecimal("120"));
        when(envSampleMapper.selectHistory(eq(MAIN), any(), any(), eq(10)))
                .thenReturn(List.of(point));

        EnvHistoryResponse resp = service.history(MAIN,
                OffsetDateTime.parse("2026-10-26T00:00:00Z"),
                OffsetDateTime.parse("2026-10-26T02:00:00Z"), 10);

        assertThat(resp.points()).hasSize(1);
        verify(envSampleMapper).selectHistory(eq(MAIN), any(), any(), eq(10));
    }

    @Test
    @DisplayName("history：interval 越界 → 40001")
    void historyRejectsBadInterval() {
        assertThatThrownBy(() -> service.history(MAIN,
                OffsetDateTime.parse("2026-10-26T00:00:00Z"),
                OffsetDateTime.parse("2026-10-26T01:00:00Z"), 2000))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("history：缺少 from → 40001")
    void historyRejectsMissingFrom() {
        assertThatThrownBy(() -> service.history(MAIN, null,
                OffsetDateTime.parse("2026-10-26T01:00:00Z"), null))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("history：时间跨度超过 30 天 → 40001")
    void historyRejectsTooLargeSpan() {
        assertThatThrownBy(() -> service.history(MAIN,
                OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                OffsetDateTime.parse("2026-02-01T00:00:00Z"), null))
                .isInstanceOf(BizException.class);
    }
}
