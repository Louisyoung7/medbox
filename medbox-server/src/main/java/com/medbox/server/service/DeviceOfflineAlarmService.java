package com.medbox.server.service;

import com.medbox.server.config.DeviceOfflineAlarmProperties;
import com.medbox.server.domain.Alarm;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.mapper.AlarmMapper;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import com.medbox.server.ws.WsTopic;
import com.medbox.server.ws.payload.AlarmPayload;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 主控离线 {@code DEVICE_OFFLINE} 告警生成（feat/alarm-device-offline，见 02 第 8 章 / 06 §2.9）。
 *
 * <p>被 {@link DeviceOfflineAlarmListener}（设备状态跃变监听者）在 MAIN 设备 OFFLINE 跃变时调用；
 * 也由 {@link DeviceOfflineScanService} 的定时扫描经同一监听者汇入。本类只负责"落告警 + 推 WS"，
 * 不写 {@code device_event}（那是 {@code DeviceStatusEventBridge} 的活）。
 *
 * <p>照搬 {@link DeviceFaultService} 范式：构建 {@code Alarm}（{@code type=DEVICE_OFFLINE}/{@code level=WARN}）
 * → {@code AlarmMapper.nextAlarmId()} → 捕获 {@code DuplicateKeyException} 静默去重（唯一索引 {@code uk_alarm_dedup_key}）
 * → {@code ObjectProvider<WsPushService>.publish(ALARM)}。{@code enabled=false} 只跳告警、不影响事件流水与状态推送。
 *
 * <p>全程兜异常：监听者跑在 MQTT 线程，单设备失败只记日志，不外溢到 Paho 回调线程。
 */
@Service
public class DeviceOfflineAlarmService {

    private static final Logger log = LoggerFactory.getLogger(DeviceOfflineAlarmService.class);

    static final String TYPE_DEVICE_OFFLINE = "DEVICE_OFFLINE";
    static final String LEVEL_WARN = "WARN";

    private final AlarmMapper alarmMapper;
    private final DeviceOfflineAlarmProperties props;
    private final ObjectProvider<WsPushService> wsPushService;

    public DeviceOfflineAlarmService(AlarmMapper alarmMapper, DeviceOfflineAlarmProperties props,
                                     ObjectProvider<WsPushService> wsPushService) {
        this.alarmMapper = alarmMapper;
        this.props = props;
        this.wsPushService = wsPushService;
    }

    /**
     * 主控离线告警生成入口（监听者调用）。
     *
     * @param device 发生 OFFLINE 跃变的主控（监听者已在 newState==OFFLINE 后才调用；此处不再判状态）
     */
    public void raise(Device device) {
        try {
            doRaise(device);
        } catch (RuntimeException ex) {
            log.warn("DEVICE_OFFLINE 告警生成失败 deviceId={}：{}", device.getDeviceId(), ex.getMessage());
        }
    }

    private void doRaise(Device device) {
        if (device.getDeviceType() != DeviceType.MAIN) {
            // 传感器子设备离线不生成告警（文档 06 的 2.3），由上报侧只更新 online_state
            return;
        }
        String elderId = device.getElderId();
        if (elderId == null || elderId.isBlank()) {
            log.debug("DEVICE_OFFLINE 告警跳过：设备无归属老人 deviceId={}", device.getDeviceId());
            return;
        }
        if (!props.isEnabled()) {
            log.debug("DEVICE_OFFLINE 告警已关闭（medbox.alarm.device-offline.enabled=false）deviceId={}",
                    device.getDeviceId());
            return;
        }

        String message = "药箱离线：" + device.getDeviceId();
        String dedupKey = buildDedupKey(device.getDeviceId());

        Alarm alarm = new Alarm();
        alarm.setAlarmId(alarmMapper.nextAlarmId());
        alarm.setElderId(elderId);
        alarm.setDeviceId(device.getDeviceId());
        alarm.setPlanId(null);
        alarm.setMedicineId(null);
        alarm.setType(TYPE_DEVICE_OFFLINE);
        alarm.setLevel(LEVEL_WARN);
        alarm.setMessage(message);
        alarm.setReadState(false);
        alarm.setHandleState("OPEN");
        alarm.setDedupKey(dedupKey);
        alarm.setCreatedAt(OffsetDateTime.now());

        try {
            alarmMapper.insert(alarm);
        } catch (DuplicateKeyException ex) {
            // 去重窗口内同设备已告警过，跳过，不重复落库、不重复推 WS
            log.debug("DEVICE_OFFLINE 告警去重命中：跳过 {}", dedupKey);
            return;
        }
        pushWs(alarm, elderId);
    }

    /** 去重键：设备 + 按窗口截断的 bucket 起始瞬时（与 DEVICE_FAULT 同范式）。 */
    private String buildDedupKey(String deviceId) {
        long windowMin = props.getDedupWindowMinutes();
        Instant now = Instant.now();
        long bucketIndex = (now.getEpochSecond() / 60L) / windowMin;
        Instant bucketStart = Instant.ofEpochSecond(bucketIndex * windowMin * 60L);
        return "DEVICE_OFFLINE:" + deviceId + ":" + bucketStart;
    }

    private void pushWs(Alarm alarm, String elderId) {
        WsPushService pusher = wsPushService.getIfAvailable();
        if (pusher == null) {
            log.debug("WS 未启用，跳过 DEVICE_OFFLINE 告警推送 alarmId={}", alarm.getAlarmId());
            return;
        }
        try {
            pusher.publish(WsTopic.elder(elderId), WsFrameType.ALARM,
                    new AlarmPayload(alarm.getAlarmId(), alarm.getType(), alarm.getLevel(),
                            alarm.getMessage(), elderId, List.of()));
        } catch (RuntimeException ex) {
            log.warn("DEVICE_OFFLINE 告警 WS 推送失败 alarmId={}:{}", alarm.getAlarmId(), ex.getMessage());
        }
    }
}
