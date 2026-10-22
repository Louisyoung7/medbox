package com.medbox.server.mqtt;

import java.util.List;
import lombok.Data;

/**
 * 下行业务体 {@code down/schedule}（见文档 03 第 3 章）。
 *
 * <p>{@code msgId} / {@code needAck} 由 {@link DownlinkPublisher} 统一注入信封，本类只管业务字段。
 * {@code op} 除文档已给的 {@code UPSERT} 外，本分支补 {@code DELETE}（删除/停用时用，
 * 设备按 {@code planId} 停掉该计划提醒）。{@code DELETE} 时仅 {@code planId} / {@code op} 有效，
 * 其余字段为 {@code null}。
 */
@Data
public class ScheduleDownlinkPayload {

    /** 计划业务 ID（与 {@code med_plan.plan_id} 一致，设备据此定位 / 去重）。 */
    private String planId;

    /** {@code UPSERT} / {@code DELETE}。 */
    private String op;

    /** 本地墙钟服药时刻，如 {@code ["08:00","20:00"]}；DELETE 时为 null。 */
    private List<String> times;

    /** 重复规则，本期仅 {@code DAILY}；DELETE 时为 null。 */
    private String repeat;

    /** 漏服判定窗口；DELETE 时为 null。 */
    private ScheduleAlarm alarm;

    /** 药品明细；DELETE 时为 null。 */
    private List<ScheduleItem> items;

    /** 漏服告警窗口（对应 06 的 {@code miss_alarm_after_min}，可空）。 */
    @Data
    public static class ScheduleAlarm {
        private Integer missAfterMin;

        public ScheduleAlarm(Integer missAfterMin) {
            this.missAfterMin = missAfterMin;
        }
    }

    /** 一条服药明细（对应 {@code med_plan_item}）。 */
    @Data
    public static class ScheduleItem {
        private String itemId;
        private String medicineId;
        private String dose;
        private String unit;

        public ScheduleItem(String itemId, String medicineId, String dose, String unit) {
            this.itemId = itemId;
            this.medicineId = medicineId;
            this.dose = dose;
            this.unit = unit;
        }
    }
}
