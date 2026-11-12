package com.medbox.server.domain;

import lombok.Data;

/**
 * 服药计划槽：固定药格的「格号 → 开格时间」（见文档 06 的 2.6）。
 *
 * <p>格号 1 / 2 / 3 由固件固定对应早 / 中 / 晚；{@code time} 为 HH:mm 的
 * 设备本地墙钟（时区取 {@code device.timezone}，缺省 {@code Asia/Shanghai}），不转 UTC。
 * 计划与药品无关，本对象不携带药品、剂量信息。
 */
@Data
public class PlanSlot {

    /** 药格格号：1 / 2 / 3。 */
    private Integer cellNo;

    /** 开格时间，HH:mm 设备本地墙钟。 */
    private String time;
}
