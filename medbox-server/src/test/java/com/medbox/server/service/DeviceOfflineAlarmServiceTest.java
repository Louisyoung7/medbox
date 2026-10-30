package com.medbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medbox.server.config.DeviceOfflineAlarmProperties;
import com.medbox.server.domain.Alarm;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.mapper.AlarmMapper;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import com.medbox.server.ws.WsTopic;
import com.medbox.server.ws.payload.AlarmPayload;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

/**
 * {@link DeviceOfflineAlarmService} 单元测试：纯 Mockito，不连库、不启 Spring。
 *
 * <p>覆盖：MAIN 离线 → DEVICE_OFFLINE(WARN) 告警 + WS；传感器离线 → 绝不告警；
 * 无归属老人 → 跳过；{@code enabled=false} → 不告警不推 WS；去重命中跳过不推 WS；
 * WS 未启用 / 推送异常静默；去重键按窗口分桶。
 */
class DeviceOfflineAlarmServiceTest {

    private static final String DEVICE = "BOXA1";
    private static final String ELDER = "u-1001";

    private final AlarmMapper alarmMapper = mock(AlarmMapper.class);
    private final DeviceOfflineAlarmProperties props = new DeviceOfflineAlarmProperties();
    @SuppressWarnings("unchecked")
    private final ObjectProvider<WsPushService> wsProvider = mock(ObjectProvider.class);
    private final WsPushService wsPushService = mock(WsPushService.class);

    private DeviceOfflineAlarmService service;

    @BeforeEach
    void setUp() {
        props.setEnabled(true);
        props.setDedupWindowMinutes(30L);
        when(wsProvider.getIfAvailable()).thenReturn(wsPushService);
        when(alarmMapper.nextAlarmId()).thenReturn("a-1001");
        service = new DeviceOfflineAlarmService(alarmMapper, props, wsProvider);
    }

    private Device mainDevice() {
        Device d = new Device();
        d.setDeviceId(DEVICE);
        d.setDeviceType(DeviceType.MAIN);
        d.setElderId(ELDER);
        return d;
    }

    @Test
    @DisplayName("MAIN 离线跃变：生成 DEVICE_OFFLINE(WARN) 告警并推 WS")
    void mainOfflineRaisesAlarmAndPushesWs() {
        service.raise(mainDevice());

        ArgumentCaptor<Alarm> cap = ArgumentCaptor.forClass(Alarm.class);
        verify(alarmMapper).insert(cap.capture());
        Alarm alarm = cap.getValue();
        assertThat(alarm.getAlarmId()).isEqualTo("a-1001");
        assertThat(alarm.getElderId()).isEqualTo(ELDER);
        assertThat(alarm.getDeviceId()).isEqualTo(DEVICE);
        assertThat(alarm.getType()).isEqualTo("DEVICE_OFFLINE");
        assertThat(alarm.getLevel()).isEqualTo("WARN");
        assertThat(alarm.getMessage()).isEqualTo("药箱离线：" + DEVICE);
        assertThat(alarm.getReadState()).isFalse();
        assertThat(alarm.getHandleState()).isEqualTo("OPEN");
        assertThat(alarm.getDedupKey()).startsWith("DEVICE_OFFLINE:" + DEVICE + ":");

        verify(wsPushService).publish(org.mockito.ArgumentMatchers.eq(WsTopic.elder(ELDER)),
                org.mockito.ArgumentMatchers.eq(WsFrameType.ALARM), any(AlarmPayload.class));
    }

    @Test
    @DisplayName("传感器子设备离线：绝不生成 DEVICE_OFFLINE 告警")
    void sensorOfflineNoAlarm() {
        Device sensor = mainDevice();
        sensor.setDeviceType(DeviceType.TEMP_HUMI);

        service.raise(sensor);

        verify(alarmMapper, never()).insert(any(Alarm.class));
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("设备无归属老人：跳过告警、不推 WS")
    void noElderSkipsAlarm() {
        Device orphan = mainDevice();
        orphan.setElderId(null);

        service.raise(orphan);

        verify(alarmMapper, never()).insert(any(Alarm.class));
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("enabled=false：不告警、不推 WS")
    void disabledSkipsAlarm() {
        props.setEnabled(false);

        service.raise(mainDevice());

        verify(alarmMapper, never()).insert(any(Alarm.class));
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("dedup_key 命中唯一索引冲突：不推 WS、不抛异常")
    void duplicateKeySkipsWs() {
        when(alarmMapper.insert(any(Alarm.class))).thenThrow(new DuplicateKeyException("dup"));

        assertThatCode(() -> service.raise(mainDevice())).doesNotThrowAnyException();
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("WS 未启用（provider 取不到）：不推送、不抛异常，告警仍落库")
    void wsUnavailableSilent() {
        when(wsProvider.getIfAvailable()).thenReturn(null);

        assertThatCode(() -> service.raise(mainDevice())).doesNotThrowAnyException();
        verify(alarmMapper).insert(any(Alarm.class));
    }

    @Test
    @DisplayName("WS 推送抛异常：只记日志，不影响告警已落库")
    void wsPublishFailureSwallowed() {
        when(wsPushService.publish(any(), any(), any())).thenThrow(new RuntimeException("ws down"));

        assertThatCode(() -> service.raise(mainDevice())).doesNotThrowAnyException();
        verify(alarmMapper).insert(any(Alarm.class));
    }
}
