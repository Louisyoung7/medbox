package com.medbox.server.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medbox.server.config.DeviceOfflineAlarmProperties;
import com.medbox.server.domain.Alarm;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.mapper.AlarmMapper;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.support.TableInfoWarmup;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import com.medbox.server.ws.WsTopic;
import com.medbox.server.ws.payload.AlarmPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@link DeviceOfflineAlarmService} 集成测试：真实 Spring 链路（Bean 装配），
 * 仅把 Mapper / WS 出口 / 配置换成替身，不连 PostgreSQL（H2 替换 DataSource）。重点验：
 * MAIN 离线 → DEVICE_OFFLINE(WARN) 告警落库 + WS 推送；传感器 / 无老人 / 关闭开关 / 去重命中 均不告警。
 */
@SpringBootTest
class DeviceOfflineAlarmIntegrationTest {

    @TestConfiguration
    static class H2DataSourceConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-test-device-offline")
                    .build();
        }
    }

    private static final String DEVICE = "BOXA1";
    private static final String ELDER = "u-1001";

    @Autowired
    private DeviceOfflineAlarmService deviceOfflineAlarmService;

    @MockitoBean
    private AlarmMapper alarmMapper;
    @MockitoBean
    private DeviceMapper deviceMapper;
    @MockitoBean
    private DeviceOfflineAlarmProperties deviceOfflineAlarmProperties;
    @MockitoBean
    private WsPushService wsPushService;

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(Device.class, Alarm.class);

        when(deviceOfflineAlarmProperties.isEnabled()).thenReturn(true);
        when(deviceOfflineAlarmProperties.getDedupWindowMinutes()).thenReturn(30L);
        when(alarmMapper.nextAlarmId()).thenReturn("a-3001");
    }

    private Device main() {
        Device d = new Device();
        d.setDeviceId(DEVICE);
        d.setDeviceType(DeviceType.MAIN);
        d.setElderId(ELDER);
        return d;
    }

    @Test
    @DisplayName("集成链路：MAIN 离线 → DEVICE_OFFLINE(WARN) 告警落库 + WS 推送")
    void mainOfflineRaisesAlarmAndPushes() {
        deviceOfflineAlarmService.raise(main());

        ArgumentCaptor<Alarm> cap = ArgumentCaptor.forClass(Alarm.class);
        verify(alarmMapper).insert(cap.capture());
        Alarm alarm = cap.getValue();
        org.assertj.core.api.Assertions.assertThat(alarm.getType()).isEqualTo("DEVICE_OFFLINE");
        org.assertj.core.api.Assertions.assertThat(alarm.getLevel()).isEqualTo("WARN");
        org.assertj.core.api.Assertions.assertThat(alarm.getElderId()).isEqualTo(ELDER);
        org.assertj.core.api.Assertions.assertThat(alarm.getDedupKey()).startsWith("DEVICE_OFFLINE:" + DEVICE + ":");

        verify(wsPushService).publish(org.mockito.ArgumentMatchers.eq(WsTopic.elder(ELDER)),
                org.mockito.ArgumentMatchers.eq(WsFrameType.ALARM), any(AlarmPayload.class));
    }

    @Test
    @DisplayName("集成链路：传感器子设备离线 → 无告警、无推送")
    void sensorOfflineNoAlarm() {
        Device sensor = main();
        sensor.setDeviceType(DeviceType.TEMP_HUMI);

        deviceOfflineAlarmService.raise(sensor);

        verify(alarmMapper, never()).insert(any(Alarm.class));
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("集成链路：无归属老人 → 无告警、无推送")
    void noElderNoAlarm() {
        Device orphan = main();
        orphan.setElderId(null);

        deviceOfflineAlarmService.raise(orphan);

        verify(alarmMapper, never()).insert(any(Alarm.class));
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("集成链路：enabled=false → 不告警、不推 WS")
    void disabledNoAlarm() {
        when(deviceOfflineAlarmProperties.isEnabled()).thenReturn(false);

        deviceOfflineAlarmService.raise(main());

        verify(alarmMapper, never()).insert(any(Alarm.class));
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("集成链路：去重命中（唯一索引冲突）→ 不推 WS，不抛异常")
    void duplicateKeySkipsWs() {
        when(alarmMapper.insert(any(Alarm.class))).thenThrow(new DuplicateKeyException("dup"));

        org.assertj.core.api.Assertions.assertThatCode(() -> deviceOfflineAlarmService.raise(main()))
                .doesNotThrowAnyException();
        verify(wsPushService, never()).publish(any(), any(), any());
    }
}
