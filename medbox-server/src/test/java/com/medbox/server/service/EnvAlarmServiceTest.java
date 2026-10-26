package com.medbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.when;

import com.medbox.server.config.EnvAlarmProperties;
import com.medbox.server.domain.Alarm;
import com.medbox.server.domain.EnvSample;
import com.medbox.server.domain.EnvThreshold;
import com.medbox.server.domain.event.EnvSampleRecordedEvent;
import com.medbox.server.mapper.AlarmMapper;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import com.medbox.server.ws.WsTopic;
import com.medbox.server.ws.payload.AlarmPayload;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

/**
 * {@link EnvAlarmService} 纯单测（不走 Spring）：阈值解析 / 各指标上下限 / 缺省 / 去重 / 边界全覆盖。
 * 推送出口与 Mapper 全部打桩。
 */
class EnvAlarmServiceTest {

    private final EnvThresholdResolver thresholdResolver = mock(EnvThresholdResolver.class);
    private final AlarmMapper alarmMapper = mock(AlarmMapper.class);
    private final EnvAlarmProperties envAlarmProperties = mock(EnvAlarmProperties.class);
    private final WsPushService wsPushService = mock(WsPushService.class);
    private final ObjectProvider<WsPushService> wsProvider = mock(ObjectProvider.class);

    private final EnvAlarmService service =
            new EnvAlarmService(thresholdResolver, alarmMapper, envAlarmProperties, wsProvider);

    private static final String ELDER = "u-1001";
    private static final String SENSOR = "BOXA1-S-TH01";

    private EnvAlarmServiceTest() {
        when(envAlarmProperties.isEnabled()).thenReturn(true);
        when(envAlarmProperties.getDedupWindowMinutes()).thenReturn(30L);
        when(alarmMapper.nextAlarmId()).thenReturn("a-2001");
        when(wsProvider.getIfAvailable()).thenReturn(wsPushService);
    }

    private EnvSample sample(BigDecimal temp, BigDecimal humi, BigDecimal lux) {
        EnvSample s = new EnvSample();
        s.setElderId(ELDER);
        s.setDeviceId(SENSOR);
        s.setTemperature(temp);
        s.setHumidity(humi);
        s.setLux(lux);
        s.setSampleTime(OffsetDateTime.parse("2026-10-26T00:05:00Z"));
        return s;
    }

    private void withThreshold(Integer tMin, Integer tMax, Integer hMin, Integer hMax, Integer lMax) {
        when(thresholdResolver.resolve(ELDER))
                .thenReturn(java.util.Optional.of(new EnvThreshold(tMin, tMax, hMin, hMax, lMax)));
    }

    @Test
    @DisplayName("未配置阈值 → 不落库不推送")
    void noThreshold() {
        when(thresholdResolver.resolve(ELDER)).thenReturn(java.util.Optional.empty());
        service.onEnvSampleRecorded(new EnvSampleRecordedEvent(sample(new BigDecimal("35"), null, null)));
        verify(alarmMapper, never()).insert(any(Alarm.class));
        verify(wsPushService, never()).publish(anyString(), any(), any());
    }

    @Test
    @DisplayName("enabled=false → 整体不判定")
    void disabled() {
        when(envAlarmProperties.isEnabled()).thenReturn(false);
        withThreshold(10, 30, 40, 70, 600);
        service.onEnvSampleRecorded(new EnvSampleRecordedEvent(sample(new BigDecimal("35"), null, null)));
        verify(alarmMapper, never()).insert(any(Alarm.class));
    }

    @Test
    @DisplayName("温度越上限 → 一条告警，message 含'超出上限'")
    void tempAbove() {
        withThreshold(10, 30, 40, 70, 600);
        service.onEnvSampleRecorded(new EnvSampleRecordedEvent(sample(new BigDecimal("35.0"), null, null)));
        ArgumentCaptor<Alarm> cap = ArgumentCaptor.forClass(Alarm.class);
        verify(alarmMapper).insert(cap.capture());
        Alarm a = cap.getValue();
        assertThat(a.getType()).isEqualTo("ENV");
        assertThat(a.getLevel()).isEqualTo("WARN");
        assertThat(a.getDeviceId()).isEqualTo(SENSOR);
        assertThat(a.getElderId()).isEqualTo(ELDER);
        assertThat(a.getMessage()).isEqualTo("温度 35℃ 超出上限 30℃");
        assertThat(a.getDedupKey()).startsWith("ENV:" + ELDER + ":" + SENSOR + ":TEMP:");
    }

    @Test
    @DisplayName("温度越下限 → message 含'低于下限'")
    void tempBelow() {
        withThreshold(10, 30, 40, 70, 600);
        service.onEnvSampleRecorded(new EnvSampleRecordedEvent(sample(new BigDecimal("5"), null, null)));
        ArgumentCaptor<Alarm> cap = ArgumentCaptor.forClass(Alarm.class);
        verify(alarmMapper).insert(cap.capture());
        assertThat(cap.getValue().getMessage()).isEqualTo("温度 5℃ 低于下限 10℃");
    }

    @Test
    @DisplayName("边界值等于上限不算越界")
    void tempAtLimitNotViolated() {
        withThreshold(10, 30, 40, 70, 600);
        service.onEnvSampleRecorded(new EnvSampleRecordedEvent(sample(new BigDecimal("30"), new BigDecimal("40"), new BigDecimal("600"))));
        verify(alarmMapper, never()).insert(any(Alarm.class));
    }

    @Test
    @DisplayName("湿度越下限、光照越上限 → 两条告警")
    void humiBelowLuxAbove() {
        withThreshold(10, 30, 40, 70, 600);
        service.onEnvSampleRecorded(new EnvSampleRecordedEvent(sample(null, new BigDecimal("30"), new BigDecimal("800"))));
        ArgumentCaptor<Alarm> cap = ArgumentCaptor.forClass(Alarm.class);
        verify(alarmMapper, times(2)).insert(cap.capture());
        List<Alarm> all = cap.getAllValues();
        assertThat(all).anyMatch(a -> a.getMessage().equals("湿度 30% 低于下限 40%"));
        assertThat(all).anyMatch(a -> a.getMessage().equals("光照 800lux 超出上限 600lux"));
    }

    @Test
    @DisplayName("某指标下限为 null 只判上限；值为 null 该指标跳过")
    void nullBoundsAndNullValue() {
        // tempMin null：温度 5 不应判下限；humidity 全 null：湿度整项跳过；lux null：跳过
        withThreshold(null, 30, null, null, null);
        service.onEnvSampleRecorded(new EnvSampleRecordedEvent(sample(new BigDecimal("5"), null, null)));
        verify(alarmMapper, never()).insert(any(Alarm.class));
    }

    @Test
    @DisplayName("温度/湿度/光照同时越界 → 三条指标级告警，且均经 WS 推送（medicineIds 为空）")
    void allThreeMetricsRaisedAndPushed() {
        withThreshold(10, 30, 40, 70, 600);
        service.onEnvSampleRecorded(new EnvSampleRecordedEvent(
                sample(new BigDecimal("35"), new BigDecimal("30"), new BigDecimal("800"))));
        ArgumentCaptor<Alarm> cap = ArgumentCaptor.forClass(Alarm.class);
        verify(alarmMapper, times(3)).insert(cap.capture());
        assertThat(cap.getAllValues()).hasSize(3)
                .extracting(Alarm::getDedupKey)
                .allMatch(k -> k.startsWith("ENV:" + ELDER + ":" + SENSOR + ":"))
                .contains("ENV:" + ELDER + ":" + SENSOR + ":TEMP:2026-10-26T00:00:00Z",
                          "ENV:" + ELDER + ":" + SENSOR + ":HUMI:2026-10-26T00:00:00Z",
                          "ENV:" + ELDER + ":" + SENSOR + ":LUX:2026-10-26T00:00:00Z");
        verify(wsPushService, times(3)).publish(eq(WsTopic.elder(ELDER)), eq(WsFrameType.ALARM), any(AlarmPayload.class));
        ArgumentCaptor<AlarmPayload> payloadCap = ArgumentCaptor.forClass(AlarmPayload.class);
        verify(wsPushService, atLeastOnce()).publish(anyString(), any(), payloadCap.capture());
        assertThat(payloadCap.getValue().medicineIds()).isEmpty();
    }

    @Test
    @DisplayName("去重：落库命中唯一索引冲突被静默吞掉，不抛异常、不二次推送")
    void dedupOnDuplicateKey() {
        withThreshold(10, 30, 40, 70, 600);
        when(alarmMapper.insert(any(Alarm.class))).thenThrow(new DuplicateKeyException("dup"));
        service.onEnvSampleRecorded(new EnvSampleRecordedEvent(sample(new BigDecimal("35"), null, null)));
        // 不抛异常、不推送
        verify(wsPushService, never()).publish(anyString(), any(), any());
    }

    @Test
    @DisplayName("样本无 elderId → 跳过")
    void nullElderId() {
        EnvSample s = sample(new BigDecimal("35"), null, null);
        s.setElderId(null);
        service.onEnvSampleRecorded(new EnvSampleRecordedEvent(s));
        verify(alarmMapper, never()).insert(any(Alarm.class));
    }
}
