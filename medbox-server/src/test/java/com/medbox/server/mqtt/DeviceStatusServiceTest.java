package com.medbox.server.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.support.MutableClock;
import com.medbox.server.support.TableInfoWarmup;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

/**
 * 设备在线状态维护与**跃变判定**（{@code feat/device-core}）。
 *
 * <p>核心要验的是"**只有状态真的变了才回调监听者**"：主控每 60s 有心跳，
 * 若每次上行都写一条事件流水，单台设备一天就是 1440 条噪声。
 *
 * <p>次要但同样关键：**心跳刷新不能因为跃变判定而失效** —— 这也是这里采用
 * "先 SELECT 再 UPDATE"，而不是给 UPDATE 加 {@code online_state != 目标值} 条件的原因。
 */
class DeviceStatusServiceTest {

    private static final String DEVICE_ID = "BOXA1001";
    private static final String ELDER_ID = "u-1001";

    private DeviceMapper deviceMapper;
    private MutableClock clock;
    private RecordingListener listener;

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(Device.class);
        deviceMapper = mock(DeviceMapper.class);
        clock = MutableClock.at(1_760_000_000_000L);
        listener = new RecordingListener(false);
        when(deviceMapper.update(any(), ArgumentMatchers.<LambdaUpdateWrapper<Device>>any())).thenReturn(1);
    }

    @Test
    @DisplayName("设备未登记 → false，不更新也不回调")
    void 设备未登记() {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any())).thenReturn(null);

        assertFalse(service().markOnline(DEVICE_ID));
        verify(deviceMapper, never()).update(any(), ArgumentMatchers.<LambdaUpdateWrapper<Device>>any());
        assertTrue(listener.events.isEmpty());
    }

    @Test
    @DisplayName("OFFLINE → ONLINE：写状态、刷心跳、回调一次 ONLINE")
    void 首次上线触发回调() {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(device(OnlineState.OFFLINE));

        assertTrue(service().markOnline(DEVICE_ID));

        assertEquals(1, listener.events.size());
        assertEquals(DEVICE_ID, listener.events.get(0).deviceId());
        assertEquals(OnlineState.ONLINE, listener.events.get(0).state());
        // 回调里拿到的实体仍是旧值，新值由参数带出（接口约定，避免实现方误判）
        assertEquals(OnlineState.OFFLINE, listener.events.get(0).device().getOnlineState());
        // 上线必须顺带刷新心跳
        assertTrue(sqlSetOfLastUpdate().contains("last_heartbeat"));
    }

    @Test
    @DisplayName("已 ONLINE 再 markAlive：仍刷心跳，但不重复回调（否则心跳会灌满事件流水）")
    void 重复心跳不重复回调() {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(device(OnlineState.ONLINE));

        DeviceStatusService service = service();
        assertTrue(service.markAlive(DEVICE_ID));
        assertTrue(service.markAlive(DEVICE_ID));
        assertTrue(service.markAlive(DEVICE_ID));

        assertTrue(listener.events.isEmpty(), "状态没变就不该回调监听者");
        // 但心跳每次都要刷新
        verify(deviceMapper, times(3)).update(any(), ArgumentMatchers.<LambdaUpdateWrapper<Device>>any());
    }

    @Test
    @DisplayName("ONLINE → OFFLINE：回调 OFFLINE，且不刷新心跳（掉线后不该更新时间戳）")
    void 离线跃变不刷心跳() {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(device(OnlineState.ONLINE));

        assertTrue(service().markOffline(DEVICE_ID));

        assertEquals(1, listener.events.size());
        assertEquals(OnlineState.OFFLINE, listener.events.get(0).state());
        assertFalse(sqlSetOfLastUpdate().contains("last_heartbeat"));
    }

    @Test
    @DisplayName("数据库异常 → 返回 false 且不抛出（消息通道不该被 DB 拖垮）")
    void 数据库异常不抛出() {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(device(OnlineState.OFFLINE));
        when(deviceMapper.update(any(), ArgumentMatchers.<LambdaUpdateWrapper<Device>>any()))
                .thenThrow(new RuntimeException("connection reset"));

        assertFalse(service().markOnline(DEVICE_ID));
        assertTrue(listener.events.isEmpty());
    }

    @Test
    @DisplayName("监听者抛异常：不影响返回值，也不影响后续监听者")
    void 监听者异常被隔离() {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(device(OnlineState.OFFLINE));

        RecordingListener second = new RecordingListener(false);
        DeviceStatusService service = new DeviceStatusService(deviceMapper, clock, List.of(
                new RecordingListener(true), second));

        assertTrue(service.markOnline(DEVICE_ID), "监听者故障不该让状态更新返回 false");
        assertEquals(1, second.events.size(), "前一个监听者挂了，后一个仍要收到");
    }

    @Test
    @DisplayName("无监听者时正常工作（mqtt 包可独立使用）")
    void 无监听者() {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(device(OnlineState.OFFLINE));

        DeviceStatusService bare = new DeviceStatusService(deviceMapper);
        assertTrue(bare.markOnline(DEVICE_ID));
    }

    @Test
    @DisplayName("更新影响 0 行 → 返回 false，不回调")
    void 更新零行不回调() {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(device(OnlineState.OFFLINE));
        when(deviceMapper.update(any(), ArgumentMatchers.<LambdaUpdateWrapper<Device>>any())).thenReturn(0);

        assertFalse(service().markOnline(DEVICE_ID));
        assertTrue(listener.events.isEmpty());
    }

    // ————————————————— 辅助 —————————————————

    private DeviceStatusService service() {
        return new DeviceStatusService(deviceMapper, clock, List.of(listener));
    }

    /** 取最后一次 UPDATE 的 SET 子句，用来断言"有没有顺带刷新心跳"。 */
    @SuppressWarnings("unchecked")
    private String sqlSetOfLastUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<Device>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(deviceMapper).update(any(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertNotNull(sqlSet);
        return sqlSet.toLowerCase();
    }

    private static Device device(OnlineState state) {
        Device device = new Device();
        device.setDeviceId(DEVICE_ID);
        device.setDeviceType(DeviceType.MAIN);
        device.setElderId(ELDER_ID);
        device.setOnlineState(state);
        return device;
    }

    /** 记录收到的跃变事件；{@code fail=true} 时按构造要求抛异常，用于验证异常隔离。 */
    private record ChangeEvent(String deviceId, Device device, OnlineState state) {
    }

    private static final class RecordingListener implements DeviceStatusChangeListener {

        final List<ChangeEvent> events = new ArrayList<>();
        private final boolean fail;

        private RecordingListener(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void onStatusChanged(Device device, OnlineState newState) {
            if (fail) {
                throw new RuntimeException("监听者故障");
            }
            events.add(new ChangeEvent(device.getDeviceId(), device, newState));
        }
    }
}
