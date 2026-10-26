package com.medbox.server.domain.enums;

/**
 * 命令下发状态（{@code device_command.state}，见文档 06 的 2.17）：
 * {@code PENDING} 受理待发 → {@code SUCCESS} / {@code FAILED} 终态（只翻一次）。
 */
public enum DeviceCommandState {
    PENDING, SUCCESS, FAILED
}
