package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.medbox.server.config.DeviceProperties;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mqtt.DeviceStatusChangeListener;
import com.medbox.server.support.MutableClock;
import com.medbox.server.support.TableInfoWarmup;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

/**
 * 主控（MAIN）离线定时扫描（{@code feat/alarm-device-offline}）。
 *
 * <p>核心要验的几点（与 {@code SensorStatusServiceTest} 同范式）：
 * <ul>
 *   <li><b>只动 MAIN</b>：查询条件即 {@code eq(MAIN)}，传感器子设备离线不归本类管、也不告警；</li>
 *   <li><b>只动 ONLINE</b>：已 OFFLINE 不重复写库 / 不重复回调（幂等）；</li>
 *   <li><b>心跳超阈值或 NULL 才判离线</b>；判离线只置 {@code online_state}、不刷心跳；</li>
 *   <li>跃变才回调监听者（{@code DeviceStatusEventBridge} 写流水 + 推状态、{@code DeviceOfflineAlarmListener} 生成告警）。</li>
 * </ul>
 */
class DeviceOfflineScanServiceTest {

    private static final String MAIN_ID = "BOXA1001";
    private static final long T0 = 1_760_000_000_000L;
    private static final long THRESHOLD = 180_000L;

    private DeviceMapper deviceMapper;
    private DeviceProperties props;
    private MutableClock clock;
    private RecordingListener listener;

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(Device.class);
        deviceMapper = mock(DeviceMapper.class);
        props = new DeviceProperties();
        props.setMainOfflineAfterMs(THRESHOLD);
        clock = MutableClock.at(T0);
        listener = new RecordingListener();
        when(deviceMapper.update(any(), ArgumentMatchers.<LambdaUpdateWrapper<Device>>any())).thenReturn(1);
    }

    private DeviceOfflineScanService service() {
        return new DeviceOfflineScanService(deviceMapper, props, List.of(listener), clock);
    }

    private Device main(OnlineState state) {
        Device d = new Device();
        d.setDeviceId(MAIN_ID);
        d.setDeviceType(DeviceType.MAIN);
        d.setElderId("u-1001");
        d.setOnlineState(state);
        return d;
    }

    @Test
    @DisplayName("MAIN ONLINE 且心跳超阈值 → 判 OFFLINE、回调一次、置 online_state、不刷心跳")
    void staleMainMarkedOffline() {
        Device stale = main(OnlineState.ONLINE);
        stale.setLastHeartbeat(OffsetDateTime.now(clock).minus(THRESHOLD + 1000, ChronoUnit.MILLIS));
        when(deviceMapper.selectList(any())).thenReturn(List.of(stale));

        int changed = service().scanOffline(clock);

        assertEquals(1, changed);
        assertEquals(List.of(MAIN_ID), listener.events);
        String sqlSet = sqlSetOfLastUpdate();
        assertTrue(sqlSet.contains("online_state"), "判离线必须置 online_state");
        assertFalse(sqlSet.contains("last_heartbeat"), "判离线不该刷心跳时间戳");
    }

    @Test
    @DisplayName("无超时主控（查询口径已过滤新鲜心跳）→ 返回 0，不写库、不回调")
    void noStaleDevicesReturnsZero() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        assertEquals(0, service().scanOffline(clock));
        verify(deviceMapper, never()).update(any(), ArgumentMatchers.<LambdaUpdateWrapper<Device>>any());
        assertTrue(listener.events.isEmpty());
    }

    @Test
    @DisplayName("MAIN ONLINE 且 last_heartbeat 为 NULL → 防御性判离线")
    void nullHeartbeatMainMarkedOffline() {
        Device stale = main(OnlineState.ONLINE);
        stale.setLastHeartbeat(null);
        when(deviceMapper.selectList(any())).thenReturn(List.of(stale));

        int changed = service().scanOffline(clock);

        assertEquals(1, changed);
        assertEquals(List.of(MAIN_ID), listener.events);
    }

    @Test
    @DisplayName("已被判 OFFLINE 的 MAIN（并发/重复扫到）→ 不重复写库、不重复回调")
    void alreadyOfflineIdempotent() {
        Device offline = main(OnlineState.OFFLINE);
        when(deviceMapper.selectList(any())).thenReturn(List.of(offline));

        assertEquals(0, service().scanOffline(clock));
        verify(deviceMapper, never()).update(any(), ArgumentMatchers.<LambdaUpdateWrapper<Device>>any());
        assertTrue(listener.events.isEmpty());
    }

    @Test
    @DisplayName("扫描查询口径：仅 MAIN + ONLINE + 心跳超时（设备类型/在线态/心跳三条件都在）")
    void scanQueryFilter() {
        when(deviceMapper.selectList(any())).thenReturn(List.of());

        service().scanOffline(clock);

        ArgumentCaptor<LambdaQueryWrapper<Device>> captor = queryCaptor();
        verify(deviceMapper).selectList(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        assertNotNull(sql);
        String normalized = sql.toLowerCase();
        assertTrue(normalized.contains("device_type"), "必须限定 MAIN 主控");
        assertTrue(normalized.contains("online_state"), "必须只扫 ONLINE");
        assertTrue(normalized.contains("last_heartbeat"), "必须按心跳超时过滤");
    }

    // ————————————————— 辅助 —————————————————

    @SuppressWarnings("unchecked")
    private String sqlSetOfLastUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<Device>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(deviceMapper, org.mockito.Mockito.atLeastOnce()).update(any(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertNotNull(sqlSet);
        return sqlSet.toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<LambdaQueryWrapper<Device>> queryCaptor() {
        return ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    }

    /** 记录收到跃变事件的 deviceId，用于验证"只在跃变时回调"。 */
    private static final class RecordingListener implements DeviceStatusChangeListener {

        final List<String> events = new ArrayList<>();

        @Override
        public void onStatusChanged(Device device, OnlineState newState) {
            events.add(device.getDeviceId());
        }
    }
}
