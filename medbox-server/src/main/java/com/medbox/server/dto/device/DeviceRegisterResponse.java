package com.medbox.server.dto.device;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code POST /devices/register} 的响应体（见文档 02 第 4 章）。
 *
 * <p><b>注册即生效</b>：返回成功时主控与全部子设备都已落库，无需任何审核或确认步骤
 * （07 清单 {@code feat/device-core} 的"注册即生效"）。
 *
 * <p>{@code onlineState} 恒为 {@code OFFLINE}：登记只是把设备写进库，
 * 真正的在线要等设备带着这个 {@code deviceId} 连上 EMQX、由遗嘱 / status 驱动
 * （见文档 06 的 2.3「在线状态怎么来」）。这个字段放在响应里，是为了让小程序
 * 不必再调一次 {@code GET /devices/{id}/status} 才能渲染。
 *
 * @param deviceId    药箱主控编号
 * @param deviceType  恒为 {@code MAIN}
 * @param elderId     归属老人
 * @param productKey  实际落库的 productKey（请求没填时会回显默认值）
 * @param timezone    实际落库的时区（请求没填时会回显默认值）
 * @param onlineState 恒为 {@code OFFLINE}
 * @param registeredAt 登记时间（UTC）
 * @param sensors     已登记的子设备清单（请求没带则为空数组）
 */
public record DeviceRegisterResponse(String deviceId,
                                     String deviceType,
                                     String elderId,
                                     String productKey,
                                     String timezone,
                                     String onlineState,
                                     OffsetDateTime registeredAt,
                                     List<RegisteredSensor> sensors) {

    /** 已登记的子设备（响应里带类型，便于小程序直接渲染图标）。 */
    public record RegisteredSensor(String deviceId, String deviceType) {
    }
}
