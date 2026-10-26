package com.medbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.medbox.server.config.DeviceFaultAlarmProperties;
import com.medbox.server.domain.Alarm;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.DeviceEventType;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.mapper.AlarmMapper;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mqtt.payload.DeviceErrorPayload;
import com.medbox.server.support.TableInfoWarmup;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import com.medbox.server.ws.WsTopic;
import com.medbox.server.ws.payload.AlarmPayload;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

/**
 * {@link DeviceFaultService} 单元测试：纯 Mockito，不连库、不启 Spring。
 *
 * <p>按规则表覆盖：非法 type 丢弃；未登记 / 非主控忽略；合法链路 = 流水（detail=故障码）+
 * DEVICE_FAULT(WARN) 告警 + WS；{@code enabled=false} 只跳过告警不影响流水；无归属老人跳过告警；
 * 去重命中跳过不推 WS；WS 未启用 / 推送异常静默；去重键按窗口分桶。
 */
class DeviceFaultServiceTest {

    private static final String DEVICE = "BOXA1";
    private static final String ELDER = "u-1001";

    private final DeviceMapper deviceMapper = mock(DeviceMapper.class);
    private final DeviceEventService deviceEventService = mock(DeviceEventService.class);
    private final AlarmMapper alarmMapper = mock(AlarmMapper.class);
    private final DeviceFaultAlarmProperties props = new DeviceFaultAlarmProperties();
    @SuppressWarnings("unchecked")
    private final ObjectProvider<WsPushService> wsProvider = mock(ObjectProvider.class);
    private final WsPushService wsPushService = mock(WsPushService.class);

    private DeviceFaultService service;

    @BeforeEach
    void setUp() {
        // Lambda 条件构造器需要实体 TableInfo 才能把方法引用解析成列名
        TableInfoWarmup.init(Device.class, Alarm.class, com.medbox.server.domain.DeviceEvent.class);

        props.setEnabled(true);
        props.setDedupWindowMinutes(30L);
        when(wsProvider.getIfAvailable()).thenReturn(wsPushService);
        when(alarmMapper.nextAlarmId()).thenReturn("a-1001");
        when(deviceMapper.selectOne(any())).thenReturn(device());
        service = new DeviceFaultService(deviceMapper, deviceEventService, alarmMapper, props, wsProvider);
    }

    private Device device() {
        Device d = new Device();
        d.setDeviceId(DEVICE);
        d.setDeviceType(DeviceType.MAIN);
        d.setElderId(ELDER);
        return d;
    }

    private DeviceErrorPayload payload(String type, String detail, Long ts) {
        DeviceErrorPayload p = new DeviceErrorPayload();
        p.setMsgId(DEVICE + "-000125");
        p.setTs(ts);
        p.setType(type);
        p.setDetail(detail);
        return p;
    }

    // ------------------------------------------------------------- 规则 1：非法 type

    @Test
    @DisplayName("type 为 null：丢弃，零交互")
    void nullTypeDropped() {
        service.ingest(DEVICE, payload(null, "x", 1L));

        verifyNoInteractions(deviceMapper, deviceEventService, alarmMapper, wsPushService);
    }

    @Test
    @DisplayName("type 非法（WRONG_DRUG 不属于设备故障码）：丢弃，零交互")
    void wrongDrugTypeDropped() {
        service.ingest(DEVICE, payload("WRONG_DRUG", "x", 1L));

        verifyNoInteractions(deviceMapper, deviceEventService, alarmMapper, wsPushService);
    }

    // ------------------------------------------------------------- 规则 2：设备未登记 / 非主控

    @Test
    @DisplayName("设备未登记：查过 device 即止，无流水、无告警")
    void unregisteredDeviceIgnored() {
        when(deviceMapper.selectOne(any())).thenReturn(null);

        service.ingest(DEVICE, payload("DOOR_TIMEOUT", "x", 1L));

        verify(deviceEventService, never()).record(anyString(), any(), anyString());
        verify(alarmMapper, never()).insert(any(Alarm.class));
    }

    @Test
    @DisplayName("设备非主控（子设备上报无意义）：忽略")
    void nonMainDeviceIgnored() {
        Device sensor = device();
        sensor.setDeviceType(DeviceType.TEMP_HUMI);
        when(deviceMapper.selectOne(any())).thenReturn(sensor);

        service.ingest(DEVICE, payload("DOOR_TIMEOUT", "x", 1L));

        verify(deviceEventService, never()).record(anyString(), any(), anyString());
        verify(alarmMapper, never()).insert(any(Alarm.class));
    }

    // ------------------------------------------------------------- 规则 3/6：合法链路

    @Test
    @DisplayName("合法 DOOR_TIMEOUT：流水 detail=故障码 + 告警字段全对 + WS 推送")
    void validFaultRecordsEventAndRaisesAlarm() {
        service.ingest(DEVICE, payload("DOOR_TIMEOUT", "仓门开启超过 60s 未关闭", 1L));

        verify(deviceEventService).record(DEVICE, DeviceEventType.FAULT, "DOOR_TIMEOUT");

        ArgumentCaptor<Alarm> cap = ArgumentCaptor.forClass(Alarm.class);
        verify(alarmMapper).insert(cap.capture());
        Alarm alarm = cap.getValue();
        assertThat(alarm.getAlarmId()).isEqualTo("a-1001");
        assertThat(alarm.getElderId()).isEqualTo(ELDER);
        assertThat(alarm.getDeviceId()).isEqualTo(DEVICE);
        assertThat(alarm.getType()).isEqualTo("DEVICE_FAULT");
        assertThat(alarm.getLevel()).isEqualTo("WARN");
        assertThat(alarm.getMessage()).isEqualTo("门超时未关：仓门开启超过 60s 未关闭");
        assertThat(alarm.getReadState()).isFalse();
        assertThat(alarm.getHandleState()).isEqualTo("OPEN");
        assertThat(alarm.getDedupKey()).startsWith("DEVICE_FAULT:" + DEVICE + ":DOOR_TIMEOUT:");

        verify(wsPushService).publish(org.mockito.ArgumentMatchers.eq(WsTopic.elder(ELDER)),
                org.mockito.ArgumentMatchers.eq(WsFrameType.ALARM), any(AlarmPayload.class));
    }

    @Test
    @DisplayName("detail 为空：message 只有标签、无冒号")
    void emptyDetailKeepsLabelOnly() {
        service.ingest(DEVICE, payload("HW_FAULT", null, 1L));

        ArgumentCaptor<Alarm> cap = ArgumentCaptor.forClass(Alarm.class);
        verify(alarmMapper).insert(cap.capture());
        assertThat(cap.getValue().getMessage()).isEqualTo("硬件故障");
    }

    @Test
    @DisplayName("WS payload：medicineIds 为空 List")
    void wsPayloadHasNoMedicineIds() {
        service.ingest(DEVICE, payload("CAMERA_FAILED", "识别失败", 1L));

        ArgumentCaptor<AlarmPayload> cap = ArgumentCaptor.forClass(AlarmPayload.class);
        verify(wsPushService).publish(anyString(), any(), cap.capture());
        assertThat(cap.getValue().medicineIds()).isEmpty();
        assertThat(cap.getValue().type()).isEqualTo("DEVICE_FAULT");
        assertThat(cap.getValue().level()).isEqualTo("WARN");
    }

    // ------------------------------------------------------------- 规则 4：开关只控告警

    @Test
    @DisplayName("enabled=false：流水照记，不告警、不推 WS")
    void disabledStillRecordsEvent() {
        props.setEnabled(false);

        service.ingest(DEVICE, payload("DOOR_TIMEOUT", "x", 1L));

        verify(deviceEventService).record(DEVICE, DeviceEventType.FAULT, "DOOR_TIMEOUT");
        verify(alarmMapper, never()).insert(any(Alarm.class));
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    // ------------------------------------------------------------- 规则 5：无归属老人

    @Test
    @DisplayName("设备无归属老人：流水照记，告警跳过")
    void noElderSkipsAlarm() {
        Device orphan = device();
        orphan.setElderId(null);
        when(deviceMapper.selectOne(any())).thenReturn(orphan);

        service.ingest(DEVICE, payload("DOOR_TIMEOUT", "x", 1L));

        verify(deviceEventService).record(DEVICE, DeviceEventType.FAULT, "DOOR_TIMEOUT");
        verify(alarmMapper, never()).insert(any(Alarm.class));
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    // ------------------------------------------------------------- 规则 7：去重

    @Test
    @DisplayName("dedup_key 命中唯一索引冲突：不推 WS、不抛异常")
    void duplicateKeySkipsWs() {
        when(alarmMapper.insert(any(Alarm.class))).thenThrow(new DuplicateKeyException("dup"));

        assertThatCode(() -> service.ingest(DEVICE, payload("DOOR_TIMEOUT", "x", 1L)))
                .doesNotThrowAnyException();
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    // ------------------------------------------------------------- 规则 8：WS 容错

    @Test
    @DisplayName("WS 未启用（provider 取不到）：不推送、不抛异常")
    void wsDisabledSilent() {
        when(wsProvider.getIfAvailable()).thenReturn(null);

        assertThatCode(() -> service.ingest(DEVICE, payload("DOOR_TIMEOUT", "x", 1L)))
                .doesNotThrowAnyException();
        verify(alarmMapper).insert(any(Alarm.class));
    }

    @Test
    @DisplayName("WS 推送抛异常：只记日志，不影响告警已落库")
    void wsPublishFailureSwallowed() {
        when(wsPushService.publish(any(), any(), any())).thenThrow(new RuntimeException("ws down"));

        assertThatCode(() -> service.ingest(DEVICE, payload("DOOR_TIMEOUT", "x", 1L)))
                .doesNotThrowAnyException();
        verify(alarmMapper).insert(any(Alarm.class));
    }

    // ------------------------------------------------------------- 去重键分桶

    @Test
    @DisplayName("去重键：同 30min 桶相同，跨桶不同，ts=null 回落 now 不炸")
    void dedupKeyBucketing() {
        long t1 = Instant.parse("2026-10-26T00:05:00Z").toEpochMilli();
        long t2 = Instant.parse("2026-10-26T00:25:00Z").toEpochMilli();
        long t3 = Instant.parse("2026-10-26T00:35:00Z").toEpochMilli();

        service.ingest(DEVICE, payload("DOOR_TIMEOUT", "a", t1));
        service.ingest(DEVICE, payload("DOOR_TIMEOUT", "b", t2));
        service.ingest(DEVICE, payload("DOOR_TIMEOUT", "c", t3));
        service.ingest(DEVICE, payload("CAMERA_FAILED", "d", null));

        ArgumentCaptor<Alarm> cap = ArgumentCaptor.forClass(Alarm.class);
        verify(alarmMapper, org.mockito.Mockito.times(4)).insert(cap.capture());
        List<Alarm> alarms = cap.getAllValues();
        assertThat(alarms.get(0).getDedupKey()).isEqualTo(alarms.get(1).getDedupKey());
        assertThat(alarms.get(0).getDedupKey()).isNotEqualTo(alarms.get(2).getDedupKey());
        assertThat(alarms.get(3).getDedupKey()).startsWith("DEVICE_FAULT:" + DEVICE + ":CAMERA_FAILED:");
    }
}
