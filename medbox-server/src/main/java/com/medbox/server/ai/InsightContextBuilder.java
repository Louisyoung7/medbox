package com.medbox.server.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.config.AiInsightProperties;
import com.medbox.server.domain.*;
import com.medbox.server.mapper.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 把 med_record / plan_reminder / alarm / env_sample 压成"小而准"的上下文。 */
@Component
public class InsightContextBuilder {

    private final MedRecordMapper recordMapper;
    private final PlanReminderMapper reminderMapper;
    private final AlarmMapper alarmMapper;
    private final EnvSampleMapper envMapper;
    private final MedicineMapper medicineMapper;
    private final AiInsightProperties props;

    public InsightContextBuilder(MedRecordMapper recordMapper, PlanReminderMapper reminderMapper,
                                 AlarmMapper alarmMapper,
                                 EnvSampleMapper envMapper,
                                 MedicineMapper medicineMapper,
                                 AiInsightProperties props) {
        this.recordMapper = recordMapper;
        this.reminderMapper = reminderMapper;
        this.alarmMapper = alarmMapper;
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
        if (type == InsightType.ENV_CORRELATION || type == InsightType.COMPREHENSIVE) {
            rows += appendEnv(sb, citations, elderId, from, to);
        }
        if (type == InsightType.TREND || type == InsightType.COMPREHENSIVE) {
            rows += appendTrend(sb, citations, elderId, from, to);
        }
        if (type == InsightType.MEDICATION_SAFETY || type == InsightType.COMPREHENSIVE) {
            rows += appendMedicationSafety(sb, citations, elderId);
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

    /** 用药安全：监护人填写的药品说明是唯一事实来源，不联网、不推断说明未写的内容。 */
    private int appendMedicationSafety(StringBuilder sb, List<String> cit, String elderId) {
        List<Medicine> medicines = medicineMapper.selectList(new LambdaQueryWrapper<Medicine>()
                .eq(Medicine::getOwnerElderId, elderId)
                .orderByAsc(Medicine::getMedicineId)
                .last("LIMIT " + props.getMaxMedicines()));
        if (medicines.isEmpty()) {
            return 0;
        }
        sb.append("【用药安全】\n")
          .append("- 在册药品 ").append(medicines.size()).append(" 种（最多列出前 ")
          .append(props.getMaxMedicines()).append(" 种）\n")
          .append("- 以下说明由监护人手动填写，是唯一事实来源；系统未与服药记录关联，无法确认实际服用\n");
        for (Medicine m : medicines) {
            String desc = m.getDescription();
            if (desc == null || desc.isBlank()) {
                desc = "（未填写说明，仅能按名称核对说明书）";
            } else if (desc.length() > props.getMaxDescriptionChars()) {
                desc = desc.substring(0, props.getMaxDescriptionChars()) + "…";
            }
            String dose = (m.getDosePerTime() == null ? "" : m.getDosePerTime())
                    + (m.getUnit() == null ? "" : m.getUnit());
            sb.append("- [medicine#").append(m.getMedicineId()).append("] ").append(m.getName());
            if (!dose.isBlank()) {
                sb.append("（参考单次用量 ").append(dose).append("）");
            }
            sb.append("：").append(desc).append("\n");
            cit.add("medicine#" + m.getMedicineId());
        }
        return medicines.size();
    }

    /** 拼好的上下文；empty=true 表示区间内没有任何可用数据。 */
    public record InsightContext(String text, List<String> citations, boolean empty) {
    }
}