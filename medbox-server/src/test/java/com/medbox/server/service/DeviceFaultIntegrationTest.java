package com.medbox.server.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.config.DeviceFaultAlarmProperties;
import com.medbox.server.domain.Alarm;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.DeviceEvent;
import com.medbox.server.domain.enums.DeviceEventType;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.mapper.AlarmMapper;
import com.medbox.server.mapper.DeviceEventMapper;
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
 * {@link DeviceFaultService} 集成测试：真实 Spring 链路（Bean 装配 / {@code DeviceEventService}
 * 真实落库路径），仅把 Mapper 与 WS 出口换成替身，不连 PostgreSQL（H2 替换 DataSource）。重点验：
 * ① 合法故障 → {@code device_event}(FAULT, detail=故障码) 与 {@code DEVICE_FAULT}(WARN) 告警双写 + WS；
 * ② 开关只控告警不影响流水；③ 去重命中不推 WS。
 */
@SpringBootTest
class DeviceFaultIntegrationTest {

    @TestConfiguration
    static class H2DataSourceConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-test-device-fault")
                    .build();
        }
    }

    private static final String DEVICE = "BOXA1";
    private static final String ELDER = "u-1001";

    @Autowired
    private DeviceFaultService deviceFaultService;

    @MockitoBean
    private DeviceMapper deviceMapper;
    @MockitoBean
    private DeviceEventMapper deviceEventMapper;
    @MockitoBean
    private AlarmMapper alarmMapper;
    @MockitoBean
    private DeviceFaultAlarmProperties deviceFaultAlarmProperties;
    @MockitoBean
    private WsPushService wsPushService;

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(Device.class, DeviceEvent.class, Alarm.class);

        when(deviceFaultAlarmProperties.isEnabled()).thenReturn(true);
        when(deviceFaultAlarmProperties.getDedupWindowMinutes()).thenReturn(30L);
        when(alarmMapper.nextAlarmId()).thenReturn("a-3001");

        Device main = new Device();
        main.setDeviceId(DEVICE);
        main.setDeviceType(DeviceType.MAIN);
        main.setElderId(ELDER);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(main);
    }

    private void ingest() {
        DeviceErrorPayloadHolder.ingest(deviceFaultService, DEVICE, "DOOR_TIMEOUT", "仓门开启超过 60s 未关闭");
    }

    @Test
    @DisplayName("集成链路：FAULT 流水（detail=故障码）+ DEVICE_FAULT 告警落库 + WS 推送")
    void recordsEventAndRaisesAlarm() {
        ingest();

        // 流水：真实 DeviceEventService → 被 @MockitoBean 的 DeviceEventMapper 接住
        ArgumentCaptor<DeviceEvent> eventCap = ArgumentCaptor.forClass(DeviceEvent.class);
        verify(deviceEventMapper).insert(eventCap.capture());
        DeviceEvent event = eventCap.getValue();
        org.assertj.core.api.Assertions.assertThat(event.getDeviceId()).isEqualTo(DEVICE);
        org.assertj.core.api.Assertions.assertThat(event.getEventType()).isEqualTo(DeviceEventType.FAULT);
        org.assertj.core.api.Assertions.assertThat(event.getDetail()).isEqualTo("DOOR_TIMEOUT");

        // 告警
        ArgumentCaptor<Alarm> alarmCap = ArgumentCaptor.forClass(Alarm.class);
        verify(alarmMapper).insert(alarmCap.capture());
        Alarm alarm = alarmCap.getValue();
        org.assertj.core.api.Assertions.assertThat(alarm.getType()).isEqualTo("DEVICE_FAULT");
        org.assertj.core.api.Assertions.assertThat(alarm.getLevel()).isEqualTo("WARN");
        org.assertj.core.api.Assertions.assertThat(alarm.getElderId()).isEqualTo(ELDER);
        org.assertj.core.api.Assertions.assertThat(alarm.getMessage()).isEqualTo("门超时未关：仓门开启超过 60s 未关闭");
        org.assertj.core.api.Assertions.assertThat(alarm.getDedupKey())
                .startsWith("DEVICE_FAULT:" + DEVICE + ":DOOR_TIMEOUT:");

        // WS
        verify(wsPushService).publish(org.mockito.ArgumentMatchers.eq(WsTopic.elder(ELDER)),
                org.mockito.ArgumentMatchers.eq(WsFrameType.ALARM), any(AlarmPayload.class));
    }

    @Test
    @DisplayName("集成链路：设备未登记 → 无流水、无告警、无推送")
    void unregisteredDeviceNoop() {
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        ingest();

        verify(deviceEventMapper, never()).insert(any(DeviceEvent.class));
        verify(alarmMapper, never()).insert(any(Alarm.class));
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("集成链路：enabled=false → 流水照记，告警与 WS 全免")
    void disabledStillRecordsEvent() {
        when(deviceFaultAlarmProperties.isEnabled()).thenReturn(false);

        ingest();

        verify(deviceEventMapper, times(1)).insert(any(DeviceEvent.class));
        verify(alarmMapper, never()).insert(any(Alarm.class));
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("集成链路：去重命中（唯一索引冲突）→ 不推 WS，不抛异常")
    void duplicateKeySkipsWs() {
        when(alarmMapper.insert(any(Alarm.class))).thenThrow(new DuplicateKeyException("dup"));

        org.assertj.core.api.Assertions.assertThatCode(this::ingest).doesNotThrowAnyException();
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    /** 测试辅助：构造 payload 并打服务入口（避免测试类里散落 payload 组装）。 */
    static final class DeviceErrorPayloadHolder {
        private DeviceErrorPayloadHolder() {
        }

        static void ingest(DeviceFaultService svc, String deviceId, String type, String detail) {
            com.medbox.server.mqtt.payload.DeviceErrorPayload p =
                    new com.medbox.server.mqtt.payload.DeviceErrorPayload();
            p.setMsgId(deviceId + "-000125");
            p.setTs(1759474512000L);
            p.setType(type);
            p.setDetail(detail);
            svc.ingest(deviceId, p);
        }
    }
}
