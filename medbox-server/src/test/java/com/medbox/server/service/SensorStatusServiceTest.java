package com.medbox.server.service;

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
import com.medbox.server.config.DeviceProperties;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mqtt.DeviceStatusChangeListener;
import com.medbox.server.mqtt.payload.SensorHeartbeatPayload;
import com.medbox.server.support.MutableClock;
import com.medbox.server.support.TableInfoWarmup;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

/**
 * 传感器子设备在线状态维护（{@code feat/device-sensor}，规则表见 07 清单本分支条目）。
 *
 * <p>核心要验的四条：
 * <ul>
 *   <li><b>跃变才通知监听者</b>（心跳 60s 一次，每次上行都写事件流水会灌满事件表）；</li>
 *   <li><b>online=true 必刷心跳，online=false 必不刷</b>（{@code last_heartbeat} 语义 = 最后活着的时刻，
 *       180s 扫描全靠它）；</li>
 *   <li><b>只认本主控的子设备</b>：未登记 / 挂在别的主控下的 sensorId 跳过且不建行；</li>
 *   <li><b>180s 扫描只动子设备、不碰告警</b>：本类不持有 AlarmMapper（构造上就碰不了告警表），
 *       且不判 {@code MAIN}（主控离线由 LWT 管）。</li>
 * </ul>
 */
class SensorStatusServiceTest {

    private static final String MAIN_ID = "BOXA1001";
    private static final String SENSOR_TH = "BOXA1001-S-TH01";
    private static final String SENSOR_CAM = "BOXA1001-S-CAM01";
    private static final String ELDER_ID = "u-1001";
    private static final long T0 = 1_760_000_000_000L;

    private DeviceMapper deviceMapper;
    private DeviceProperties props;
    private MutableClock clock;
    private RecordingListener listener;

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(Device.class);
        deviceMapper = mock(DeviceMapper.class);
        props = new DeviceProperties();
        clock = MutableClock.at(T0);
        listener = new RecordingListener();
        when(deviceMapper.update(any(), ArgumentMatchers.<LambdaUpdateWrapper<Device>>any())).thenReturn(1);
    }

    // ————————————————— applyHeartbeat —————————————————

    @Test
    @DisplayName("online=true 且当前 OFFLINE → 置 ONLINE、刷心跳、回调一次")
    void 上线跃变刷心跳并回调() {
        givenMainAndChildren(sensor(SENSOR_TH, OnlineState.OFFLINE));

        service().applyHeartbeat(MAIN_ID, List.of(state(SENSOR_TH, true)));

        assertEquals(1, listener.events.size());
        assertEquals(SENSOR_TH, listener.events.get(0));
        assertTrue(sqlSetOfLastUpdate().contains("last_heartbeat"), "上线必须顺带刷新心跳");
    }

    @Test
    @DisplayName("online=true 且当前 ONLINE → 仍刷心跳，但不重复回调")
    void 重复心跳只刷时间不回调() {
        givenMainAndChildren(sensor(SENSOR_TH, OnlineState.ONLINE));

        SensorStatusService service = service();
        service.applyHeartbeat(MAIN_ID, List.of(state(SENSOR_TH, true)));
        service.applyHeartbeat(MAIN_ID, List.of(state(SENSOR_TH, true)));

        assertTrue(listener.events.isEmpty(), "状态没变就不该回调监听者");
        verify(deviceMapper, times(2)).update(any(), ArgumentMatchers.<LambdaUpdateWrapper<Device>>any());
        assertTrue(sqlSetOfLastUpdate().contains("last_heartbeat"));
    }

    @Test
    @DisplayName("online=false 且当前 ONLINE → 立即置 OFFLINE、不刷心跳、回调一次")
    void 显式离线立即生效不刷心跳() {
        givenMainAndChildren(sensor(SENSOR_TH, OnlineState.ONLINE));

        service().applyHeartbeat(MAIN_ID, List.of(state(SENSOR_TH, false)));

        assertEquals(1, listener.events.size());
        assertFalse(sqlSetOfLastUpdate().contains("last_heartbeat"),
                "掉线后不该更新心跳时间戳，否则 180s 扫描永远判不出离线");
    }

    @Test
    @DisplayName("online=false 且当前 OFFLINE → 完全不写库（幂等）")
    void 已离线再报离线不写库() {
        givenMainAndChildren(sensor(SENSOR_TH, OnlineState.OFFLINE));

        service().applyHeartbeat(MAIN_ID, List.of(state(SENSOR_TH, false)));

        verify(deviceMapper, never()).update(any(), ArgumentMatchers.<LambdaUpdateWrapper<Device>>any());
        assertTrue(listener.events.isEmpty());
    }

    @Test
    @DisplayName("sensorId 未登记 / 非本主控子设备 → 跳过，不自动建行")
    void 未登记子设备被跳过() {
        givenMainAndChildren(sensor(SENSOR_TH, OnlineState.OFFLINE));

        service().applyHeartbeat(MAIN_ID, List.of(state("BOXA9999-S-TH01", true)));

        verify(deviceMapper, never()).update(any(), ArgumentMatchers.<LambdaUpdateWrapper<Device>>any());
        verify(deviceMapper, never()).insert(any(Device.class));
        assertTrue(listener.events.isEmpty());
    }

    @Test
    @DisplayName("主控未登记 → 整条忽略，不查子设备、不更新")
    void 主控未登记整条忽略() {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any())).thenReturn(null);

        service().applyHeartbeat("NOT-EXIST", List.of(state(SENSOR_TH, true)));

        verify(deviceMapper, never()).selectList(any());
        verify(deviceMapper, never()).update(any(), ArgumentMatchers.<LambdaUpdateWrapper<Device>>any());
    }

    @Test
    @DisplayName("一帧多设备：单条处理失败不影响其余子设备")
    void 单条失败不影响其余() {
        Device th = sensor(SENSOR_TH, OnlineState.OFFLINE);
        Device cam = sensor(SENSOR_CAM, OnlineState.OFFLINE);
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(mainDevice());
        when(deviceMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(List.of(th, cam));
        when(deviceMapper.update(any(), ArgumentMatchers.<LambdaUpdateWrapper<Device>>any()))
                .thenThrow(new RuntimeException("connection reset"))
                .thenReturn(1);

        service().applyHeartbeat(MAIN_ID, List.of(state(SENSOR_TH, true), state(SENSOR_CAM, true)));

        verify(deviceMapper, times(2)).update(any(), ArgumentMatchers.<LambdaUpdateWrapper<Device>>any());
        assertEquals(List.of(SENSOR_CAM), listener.events, "第一台失败不该影响第二台");
    }

    // ————————————————— scanOffline —————————————————

    @Test
    @DisplayName("ONLINE 且心跳超过 180s 未刷新 → 判 OFFLINE 并回调；不写告警表")
    void 超时子设备判离线() {
        when(deviceMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(List.of(sensor(SENSOR_TH, OnlineState.ONLINE)));

        int changed = service().scanOffline(clock);

        assertEquals(1, changed);
        assertEquals(List.of(SENSOR_TH), listener.events);
        assertFalse(sqlSetOfLastUpdate().contains("last_heartbeat"), "判离线不刷心跳");
        // 不生成 DEVICE_OFFLINE 告警是构造性保证：本类没有 AlarmMapper 字段，想写也写不了
    }

    @Test
    @DisplayName("无超时子设备 → 返回 0，不写库")
    void 无超时不写库() {
        when(deviceMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(List.of());

        assertEquals(0, service().scanOffline(clock));
        verify(deviceMapper, never()).update(any(), ArgumentMatchers.<LambdaUpdateWrapper<Device>>any());
    }

    @Test
    @DisplayName("扫描只查 ONLINE 的非 MAIN 设备（查询条件即过滤口径）")
    void 扫描查询口径() {
        when(deviceMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(List.of());

        service().scanOffline(clock);

        ArgumentCaptor<LambdaQueryWrapper<Device>> captor = queryCaptor();
        verify(deviceMapper).selectList(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        assertNotNull(sql);
        String normalized = sql.toLowerCase();
        assertTrue(normalized.contains("device_type"), "必须限定子设备");
        assertTrue(normalized.contains("online_state"), "必须只扫 ONLINE");
        assertTrue(normalized.contains("last_heartbeat"), "必须按心跳超时过滤");
    }

    // ————————————————— 辅助 —————————————————

    private SensorStatusService service() {
        return new SensorStatusService(deviceMapper, props, List.of(listener), clock);
    }

    private void givenMainAndChildren(Device... children) {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(mainDevice());
        when(deviceMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(List.of(children));
    }

    /** 取最后一次 UPDATE 的 SET 子句，断言"有没有顺带刷新心跳"（同 DeviceStatusServiceTest 的做法）。 */
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

    private static SensorHeartbeatPayload.SensorState state(String sensorId, boolean online) {
        SensorHeartbeatPayload.SensorState state = new SensorHeartbeatPayload.SensorState();
        state.setSensorId(sensorId);
        state.setOnline(online);
        return state;
    }

    private static Device mainDevice() {
        Device device = new Device();
        device.setDeviceId(MAIN_ID);
        device.setDeviceType(DeviceType.MAIN);
        device.setElderId(ELDER_ID);
        device.setOnlineState(OnlineState.ONLINE);
        return device;
    }

    private static Device sensor(String deviceId, OnlineState state) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setDeviceType(DeviceType.TEMP_HUMI);
        device.setParentDeviceId(MAIN_ID);
        device.setElderId(ELDER_ID);
        device.setOnlineState(state);
        device.setLastHeartbeat(OffsetDateTime.now());
        return device;
    }

    /** 记录收到跃变事件的 deviceId。 */
    private static final class RecordingListener implements DeviceStatusChangeListener {

        final List<String> events = new ArrayList<>();

        @Override
        public void onStatusChanged(Device device, OnlineState newState) {
            events.add(device.getDeviceId());
        }
    }
}
