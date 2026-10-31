package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.security.Permission;
import com.medbox.server.domain.Alarm;
import com.medbox.server.domain.MedRecord;
import com.medbox.server.dto.adherence.AdherenceDaily;
import com.medbox.server.dto.adherence.AdherenceResponse;
import com.medbox.server.mapper.AlarmMapper;
import com.medbox.server.mapper.MedRecordMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 服药依从性统计（{@code feat/record-report}，见文档 02 第 7 章、07）。
 *
 * <p><b>权限</b>：{@code READ_ADHERENCE}（全角色，老人仅本人），走 {@link AccessService#require}。
 *
 * <p><b>统计口径（与产品确认）</b>：按时率 = 周期内 {@code med_record.on_time=true} 数 ÷ 周期内记录总数；
 * 漏服数 = 周期内 {@code alarm.type=MISS} 条数；迟到（on_time=false 但有记录）<b>不计入漏服</b>，与
 * 11 §2/§5 的 MISS 定义一致。
 *
 * <p><b>周期</b>：滚动窗口（day=24h / week=7d / month=30d），终点恒为当前时刻（由可注入
 * {@link Clock} 提供，便于测试固定时间）；窗口边界用 UTC 瞬时。本地日分桶走老人 MAIN 设备时区
 * （{@link DeviceTimezoneResolver}，缺省 Asia/Shanghai）。
 *
 * <p><b>实现取舍</b>：汇总计数走 {@code selectCount}（命中 {@code idx_med_record_elder_time} /
 * {@code uk_alarm_dedup_key}）；趋势分桶用 {@code selectList} 取窗口内有限记录后在 JVM 内按本地日
 * 分组，避免为分组写 H2/PG 方言 SQL（窗口 ≤30 天，数据量有界）。
 */
@Service
public class AdherenceService {

    private final MedRecordMapper medRecordMapper;
    private final AlarmMapper alarmMapper;
    private final DeviceTimezoneResolver timezoneResolver;
    private final AccessService accessService;
    private final Clock clock;

    /** 测试 / 非 Spring 场景：可注入 {@link Clock} 以固定时间。 */
    public AdherenceService(MedRecordMapper medRecordMapper, AlarmMapper alarmMapper,
                            DeviceTimezoneResolver timezoneResolver, AccessService accessService,
                            Clock clock) {
        this.medRecordMapper = medRecordMapper;
        this.alarmMapper = alarmMapper;
        this.timezoneResolver = timezoneResolver;
        this.accessService = accessService;
        this.clock = clock;
    }

    /** Spring 入口：{@code Clock} 无 Bean，默认 {@code systemUTC()}。 */
    @Autowired
    public AdherenceService(MedRecordMapper medRecordMapper, AlarmMapper alarmMapper,
                            DeviceTimezoneResolver timezoneResolver, AccessService accessService) {
        this(medRecordMapper, alarmMapper, timezoneResolver, accessService, Clock.systemUTC());
    }

    /** 计算某老人在指定周期的依从性统计（含本地日趋势）。 */
    public AdherenceResponse compute(String elderId, String period) {
        accessService.require(elderId, Permission.READ_ADHERENCE);

        Instant now = clock.instant();
        AdherencePeriod p = AdherencePeriod.of(period);
        Instant start = p.start(now);
        OffsetDateTime startOdt = start.atOffset(ZoneOffset.UTC);
        OffsetDateTime endOdt = now.atOffset(ZoneOffset.UTC);
        ZoneId zone = timezoneResolver.resolve(elderId);

        long totalDoses = medRecordMapper.selectCount(window(elderId, startOdt, endOdt));
        long onTimeCount = medRecordMapper.selectCount(onTime(elderId, startOdt, endOdt, true));
        long lateCount = medRecordMapper.selectCount(onTime(elderId, startOdt, endOdt, false));
        long missCount = alarmMapper.selectCount(missWindow(elderId, startOdt, endOdt));

        List<MedRecord> records = medRecordMapper.selectList(window(elderId, startOdt, endOdt));
        List<Alarm> missAlarms = alarmMapper.selectList(missWindow(elderId, startOdt, endOdt));

        double onTimeRate = totalDoses == 0 ? 0.0 : (double) onTimeCount / totalDoses;
        long unjudgedCount = Math.max(0, totalDoses - onTimeCount - lateCount);
        List<AdherenceDaily> daily = buildDaily(records, missAlarms, zone, start, now);

        return new AdherenceResponse(
                elderId, p.name().toLowerCase(),
                startOdt, endOdt,
                totalDoses, onTimeCount, lateCount, unjudgedCount, onTimeRate, missCount, daily);
    }

    private LambdaQueryWrapper<MedRecord> window(String elderId, OffsetDateTime s, OffsetDateTime e) {
        return new LambdaQueryWrapper<MedRecord>()
                .eq(MedRecord::getElderId, elderId)
                .ge(MedRecord::getActualTime, s)
                .le(MedRecord::getActualTime, e);
    }

    private LambdaQueryWrapper<MedRecord> onTime(String elderId, OffsetDateTime s, OffsetDateTime e,
                                                 boolean onTime) {
        return window(elderId, s, e).eq(MedRecord::getOnTime, onTime);
    }

    private LambdaQueryWrapper<Alarm> missWindow(String elderId, OffsetDateTime s, OffsetDateTime e) {
        return new LambdaQueryWrapper<Alarm>()
                .eq(Alarm::getElderId, elderId)
                .eq(Alarm::getType, "MISS")
                .ge(Alarm::getCreatedAt, s)
                .le(Alarm::getCreatedAt, e);
    }

    /** 按老人本地日分桶：遍历 [start, now] 覆盖的每一个本地日期，补出无数据日。 */
    private List<AdherenceDaily> buildDaily(List<MedRecord> records, List<Alarm> missAlarms,
                                            ZoneId zone, Instant start, Instant end) {
        Map<LocalDate, Long> missByDay = missAlarms.stream()
                .filter(a -> a.getCreatedAt() != null)
                .collect(Collectors.groupingBy(
                        a -> a.getCreatedAt().atZoneSameInstant(zone).toLocalDate(),
                        Collectors.counting()));
        Map<LocalDate, List<MedRecord>> byDay = records.stream()
                .filter(r -> r.getActualTime() != null)
                .collect(Collectors.groupingBy(
                        r -> r.getActualTime().atZoneSameInstant(zone).toLocalDate()));

        LocalDate from = start.atZone(zone).toLocalDate();
        LocalDate to = end.atZone(zone).toLocalDate();
        List<AdherenceDaily> result = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            List<MedRecord> recs = byDay.getOrDefault(d, List.of());
            long total = recs.size();
            long onTime = recs.stream().filter(r -> Boolean.TRUE.equals(r.getOnTime())).count();
            long miss = missByDay.getOrDefault(d, 0L);
            double rate = total == 0 ? 0.0 : (double) onTime / total;
            result.add(new AdherenceDaily(
                    d.format(DateTimeFormatter.ISO_LOCAL_DATE), rate, total, onTime, miss));
        }
        return result;
    }
}
