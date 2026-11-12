package com.medbox.server.ws.payload;

/**
 * {@code ALARM} 帧载荷（见文档 04 第 3 章）。
 *
 * <p>告警文案自带格号与时间（如「08:00 1 号格（早间）未按时取药」），不再携带 {@code medicineIds}。
 *
 * @param alarmId 告警业务号
 * @param type    告警类型：MISS / ENV / DEVICE_OFFLINE / DEVICE_FAULT
 * @param level   严重程度：INFO / WARN / CRITICAL
 * @param message 可直接展示的文案
 * @param elderId 归属老人（与 topic 里的 elderId 一致）
 */
public record AlarmPayload(String alarmId,
                           String type,
                           String level,
                           String message,
                           String elderId) {
}
