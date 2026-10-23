package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.mapper.DeviceMapper;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 设备本地时区解析（公共组件，抽自 {@code PlanService}）。
 *
 * <p><b>口径</b>：服药时间的时区以设备为准，取老人名下<b> MAIN 主控设备</b>的 {@code device.timezone}；
 * 无设备 / 空 / 非法一律回退 {@code Asia/Shanghai}（见 06 §2.6.1）。
 *
 * <p><b>为什么抽成公共组件</b>：{@code PlanService.nextDoses}（未来待服时间线）与
 * {@code feat/dispense-consume} 的"就近槽 + 计划时间"换算都依赖同一套时区口径，
 * 放一处避免两处实现漂移。{@code PlanService} 的私有 {@code resolveTimezone} 已改为委托本类。
 */
@Component
public class DeviceTimezoneResolver {

    private static final Logger log = LoggerFactory.getLogger(DeviceTimezoneResolver.class);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    private final DeviceMapper deviceMapper;

    public DeviceTimezoneResolver(DeviceMapper deviceMapper) {
        this.deviceMapper = deviceMapper;
    }

    /** 取老人 MAIN 主控设备的 timezone；无设备 / 空 / 非法 → Asia/Shanghai。 */
    public ZoneId resolve(String elderId) {
        if (elderId == null || elderId.isBlank()) {
            return DEFAULT_ZONE;
        }
        Device device = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                .eq(Device::getElderId, elderId)
                .eq(Device::getDeviceType, DeviceType.MAIN));
        if (device != null && device.getTimezone() != null && !device.getTimezone().isBlank()) {
            try {
                return ZoneId.of(device.getTimezone().trim());
            } catch (Exception e) {
                log.warn("老人的 MAIN 设备时区非法，回退默认时区 elderId={} timezone={}",
                        elderId, device.getTimezone());
            }
        }
        return DEFAULT_ZONE;
    }
}
