package com.medbox.server.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.config.AiInsightProperties;
import com.medbox.server.domain.*;
import com.medbox.server.mapper.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 把 med_record / plan_reminder / alarm / medicine_stock / env_sample 压成"小而准"的上下文。 */
@Component
public class InsightContextBuilder {

    private final MedRecordMapper recordMapper;
    private final PlanReminderMapper reminderMapper;
    private final AlarmMapper alarmMapper;
    private final MedicineStockMapper stockMapper;
    private final EnvSampleMapper envMapper;
    private final MedicineMapper medicineMapper;
    private final AiInsightProperties props;

    public InsightContextBuilder(MedRecordMapper recordMapper, PlanReminderMapper reminderMapper,
                                 AlarmMapper alarmMapper, MedicineStockMapper stockMapper,
                                 EnvSampleMapper envMapper, MedicineMapper medicineMapper,
                                 AiInsightProperties props) {
        this.recordMapper = recordMapper;
        this.reminderMapper = reminderMapper;
        this.alarmMapper = alarmMapper;
        this.stockMapper = stockMapper;
        this.envMapper = envMapper;
        this.medicineMapper = medicineMapper;
        this.props = props;
    }

    public InsightContext build(String elderId, InsightType type, OffsetDateTime from, OffsetDateTime to) {
        StringBuilder sb = new StringBuilder();
        List<String> citations = new ArrayList<>();
        int rows = 0;
        if (type == InsightType.ADHERENCE || type == InsightType.COMPREHENSIVE) {
            rows += appendAdherence(sb, citations, elderId, from, to);
        }
        if (type == InsightType.STOCK || type == InsightType.COMPREHENSIVE) {
            rows += appendStock(sb, citations, elderId, from, to);
        }
        if (type == InsightType.ENV_CORRELATION || type == InsightType.COMPREHENSIVE) {
            rows += appendEnv(sb, citations, elderId, from, to);
        }
        if (type == InsightType.TREND || type == InsightType.COMPREHENSIVE) {
            rows += appendTrend(sb, citations, elderId, from, to);
        }
        return new InsightContext(sb.toString(), List.copyOf(citations), rows == 0);
    }

    private int appendAdherence(StringBuilder sb, List<String> cit, String elderId,
                                OffsetDateTime from, OffsetDateTime to) {
        List<MedRecord> records = recordMapper.selectList(new LambdaQueryWrapper<MedRecord>()
                .eq(MedRecord::getElderId, elderId)
                .ge(MedRecord::getActualTime, from).le(MedRecord::getActualTime, to)
                .orderByAsc(MedRecord::getActualTime)
                .last("LIMIT " + props.getMaxRecords()));
        List<PlanReminder> reminders = reminderMapper.selectList(new LambdaQueryWrapper<PlanReminder>()
                .eq(PlanReminder::getElderId, elderId)
                .ge(PlanReminder::getSlotUtc, from).le(PlanReminder::getSlotUtc, to)
                .last("LIMIT " + props.getMaxRecords()));
        List<Alarm> misses = alarmMapper.selectList(new LambdaQueryWrapper<Alarm>()
                .eq(Alarm::getElderId, elderId).eq(Alarm::getType, "MISS")
                .ge(Alarm::getCreatedAt, from).le(Alarm::getCreatedAt, to)
                .last("LIMIT " + props.getMaxRecords()));

        if (records.isEmpty() && reminders.isEmpty() && misses.isEmpty()) {
            return 0;
        }
        ZoneId zone = ZoneId.of(props.getTimezone());
        long late = records.stream().filter(r -> Boolean.FALSE.equals(r.getOnTime())).count();
        Map<String, Long> lateByHour = records.stream()
                .filter(r -> Boolean.FALSE.equals(r.getOnTime()))
                .collect(Collectors.groupingBy(
                        r -> LocalTime.ofInstant(r.getActualTime().toInstant(), zone).getHour() + "点",
                        TreeMap::new, Collectors.counting()));
        Map<String, Long> lateByWeekday = records.stream()
                .filter(r -> Boolean.FALSE.equals(r.getOnTime()))
                .collect(Collectors.groupingBy(
                        r -> DayOfWeek.from(LocalDateTime.ofInstant(r.getActualTime().toInstant(), zone)).toString(),
                        TreeMap::new, Collectors.counting()));

        sb.append("【依从性】\n")
          .append("- 服药记录 ").append(records.size()).append(" 条，其中迟到 ").append(late).append(" 条\n")
          .append("- 提醒 ").append(reminders.size()).append(" 次，MISS 告警 ").append(misses.size()).append(" 条\n")
          .append("- 迟到按小时: ").append(lateByHour).append("\n")
          .append("- 迟到按星期: ").append(lateByWeekday).append("\n");
        records.stream().limit(10).forEach(r -> cit.add("med_record#" + r.getRecordId()));
        misses.stream().limit(5).forEach(a -> cit.add("alarm#" + a.getAlarmId()));
        return records.size() + reminders.size() + misses.size();
    }

    private int appendStock(StringBuilder sb, List<String> cit, String elderId,
                            OffsetDateTime from, OffsetDateTime to) {
        List<MedicineStock> stocks = stockMapper.selectList(new LambdaQueryWrapper<MedicineStock>()
                .eq(MedicineStock::getElderId, elderId));
        if (stocks.isEmpty()) {
            return 0;
        }
        Set<String> ids = stocks.stream().map(MedicineStock::getMedicineId).collect(Collectors.toSet());
        Map<String, String> names = medicineMapper.selectList(
                        new LambdaQueryWrapper<Medicine>().in(Medicine::getMedicineId, ids))
                .stream().collect(Collectors.toMap(Medicine::getMedicineId, Medicine::getName));
        List<MedRecord> records = recordMapper.selectList(new LambdaQueryWrapper<MedRecord>()
                .eq(MedRecord::getElderId, elderId)
                .ge(MedRecord::getActualTime, from).le(MedRecord::getActualTime, to));
        long days = Math.max(Duration.between(from, to).toDays(), 1);

        sb.append("【库存】\n");
        for (MedicineStock s : stocks) {
            long consumed = records.stream()
                    .filter(r -> s.getMedicineId().equals(r.getMedicineId()))
                    .mapToLong(r -> doseOf(r.getActualDose())).sum();
            double perDay = (double) consumed / days;
            String remain = perDay > 0
                    ? String.format(Locale.ROOT, "约 %.1f 天耗尽", (s.getQuantity() == null ? 0 : s.getQuantity()) / perDay)
                    : "区间内无消耗";
            sb.append("- ").append(names.getOrDefault(s.getMedicineId(), s.getMedicineId()))
              .append("：库存 ").append(s.getQuantity())
              .append("，有效期 ").append(s.getExpiryDate())
              .append("，区间消耗 ").append(consumed).append("，").append(remain).append("\n");
            cit.add("medicine_stock#" + s.getMedicineId());
        }
        return stocks.size();
    }

    private int appendEnv(StringBuilder sb, List<String> cit, String elderId,
                          OffsetDateTime from, OffsetDateTime to) {
        List<EnvSample> samples = envMapper.selectList(new LambdaQueryWrapper<EnvSample>()
                .eq(EnvSample::getElderId, elderId)
                .ge(EnvSample::getSampleTime, from).le(EnvSample::getSampleTime, to)
                .orderByDesc(EnvSample::getSampleTime)
                .last("LIMIT " + props.getMaxRecords()));
        if (samples.isEmpty()) {
            return 0;
        }
        java.util.function.Function<EnvSample, java.math.BigDecimal> temp = EnvSample::getTemperature;
        java.util.Optional<java.math.BigDecimal> maxTemp = samples.stream()
                .map(temp).filter(Objects::nonNull).max(java.math.BigDecimal::compareTo);
        long hot = samples.stream().map(temp).filter(Objects::nonNull)
                .filter(v -> v.compareTo(new java.math.BigDecimal("25")) > 0).count();
        long dry = samples.stream().map(EnvSample::getHumidity).filter(Objects::nonNull)
                .filter(v -> v.compareTo(new java.math.BigDecimal("60")) > 0).count();

        sb.append("【环境】\n")
          .append("- 样本 ").append(samples.size()).append(" 条，最高温度 ").append(maxTemp.orElse(null)).append("℃\n")
          .append("- 温度 >25℃ 的样本 ").append(hot).append(" 条；湿度 >60% 的样本 ").append(dry).append(" 条\n")
          .append("- 说明：阈值按 25℃ / 60% 粗筛，非该老人配置阈值\n");
        samples.stream().limit(5).forEach(s -> cit.add("env_sample#" + s.getId()));
        return samples.size();
    }

    private int appendTrend(StringBuilder sb, List<String> cit, String elderId,
                            OffsetDateTime from, OffsetDateTime to) {
        List<MedRecord> records = recordMapper.selectList(new LambdaQueryWrapper<MedRecord>()
                .eq(MedRecord::getElderId, elderId)
                .ge(MedRecord::getActualTime, from).le(MedRecord::getActualTime, to)
                .orderByAsc(MedRecord::getActualTime)
                .last("LIMIT " + props.getMaxRecords()));
        if (records.isEmpty()) {
            return 0;
        }
        ZoneId zone = ZoneId.of(props.getTimezone());
        Map<String, Long> byDay = records.stream().collect(Collectors.groupingBy(
                r -> LocalDate.ofInstant(r.getActualTime().toInstant(), zone).toString(),
                TreeMap::new, Collectors.counting()));
        long late = records.stream().filter(r -> Boolean.FALSE.equals(r.getOnTime())).count();
        sb.append("【趋势】\n")
          .append("- 每日服药次数: ").append(byDay).append("\n")
          .append("- 迟到占比: ").append(records.isEmpty() ? 0 : late * 100 / records.size()).append("%\n");
        records.stream().limit(5).forEach(r -> cit.add("med_record#" + r.getRecordId()));
        return records.size();
    }

    private long doseOf(String actualDose) {
        try {
            long v = Long.parseLong(String.valueOf(actualDose).trim());
            return v > 0 ? v : 1;
        } catch (RuntimeException ex) {
            return 1;
        }
    }

    /** 拼好的上下文；empty=true 表示区间内没有任何可用数据。 */
    public record InsightContext(String text, List<String> citations, boolean empty) {
    }
}