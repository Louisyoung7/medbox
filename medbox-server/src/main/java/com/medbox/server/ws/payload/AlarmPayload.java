package com.medbox.server.ws.payload;

import java.util.List;

/**
 * {@code ALARM} 帧载荷（见文档 04 第 3 章）。
 *
 * @param alarmId     告警业务号
 * @param type        告警类型：MISS / WRONG_DRUG / EXPIRED / ENV / DEVICE_OFFLINE / DEVICE_FAULT
 * @param level       严重程度：INFO / WARN / CRITICAL
 * @param message     可直接展示的文案，如"08:00 计划服药未按时服用：缺 阿莫西林、维生素D"
 * @param elderId     归属老人（与 topic 里的 elderId 一致，便于客户端免解析）
 * @param medicineIds 涉及的药品；文档示例用的是 {@code medicineIds}（数组），与 {@code medicineId} 单数形态保持一处口径
 */
public record AlarmPayload(String alarmId,
                           String type,
                           String level,
                           String message,
                           String elderId,
                           List<String> medicineIds) {
}
