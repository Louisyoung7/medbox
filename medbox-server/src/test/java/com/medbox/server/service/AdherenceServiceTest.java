package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.medbox.server.common.security.AccessIdentity;
import com.medbox.server.domain.Alarm;
import com.medbox.server.domain.MedRecord;
import com.medbox.server.dto.adherence.AdherenceDaily;
import com.medbox.server.dto.adherence.AdherenceResponse;
import com.medbox.server.mapper.AlarmMapper;
import com.medbox.server.mapper.MedRecordMapper;
import com.medbox.server.service.DeviceTimezoneResolver;
import com.medbox.server.service.AccessService;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AdherenceService} 单元测试（{@code feat/record-report}）：用固定 {@link Clock} 与打桩 Mapper，
 * 精确验证窗口解析与「按老人本地日分桶」的 grouping 逻辑（不依赖 Spring / H2）。
 */
class AdherenceServiceTest {

    private static final String ELDER_ID = "u-1001";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** now 固定在 2026-10-31T00:00:00Z；week 窗口 = [2026-10-24, 2026-10-31]，Asia/Shanghai 覆盖 8 个本地日。 */
    @Test
    @DisplayName("week 窗口：按时率=2/3、漏服=2，本地日分桶正确（含迟到不计入漏服）")
    void week窗口分桶正确() {
        MedRecordMapper medRecordMapper = mock(MedRecordMapper.class);
        AlarmMapper alarmMapper = mock(AlarmMapper.class);
        DeviceTimezoneResolver tz = mock(DeviceTimezoneResolver.class);
        AccessService access = mock(AccessService.class);

        Clock clock = Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC);
        when(tz.resolve(anyString())).thenReturn(ZONE);
        when(access.require(anyString(), any())).thenReturn(AccessIdentity.guardian("g", ELDER_ID));

        // total / onTime / late 三次 selectCount
        when(medRecordMapper.selectCount(any())).thenReturn(3L, 2L, 1L);
        when(alarmMapper.selectCount(any())).thenReturn(2L);
        when(medRecordMapper.selectList(any())).thenReturn(List.of(
                rec("2026-10-25T08:05:00+08:00", true),
                rec("2026-10-25T20:05:00+08:00", false),  // 迟到：算 lateCount，不计入漏服
                rec("2026-10-27T08:05:00+08:00", true)));
        when(alarmMapper.selectList(any())).thenReturn(List.of(
                miss("2026-10-26T08:00:00+08:00"),
                miss("2026-10-27T12:00:00+08:00")));

        AdherenceService service = new AdherenceService(medRecordMapper, alarmMapper, tz, access, clock);
        AdherenceResponse r = service.compute(ELDER_ID, "week");

        assertEquals(3, r.totalDoses());
        assertEquals(2, r.onTimeCount());
        assertEquals(1, r.lateCount());
        assertEquals(0, r.unjudgedCount());
        assertEquals(2, r.missCount());
        assertEquals(2.0 / 3, r.onTimeRate(), 1e-9);
        assertEquals("week", r.period());

        // 8 个本地日：2026-10-24 .. 2026-10-31
        assertEquals(8, r.dailyBreakdown().size());
        Map<String, AdherenceDaily> byDate = r.dailyBreakdown().stream()
                .collect(Collectors.toMap(AdherenceDaily::date, d -> d));

        AdherenceDaily d25 = byDate.get("2026-10-25");
        assertEquals(2, d25.totalDoses());
        assertEquals(1, d25.onTimeCount());
        assertEquals(0.5, d25.onTimeRate(), 1e-9);
        assertEquals(0, d25.missCount());

        AdherenceDaily d26 = byDate.get("2026-10-26");
        assertEquals(0, d26.totalDoses());
        assertEquals(1, d26.missCount());

        AdherenceDaily d27 = byDate.get("2026-10-27");
        assertEquals(1, d27.totalDoses());
        assertEquals(1, d27.onTimeCount());
        assertEquals(1, d27.missCount());
    }

    @Test
    @DisplayName("空窗口：totalDoses=0 时 onTimeRate=0.0，dailyBreakdown 仍有补齐日")
    void 空窗口返回零值() {
        MedRecordMapper medRecordMapper = mock(MedRecordMapper.class);
        AlarmMapper alarmMapper = mock(AlarmMapper.class);
        DeviceTimezoneResolver tz = mock(DeviceTimezoneResolver.class);
        AccessService access = mock(AccessService.class);

        Clock clock = Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC);
        when(tz.resolve(anyString())).thenReturn(ZONE);
        when(access.require(anyString(), any())).thenReturn(AccessIdentity.guardian("g", ELDER_ID));

        when(medRecordMapper.selectCount(any())).thenReturn(0L, 0L, 0L);
        when(alarmMapper.selectCount(any())).thenReturn(0L);
        when(medRecordMapper.selectList(any())).thenReturn(List.of());
        when(alarmMapper.selectList(any())).thenReturn(List.of());

        AdherenceService service = new AdherenceService(medRecordMapper, alarmMapper, tz, access, clock);
        AdherenceResponse r = service.compute(ELDER_ID, "month");

        assertEquals(0, r.totalDoses());
        assertEquals(0.0, r.onTimeRate(), 1e-9);
        assertEquals(0, r.missCount());
        // 30 天 UTC 窗口在 +8 时区覆盖 10-01 08:00 ~ 10-31 08:00，本地日 = 10-01..10-31 共 31 天
        assertEquals(31, r.dailyBreakdown().size());
        assertEquals(0, r.dailyBreakdown().stream().mapToLong(AdherenceDaily::totalDoses).sum());
    }

    private static MedRecord rec(String actualTime, boolean onTime) {
        MedRecord r = new MedRecord();
        r.setElderId(ELDER_ID);
        r.setActualTime(OffsetDateTime.parse(actualTime));
        r.setOnTime(onTime);
        return r;
    }

    private static Alarm miss(String createdAt) {
        Alarm a = new Alarm();
        a.setElderId(ELDER_ID);
        a.setType("MISS");
        a.setCreatedAt(OffsetDateTime.parse(createdAt));
        return a;
    }
}
