package com.medbox.server.common.security;

/**
 * 设备 → 归属老人的解析器（**扩展点，本分支不实现**）。
 *
 * <p>为什么留成接口：设备维度校验（"这个 deviceId 属不属于我能监护的老人"）需要查 {@code device} 表，
 * 但 {@code Device} 实体与 {@code DeviceMapper} 属 P0 第 5 步 {@code feat/device-core}，在
 * {@code feat/backend-authz} 之后 —— 本分支不越界提前建设备模块。
 *
 * <p>{@code AccessService#requireDevice} 通过 {@code ObjectProvider} 取用它：
 * <ul>
 *   <li>未注册实现（当前状态）→ 抛 50000，提示等 {@code feat/device-core} 接入；</li>
 *   <li>{@code feat/device-core} 里加一个 {@code @Component} 实现（查 {@code device.parent_device_id} /
 *       {@code device.elder_id}，支持子设备反查所属药箱），即可直接生效，无需改动调用方。</li>
 * </ul>
 */
public interface DeviceOwnerResolver {

    /**
     * 解析设备归属的老人。
     *
     * @param deviceId 设备业务 ID（主控或传感器子设备）
     * @return 归属老人的 {@code user_id}；设备不存在返回 {@code null}
     */
    String resolveElderId(String deviceId);
}
