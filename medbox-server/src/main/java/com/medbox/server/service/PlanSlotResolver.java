package com.medbox.server.service;

import com.medbox.server.domain.PlanSlot;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 服药计划槽展开（唯一入口，见文档 06 §2.6 / 07 §1、§2、§4）。
 *
 * <p>把 {@code slots} 中「格号 → 本地墙钟时间」按设备时区展开成带格号的具体瞬时，供
 * 就近槽匹配、MISS 扫描、REMINDER 扫描与看板 {@code totalSlots} 共用，避免多处各自解析时间字符串。
 * 格号 1 / 2 / 3 固定对应早 / 中 / 晚；缺失的格号不排程、不参与判定。
 */
@Component
public class PlanSlotResolver {

    /** 展开后的一个槽：格号 + 该槽的 UTC 瞬时（带偏移）。 */
    public record SlotInstant(Integer cellNo, OffsetDateTime slotTime) {
    }

    private static final Logger log = LoggerFactory.getLogger(PlanSlotResolver.class);

    /** 在 {@code [from, to]} 窗口内展开全部带格号的槽（±1 天覆盖跨天边界）。 */
    public List<SlotInstant> expandSlots(Instant from, Instant to, ZoneId zone, List<PlanSlot> slots) {
        List<SlotInstant> result = new ArrayList<>();
        if (slots == null || slots.isEmpty() || from.isAfter(to)) {
            return result;
        }
        LocalDate start = LocalDate.ofInstant(from, zone).minusDays(1);
        LocalDate end = LocalDate.ofInstant(to, zone).plusDays(1);
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            for (PlanSlot slot : slots) {
                if (slot == null || !isValidCellNo(slot.getCellNo())) {
                    continue;
                }
                try {
                    LocalTime lt = LocalTime.parse(slot.getTime());
                    ZonedDateTime zoned = ZonedDateTime.of(LocalDateTime.of(date, lt), zone);
                    Instant inst = zoned.toInstant();
                    if (!inst.isBefore(from) && !inst.isAfter(to)) {
                        result.add(new SlotInstant(slot.getCellNo(), zoned.toOffsetDateTime()));
                    }
                } catch (RuntimeException e) {
                    log.debug("跳过非法槽 zone={} cellNo={} time={}：{}",
                            zone, slot.getCellNo(), slot.getTime(), e.getMessage());
                }
            }
        }
        return result;
    }

    /**
     * 就近槽匹配（07 §1.2）：以 {@code at} 为中心取 D-1 / D / D+1 三天，只考虑同 {@code cellNo} 的槽，
     * 取 {@code |at − slot|} 最小者；差值相同取较早槽。该格无排程 → {@code null}。
     */
    public OffsetDateTime nearestSlot(Instant at, ZoneId zone, List<PlanSlot> slots, Integer cellNo) {
        if (cellNo == null || !isValidCellNo(cellNo)) {
            return null;
        }
        List<SlotInstant> candidates = expandSlots(
                at.minus(1, ChronoUnit.DAYS), at.plus(1, ChronoUnit.DAYS), zone, slots);
        OffsetDateTime best = null;
        long bestDiff = Long.MAX_VALUE;
        for (SlotInstant candidate : candidates) {
            if (!cellNo.equals(candidate.cellNo())) {
                continue;
            }
            long diff = Math.abs(Duration.between(candidate.slotTime().toInstant(), at).toMillis());
            if (diff < bestDiff || (diff == bestDiff && (best == null || candidate.slotTime().isBefore(best)))) {
                bestDiff = diff;
                best = candidate.slotTime();
            }
        }
        return best;
    }

    /** 固定药格格号合法性（1 / 2 / 3）。 */
    public static boolean isValidCellNo(Integer cellNo) {
        return cellNo != null && cellNo >= 1 && cellNo <= 3;
    }
}
