package com.medbox.server.common.security;

/**
 * 设备 → 归属老人的解析器（扩展点，由 {@code DeviceOwnerResolverImpl} 实现）。
 *
 * <p>{@code AccessService#requireDevice} 通过 {@code ObjectProvider} 取用它；未注册实现时抛
 * 50000。系统只有主控一种设备记录，实现直接查 {@code device.elder_id}，不再有子设备反查。
 */
public interface DeviceOwnerResolver {

    /**
     * 解析设备归属的老人。
     *
     * @param deviceId 药箱主控业务 ID
     * @return 归属老人的 {@code user_id}；设备不存在返回 {@code null}
     */
    String resolveElderId(String deviceId);
}
