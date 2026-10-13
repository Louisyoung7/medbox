package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.security.DeviceOwnerResolver;
import com.medbox.server.domain.Device;
import com.medbox.server.mapper.DeviceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 设备归属老人解析（{@code feat/device-core}）。
 *
 * <p><b>这个实现类一注册，{@code AccessService#requireDevice} 就自动可用</b>：那边是用
 * {@code ObjectProvider<DeviceOwnerResolver>} 取的，没实现时抛 50000。
 * 接入后不需要改 {@code AccessService} 一行代码 —— 这正是当初把它定义成接口的原因。
 *
 * <p><b>子设备要经 {@code parent_device_id} 反查一次父设备</b>：摄像头的抓拍上传、
 * 传感器相关接口都直接用子设备自己的 {@code deviceId} 调用（见文档 02 的 4.1：
 * {@code POST /devices/BOXA1001-S-CAM01/captures}），而权限是挂在老人上的。
 * 好在子设备的 {@code elder_id} 与父设备一致（见 06 的 2.3），
 * 因此这里仍走父设备取一次，保证"父设备的归属"是唯一事实来源，不受子设备脏数据影响。
 *
 * <p><b>不加缓存</b>：设备表数据量极小（每老人个位数），且本方法是每次请求 1~2 次主键等值查询。
 * 若将来调用频次上去，在本类里加一层本地缓存即可，调用方与 {@code AccessService} 都不用改。
 */
@Component
public class DeviceOwnerResolverImpl implements DeviceOwnerResolver {

    private static final Logger log = LoggerFactory.getLogger(DeviceOwnerResolverImpl.class);

    private final DeviceMapper deviceMapper;

    public DeviceOwnerResolverImpl(DeviceMapper deviceMapper) {
        this.deviceMapper = deviceMapper;
    }

    /**
     * 解析设备归属老人。
     *
     * @param deviceId 主控或传感器子设备的业务编号
     * @return 归属老人的 {@code user_id}；设备不存在、或子设备的父设备缺失（脏数据）时返回 {@code null}
     *         —— 调用方（{@code AccessService}）会把 null 判成 <b>40301</b>，而不是 40401（防设备编号枚举）
     */
    @Override
    public String resolveElderId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }

        Device device = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                .eq(Device::getDeviceId, deviceId));
        if (device == null) {
            return null;
        }

        // 主控：直接取自己的 elder_id
        if (device.getParentDeviceId() == null || device.getParentDeviceId().isBlank()) {
            return device.getElderId();
        }

        // 子设备：归属以父设备为准（子设备的 elder_id 只是冗余，便于按老人查询）
        Device parent = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                .eq(Device::getDeviceId, device.getParentDeviceId()));
        if (parent == null) {
            log.warn("子设备的父设备缺失，无法解析归属 childDeviceId={} parentDeviceId={}",
                    deviceId, device.getParentDeviceId());
            return null;
        }
        return parent.getElderId();
    }
}
