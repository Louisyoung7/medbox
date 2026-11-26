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
 * <p>系统只有主控一种设备记录，归属直接取 {@code device.elder_id}；不再有子设备经
 * {@code parent_device_id} 反查父设备的逻辑。设备不存在时返回 {@code null}，
 * 调用方（{@code AccessService}）判成 <b>40301</b>（防设备编号枚举）。
 *
 * <p>不加缓存：设备表数据量极小（每老人个位数），每次请求 1 次主键等值查询。
 */
@Component
public class DeviceOwnerResolverImpl implements DeviceOwnerResolver {

    private static final Logger log = LoggerFactory.getLogger(DeviceOwnerResolverImpl.class);

    private final DeviceMapper deviceMapper;

    public DeviceOwnerResolverImpl(DeviceMapper deviceMapper) {
        this.deviceMapper = deviceMapper;
    }

    @Override
    public String resolveElderId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        Device device = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                .eq(Device::getDeviceId, deviceId));
        if (device == null) {
            log.debug("设备未登记，无法解析归属 deviceId={}", deviceId);
            return null;
        }
        return device.getElderId();
    }
}
