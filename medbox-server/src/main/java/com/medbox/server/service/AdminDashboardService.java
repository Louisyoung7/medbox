package com.medbox.server.service;

import com.medbox.server.domain.MedPlan;
import com.medbox.server.dto.admin.AdminAdherenceDashboard;
import com.medbox.server.dto.admin.AdminDashboardOverview;
import com.medbox.server.dto.admin.AdminDeviceRow;
import com.medbox.server.dto.admin.AdminMessageFlow;
import com.medbox.server.mapper.AdminDashboardMapper;
import com.medbox.server.mapper.DayCountRow;
import com.medbox.server.mapper.ElderCountRow;
import com.medbox.server.mapper.HourCountRow;
import com.medbox.server.mapper.MedPlanMapper;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 管理后台监控看板聚合（见文档 02 §10.4）。
 *
 * <p>仅由 {@code ADMIN} 角色经 {@code AdminAuthInterceptor} 进入，本类不再判角色。
 * 所有查询为只读聚合；服药槽展开复用 {@link PlanSlotResolver} 与 {@link DeviceTimezoneResolver}，
 * 与核心端 miss-alarm / plan 口径一致。
 *
 * <p>聚合口径（已与用户确认）：
 * <ul>
 *   <li>todayDose：按各老人设备本地时区取今日窗口，展开其启用计划得 totalSlots；
 *       taken/missed 取 med_record 对应窗口（missed 复用 on_time=false，与 miss-alarm 口径统一）。</li>
 *   <li>adherence：历史 totalSlots 用「当前计划展开」近似（假设窗口内计划稳定）；taken/missed
 *       由 med_record 按 UTC 日/小时/老人分组；rate = taken / totalSlots。</li>
 * </ul>
 */
@Service
public class AdminDashboardService {

    private final AdminDashboardMapper dashboardMapper;
    private final MedPlanMapper medPlanMapper;
    private final PlanSlotResolver planSlotResolver;
    private final DeviceTimezoneResolver timezoneResolver;

    public AdminDashboardService(AdminDashboardMapper dashboardMapper, MedPlanMapper medPlanMapper,
                                PlanSlotResolver planSlotResolver, DeviceTimezoneResolver timezoneResolver) {
        this.dashboardMapper = dashboardMapper;
        this.medPlanMapper = medPlanMapper;
        this.planSlotResolver = planSlotResolver;
        this.timezoneResolver = timezoneResolver;
    }

    // —— 10.4.1 总览 —— //

    public AdminDashboardOverview overview() {
        long total = dashboardMapper.countDevices();
        long online = dashboardMapper.countDevicesByState("ONLINE");
        long offline = dashboardMapper.countDevicesByState("OFFLINE");
        long elderCount = dashboardMapper.countElders();
        long planCount = dashboardMapper.countEnabledPlans();
        AdminDashboardOverview.TodayDose todayDose = computeTodayDose();
        // mqttConnected 取 online_state='ONLINE' 计数（device 表无独立 mqtt_connected 列）
        return new AdminDashboardOverview(
                new AdminDashboardOverview.DeviceStats(total, online, offline, online),
                todayDose, elderCount, planCount);
    }

    private AdminDashboardOverview.TodayDose computeTodayDose() {
        List<MedPlan> plans = medPlanMapper.selectDueForMissScan();
        Map<String, List<MedPlan>> byElder = plans.stream()
                .collect(Collectors.groupingBy(MedPlan::getElderId));
        long totalSlots = 0;
        long taken = 0;
        long missed = 0;
        for (Map.Entry<String, List<MedPlan>> e : byElder.entrySet()) {
            String elderId = e.getKey();
            ZoneId zone = timezoneResolver.resolve(elderId);
            LocalDate localToday = LocalDate.now(zone);
            Instant from = localToday.atStartOfDay(zone).toInstant();
            Instant to = localToday.plusDays(1).atStartOfDay(zone).toInstant();
            for (MedPlan plan : e.getValue()) {
                totalSlots += planSlotResolver.expandSlots(from, to, zone, plan.getSlots()).size();
            }
            taken += dashboardMapper.countRecordsByElderWindow(elderId, from, to, true);
            missed += dashboardMapper.countRecordsByElderWindow(elderId, from, to, false);
        }
        long pending = Math.max(0, totalSlots - taken - missed);
        return new AdminDashboardOverview.TodayDose(totalSlots, taken, missed, pending);
    }

    // —— 10.4.2 设备网络状态 —— //

    public List<AdminDeviceRow> devices(int limit) {
        int safe = Math.min(Math.max(limit, 1), 50);
        return dashboardMapper.selectRecentDevices(safe).stream()
                .map(r -> new AdminDeviceRow(r.getDeviceId(), r.getElderId(), r.getElderName(),
                        r.getOnlineState(), r.getLastHeartbeat()))
                .toList();
    }

    // —— 10.4.3 服药依从率 —— //

    public AdminAdherenceDashboard adherence(String granularity) {
        boolean month = "month".equals(granularity);
        int days = month ? 30 : 7;
        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
        LocalDate startUtc = todayUtc.minusDays(days - 1);
        Instant from = startUtc.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = todayUtc.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        Map<LocalDate, Long> expectedByDay = computeExpectedSlotsPerUtcDay(startUtc, todayUtc);
        Map<String, Long> expectedByElder = computeExpectedSlotsPerElder(startUtc, todayUtc);
        Map<LocalDate, Long> takenByDay = dayMap(dashboardMapper.countTakenByDay(from, to));
        Map<LocalDate, Long> missedByDay = dayMap(dashboardMapper.countMissedByDay(from, to));

        // 按 UTC 日归并到 week/month 桶
        Map<String, long[]> bucketAgg = new LinkedHashMap<>();
        for (LocalDate d = startUtc; !d.isAfter(todayUtc); d = d.plusDays(1)) {
            String bucket = month ? YearMonth.from(d).toString() : isoWeek(d);
            long e = expectedByDay.getOrDefault(d, 0L);
            long t = takenByDay.getOrDefault(d, 0L);
            long m = missedByDay.getOrDefault(d, 0L);
            long[] a = bucketAgg.computeIfAbsent(bucket, k -> new long[3]);
            a[0] += e;
            a[1] += t;
            a[2] += m;
        }
        List<AdminAdherenceDashboard.AdherencePoint> trend = new ArrayList<>();
        bucketAgg.forEach((bucket, a) -> trend.add(new AdminAdherenceDashboard.AdherencePoint(
                bucket, a[0], a[1], a[2], a[0] == 0 ? 0.0 : round2((double) a[1] / a[0]))));

        List<AdminAdherenceDashboard.MissHourBucket> missByHour = dashboardMapper
                .countMissedByHour(from, to).stream()
                .map(h -> new AdminAdherenceDashboard.MissHourBucket(h.getHr() == null ? 0 : h.getHr(), h.getCnt()))
                .toList();

        Map<String, String> elderName = new HashMap<>();
        Map<String, Long> takenByElder = elderMap(dashboardMapper.countTakenByElder(from, to), elderName);
        Map<String, Long> missedByElder = elderMap(dashboardMapper.countMissedByElder(from, to), elderName);

        List<AdminAdherenceDashboard.ElderAdherence> byElder = expectedByElder.entrySet().stream()
                .map(en -> {
                    String eid = en.getKey();
                    long e = en.getValue();
                    long t = takenByElder.getOrDefault(eid, 0L);
                    long m = missedByElder.getOrDefault(eid, 0L);
                    return new AdminAdherenceDashboard.ElderAdherence(
                            eid, elderName.getOrDefault(eid, null), e, t, m,
                            e == 0 ? 0.0 : round2((double) t / e));
                })
                .sorted(Comparator.comparingLong(
                        (AdminAdherenceDashboard.ElderAdherence x) -> x.totalSlots()).reversed())
                .limit(10)
                .toList();

        return new AdminAdherenceDashboard(trend, missByHour, byElder);
    }

    // —— 10.4.4 MQTT 消息流 —— //

    public AdminMessageFlow messages() {
        Instant oneMinAgo = Instant.now().minusSeconds(60);
        return new AdminMessageFlow(
                dashboardMapper.countMqttMessages(),
                dashboardMapper.countMqttMessagesSince(oneMinAgo),
                dashboardMapper.countDeviceCommands(),
                dashboardMapper.countDeviceCommandsSince(oneMinAgo));
    }

    // —— 期望槽（当前计划展开近似）—— //

    private Map<LocalDate, Long> computeExpectedSlotsPerUtcDay(LocalDate start, LocalDate end) {
        Map<LocalDate, Long> map = new HashMap<>();
        Map<String, List<MedPlan>> byElder = medPlanMapper.selectDueForMissScan().stream()
                .collect(Collectors.groupingBy(MedPlan::getElderId));
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            Instant from = d.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant to = d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            long slots = 0;
            for (Map.Entry<String, List<MedPlan>> e : byElder.entrySet()) {
                ZoneId zone = timezoneResolver.resolve(e.getKey());
                for (MedPlan p : e.getValue()) {
                    slots += planSlotResolver.expandSlots(from, to, zone, p.getSlots()).size();
                }
            }
            map.put(d, slots);
        }
        return map;
    }

    private Map<String, Long> computeExpectedSlotsPerElder(LocalDate start, LocalDate end) {
        Map<String, Long> map = new HashMap<>();
        Map<String, List<MedPlan>> byElder = medPlanMapper.selectDueForMissScan().stream()
                .collect(Collectors.groupingBy(MedPlan::getElderId));
        for (Map.Entry<String, List<MedPlan>> e : byElder.entrySet()) {
            String elderId = e.getKey();
            ZoneId zone = timezoneResolver.resolve(elderId);
            long slots = 0;
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                Instant from = d.atStartOfDay(ZoneOffset.UTC).toInstant();
                Instant to = d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                for (MedPlan p : e.getValue()) {
                    slots += planSlotResolver.expandSlots(from, to, zone, p.getSlots()).size();
                }
            }
            map.put(elderId, slots);
        }
        return map;
    }

    // —— 辅助 —— //

    private Map<LocalDate, Long> dayMap(List<DayCountRow> rows) {
        Map<LocalDate, Long> m = new HashMap<>();
        for (DayCountRow r : rows) {
            if (r.getDay() != null) {
                m.put(LocalDate.parse(r.getDay()), r.getCnt());
            }
        }
        return m;
    }

    private Map<String, Long> elderMap(List<ElderCountRow> rows, Map<String, String> nameOut) {
        Map<String, Long> m = new HashMap<>();
        for (ElderCountRow r : rows) {
            if (r.getElderId() != null) {
                m.put(r.getElderId(), r.getCnt());
                if (r.getElderName() != null) {
                    nameOut.put(r.getElderId(), r.getElderName());
                }
            }
        }
        return m;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String isoWeek(LocalDate d) {
        WeekFields wf = WeekFields.ISO;
        int week = d.get(wf.weekOfWeekBasedYear());
        int year = d.get(wf.weekBasedYear());
        return String.format("%d-W%02d", year, week);
    }
}
