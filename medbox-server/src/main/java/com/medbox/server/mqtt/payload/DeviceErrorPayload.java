package com.medbox.server.mqtt.payload;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 上行·设备侧异常 {@code up/event/error}（见文档 03 第 3 章）。
 *
 * <p>只报<b>设备自己出问题</b>：{@code DOOR_TIMEOUT} 门超时 / {@code CAMERA_FAILED} 识别失败 /
 * {@code HW_FAULT} 硬件故障。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DeviceErrorPayload extends BaseUpPayload {

    /** {@code DOOR_TIMEOUT} / {@code CAMERA_FAILED} / {@code HW_FAULT}。 */
    private String type;

    /** 简要说明，如"仓门开启超过 60s 未关闭"。 */
    private String detail;
}
