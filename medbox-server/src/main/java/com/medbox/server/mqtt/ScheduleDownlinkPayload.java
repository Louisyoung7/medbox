package com.medbox.server.mqtt;

import com.medbox.server.domain.PlanSlot;
import java.util.List;
import lombok.Data;

/**
 * 下行业务体 {@code down/schedule}（见文档 03 第 3 章）。
 *
 * <p>{@code msgId} / {@code needAck} 由 {@link DownlinkPublisher} 统一注入信封，本类只管业务字段。
 * {@code UPSERT} 只下发「几点开几号格」的 {@code slots}，不含任何药品信息；
 * {@code DELETE} 时仅 {@code planId} / {@code op} 有效，其余字段为 {@code null}。
 * 数组里没有的格号 = 该格不提醒、不解锁。
 */
@Data
public class ScheduleDownlinkPayload {

    /** 计划业务 ID（与 {@code med_plan.plan_id} 一致，设备据此定位 / 去重）。 */
    private String planId;

    /** {@code UPSERT} / {@code DELETE}。 */
    private String op;

    /** 格号 → 开格时间（设备本地墙钟），如 [{"cellNo":1,"time":"08:00"}]；DELETE 时为 null。 */
    private List<PlanSlot> slots;

    /** 重复规则，本期仅 {@code DAILY}；DELETE 时为 null。 */
    private String repeat;

    /** 漏服判定窗口；DELETE 时为 null。 */
    private ScheduleAlarm alarm;

    /** 漏服告警窗口（对应 06 的 {@code miss_alarm_after_min}，可空）。 */
    @Data
    public static class ScheduleAlarm {
        private Integer missAfterMin;

        public ScheduleAlarm(Integer missAfterMin) {
            this.missAfterMin = missAfterMin;
        }
    }
}
