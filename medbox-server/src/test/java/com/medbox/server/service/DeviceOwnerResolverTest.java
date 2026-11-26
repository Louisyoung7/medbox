package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.support.TableInfoWarmup;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/**
 * 设备归属老人解析（{@link DeviceOwnerResolverImpl}）。
 *
 * <p>系统只有主控一种设备记录，实现直接查 {@code device.elder_id}；设备不存在时返回 null，
 * 调用方（{@code AccessService}）按 40301 处理。
 */
class DeviceOwnerResolverTest {

    private static final String ELDER_ID = "u-1001";
    private static final String DEVICE_ID = "BOXA1001";

    private DeviceMapper deviceMapper;
    private DeviceOwnerResolverImpl resolver;

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(Device.class);
        deviceMapper = mock(DeviceMapper.class);
        resolver = new DeviceOwnerResolverImpl(deviceMapper);
    }

    @Test
    @DisplayName("设备登记：直接取 elder_id")
    void 直接命中() {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(device(DEVICE_ID, ELDER_ID));

        assertEquals(ELDER_ID, resolver.resolveElderId(DEVICE_ID));
    }

    @Test
    @DisplayName("设备不存在 → null（调用方按 40301 处理，防设备编号枚举）")
    void 设备不存在返回_null() {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any())).thenReturn(null);

        assertNull(resolver.resolveElderId(DEVICE_ID));
    }

    @Test
    @DisplayName("编号为 null / 空白 → null，不查库")
    void 空编号不查库() {
        assertNull(resolver.resolveElderId(null));
        assertNull(resolver.resolveElderId("   "));

        verifyNoInteractions(deviceMapper);
    }

    private static Device device(String deviceId, String elderId) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setElderId(elderId);
        device.setTimezone("Asia/Shanghai");
        device.setOnlineState(OnlineState.OFFLINE);
        device.setCreatedAt(OffsetDateTime.parse("2026-10-12T00:00:00Z"));
        return device;
    }
}
