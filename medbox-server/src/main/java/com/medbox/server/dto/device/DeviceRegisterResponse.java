package com.medbox.server.dto.device;

import java.time.OffsetDateTime;

/**
 * {@code POST /devices/register} 的响应体（见文档 02b §1.0）。
 *
 * <p><b>注册即生效</b>：返回成功时设备已落库，无需任何审核或确认步骤。
 * {@code onlineState} 恒为 {@code OFFLINE}：登记只是把设备写进库，真正的在线要等设备
 * 带着这个 {@code deviceId} 连上 EMQX、由遗嘱 / status 驱动。
 *
 * @param deviceId    药箱主控编号（设备自生成）
 * @param elderId     归属老人
 * @param productKey  实际落库的 productKey（请求没填时回显默认值）
 * @param timezone    实际落库的时区（请求没填时回显默认值）
 * @param onlineState 恒为 {@code OFFLINE}
 * @param registeredAt 登记时间（UTC）
 */
public record DeviceRegisterResponse(String deviceId,
                                     String elderId,
                                     String productKey,
                                     String timezone,
                                     String onlineState,
                                     OffsetDateTime registeredAt) {
}
