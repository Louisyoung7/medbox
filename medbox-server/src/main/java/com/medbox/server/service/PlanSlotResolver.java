package com.medbox.server.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 服药计划槽展开（公共组件，抽自 {@code DispenseConsumeService.nearestSlot}）。
 *
 * <p><b>为什么抽出来</b>：{@code feat/dispense-consume} 的"就近槽"和本分支 {@code feat/miss-alarm}
 * 的"扫描窗口内所有槽"本质是同一套"设备本地墙钟 times → 具体瞬时"的换算（文档 06 §2.6.1），
 * 放一处避免两处漂移。
 *
 * <p><b>窗口语义</b>：给定 {@code [from, to]} 两个 UTC 瞬时与设备时区，返回窗口内（含边界 ±1 天，
 * 覆盖跨天）每一个 {@code times} 墙钟对应的服药槽（{@link OffsetDateTime}，带时区偏移）。
 * 漏服扫描对每个槽再判定是否过容忍窗口、是否缺失上报。
 */
@Component
public class PlanSlotResolver {

    private static final Logger log = LoggerFactory.getLogger(PlanSlotResolver.class);

    /** 在 {@code [from, to]} 窗口内展开全部服药槽。 */
    public List<OffsetDateTime> expandSlots(Instant from, Instant to, ZoneId zone, List<String> times) {
        List<OffsetDateTime> slots = new ArrayList<>();
        if (times == null || times.isEmpty()) {
            return slots;
        }
        if (from.isAfter(to)) {
            return slots;
        }
        // ±1 天覆盖窗口边界的非整日跨天（如本地 23:50 的槽落在 to 之后一天）
        LocalDate start = LocalDate.ofInstant(from, zone).minusDays(1);
        LocalDate end = LocalDate.ofInstant(to, zone).plusDays(1);
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            for (String time : times) {
                try {
                    LocalTime lt = LocalTime.parse(time);
                    ZonedDateTime slot = ZonedDateTime.of(LocalDateTime.of(date, lt), zone);
                    Instant inst = slot.toInstant();
                    if (!inst.isBefore(from) && !inst.isAfter(to)) {
                        slots.add(slot.toOffsetDateTime());
                    }
                } catch (RuntimeException e) {
                    // 计划里混入了非法时间字符串（create/update 理论已校验），跳过该槽
                    log.debug("漏服扫描跳过非法 times 槽 zone={} time={}：{}", zone, time, e.getMessage());
                }
            }
        }
        return slots;
    }
}
