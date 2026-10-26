package com.medbox.server.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.config.EnvAlarmProperties;
import com.medbox.server.domain.Alarm;
import com.medbox.server.domain.AlarmSetting;
import com.medbox.server.domain.EnvSample;
import com.medbox.server.domain.EnvThreshold;
import com.medbox.server.domain.event.EnvSampleRecordedEvent;
import com.medbox.server.mapper.AlarmMapper;
import com.medbox.server.mapper.AlarmSettingMapper;
import com.medbox.server.support.TableInfoWarmup;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import com.medbox.server.ws.WsTopic;
import com.medbox.server.ws.payload.AlarmPayload;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@link EnvAlarmService} 集成测试：真实 Spring 链路（Bean 装配 / 事件监听 / 配置属性），
 * 仅把 Mapper 与 WS 出口换成替身，不连 PostgreSQL（H2 替换 DataSource）。重点验：
 * ① 真实 {@link Alarm} 实体经 MyBatis-Plus 落库路径（确保表名 / 字段映射无误）；
 * ② 落库后经 {@link WsPushService} 唯一出口推送 ALARM 帧（medicineIds 为空）。
 */
@SpringBootTest
class EnvAlarmIntegrationTest {

    @TestConfiguration
    static class H2DataSourceConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-test-env-alarm")
                    .build();
        }
    }

    private static final String ELDER = "u-1001";
    private static final String SENSOR = "BOXA1-S-TH01";

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @MockitoBean
    private AlarmSettingMapper alarmSettingMapper;
    @MockitoBean
    private AlarmMapper alarmMapper;
    @MockitoBean
    private EnvAlarmProperties envAlarmProperties;
    @MockitoBean
    private WsPushService wsPushService;

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(Alarm.class, EnvSample.class, AlarmSetting.class);

        when(envAlarmProperties.isEnabled()).thenReturn(true);
        when(envAlarmProperties.getDedupWindowMinutes()).thenReturn(30L);
        when(alarmMapper.nextAlarmId()).thenReturn("a-2001");

        AlarmSetting setting = new AlarmSetting();
        setting.setEnvThreshold(new EnvThreshold(10, 30, 40, 70, 600));
        when(alarmSettingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);
    }

    private EnvSample sample() {
        EnvSample s = new EnvSample();
        s.setElderId(ELDER);
        s.setDeviceId(SENSOR);
        s.setTemperature(new BigDecimal("35"));   // 超上限 30
        s.setHumidity(new BigDecimal("30"));       // 低于下限 40
        s.setLux(new BigDecimal("800"));           // 超上限 600
        s.setSampleTime(OffsetDateTime.parse("2026-10-26T00:05:00Z"));
        return s;
    }

    @Test
    @DisplayName("集成链路：三指标越界各自落库 ENV 告警并经 WS 推送 ALARM 帧")
    void raisesEnvAlarmsAndPushes() {
        eventPublisher.publishEvent(new EnvSampleRecordedEvent(sample()));

        verify(alarmMapper, times(3)).insert(any(Alarm.class));
        verify(wsPushService, times(3)).publish(eq(WsTopic.elder(ELDER)), eq(WsFrameType.ALARM), any(AlarmPayload.class));

        org.mockito.ArgumentCaptor<AlarmPayload> cap = org.mockito.ArgumentCaptor.forClass(AlarmPayload.class);
        verify(wsPushService, atLeastOnce()).publish(anyString(), any(), cap.capture());
        cap.getAllValues().forEach(p -> org.assertj.core.api.Assertions.assertThat(p.medicineIds()).isEmpty());
    }

    @Test
    @DisplayName("集成链路：阈值内不落库不推送")
    void noAlarmWhenWithinThreshold() {
        EnvSample s = sample();
        s.setTemperature(new BigDecimal("20"));
        s.setHumidity(new BigDecimal("50"));
        s.setLux(new BigDecimal("300"));
        eventPublisher.publishEvent(new EnvSampleRecordedEvent(s));

        verify(alarmMapper, times(0)).insert(any(Alarm.class));
        verify(wsPushService, times(0)).publish(anyString(), any(), any());
    }
}
