package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.DeviceType;
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
 * <p>这个类是 {@code AccessService#requireDevice} 的前置依赖：解析不出来就一律 40301。
 * 因此**子设备经父设备反查**这条路径必须验到 —— 摄像头抓拍上传
 * （{@code POST /devices/BOXA1001-S-CAM01/captures}）用的就是子设备编号。
 */
class DeviceOwnerResolverTest {

    private static final String ELDER_ID = "u-1001";
    private static final String MAIN_ID = "BOXA1001";
    private static final String SENSOR_ID = "BOXA1001-S-CAM01";

    private DeviceMapper deviceMapper;
    private DeviceOwnerResolverImpl resolver;

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(Device.class);
        deviceMapper = mock(DeviceMapper.class);
        resolver = new DeviceOwnerResolverImpl(deviceMapper);
    }

    @Test
    @DisplayName("主控：直接取自己的 elder_id")
    void 主控直接命中() {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(main(MAIN_ID, ELDER_ID));

        assertEquals(ELDER_ID, resolver.resolveElderId(MAIN_ID));
    }

    @Test
    @DisplayName("子设备：经 parent_device_id 反查父设备取归属")
    void 子设备经父设备反查() {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(sensor(SENSOR_ID, MAIN_ID, ELDER_ID))
                .thenReturn(main(MAIN_ID, ELDER_ID));

        assertEquals(ELDER_ID, resolver.resolveElderId(SENSOR_ID));
    }

    @Test
    @DisplayName("设备不存在 → null（调用方按 40301 处理，防设备编号枚举）")
    void 设备不存在返回_null() {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any())).thenReturn(null);

        assertNull(resolver.resolveElderId(MAIN_ID));
    }

    @Test
    @DisplayName("子设备的父设备缺失（脏数据）→ null，不抛异常")
    void 父设备缺失返回_null() {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(sensor(SENSOR_ID, "GHOST-PARENT", ELDER_ID))
                .thenReturn(null);

        assertNull(resolver.resolveElderId(SENSOR_ID));
    }

    @Test
    @DisplayName("编号为 null / 空白 → null，不查库")
    void 空编号不查库() {
        assertNull(resolver.resolveElderId(null));
        assertNull(resolver.resolveElderId("   "));

        org.mockito.Mockito.verifyNoInteractions(deviceMapper);
    }

    @Test
    @DisplayName("子设备的父设备查询被隔离：即使子设备自己带了 elder_id，也以父设备为准")
    void 归属以父设备为准() {
        Device child = sensor(SENSOR_ID, MAIN_ID, "u-脏数据");
        Device parent = main(MAIN_ID, ELDER_ID);
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(child)
                .thenReturn(parent);

        assertEquals(ELDER_ID, resolver.resolveElderId(SENSOR_ID));
    }

    // ————————————————— 辅助 —————————————————

    private static Device main(String deviceId, String elderId) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setDeviceType(DeviceType.MAIN);
        device.setElderId(elderId);
        device.setTimezone("Asia/Shanghai");
        device.setOnlineState(OnlineState.OFFLINE);
        device.setCreatedAt(OffsetDateTime.parse("2026-10-12T00:00:00Z"));
        return device;
    }

    private static Device sensor(String deviceId, String parentDeviceId, String elderId) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setDeviceType(DeviceType.CAMERA);
        device.setParentDeviceId(parentDeviceId);
        device.setElderId(elderId);
        device.setOnlineState(OnlineState.OFFLINE);
        return device;
    }
}
