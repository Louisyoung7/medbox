package com.medbox.server.domain.enums;

/**
 * 控制命令白名单（{@code down/command}，见文档 02 第 4 章）：蜂鸣 / 解锁 / 重启 / 校准。
 * 客户端与 mock（02 §4.0 小程序侧落地）已按这四个值对接，新增取值要先改契约。
 */
public enum DeviceCmd {

    /** 蜂鸣提醒，{@code params.durationSec} 控制时长。 */
    BUZZ,

    /** 远程解锁仓门。 */
    UNLOCK,

    /** 设备重启。 */
    REBOOT,

    /** 传感器校准。 */
    CALIBRATE;

    /** 解析客户端传入；未知返回 {@code null}（由调用方转 40001，不在这里抛，方便单测）。 */
    public static DeviceCmd of(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
