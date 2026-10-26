package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.config.DeviceFaultAlarmProperties;
import com.medbox.server.domain.Alarm;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.DeviceEventType;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.mapper.AlarmMapper;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mqtt.payload.DeviceErrorPayload;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import com.medbox.server.ws.WsTopic;
import com.medbox.server.ws.payload.AlarmPayload;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 设备侧异常消费与 DEVICE_FAULT 告警（feat/device-fault，07 清单 P1，见 03 第 3 章 / 02 第 8 章 / 06 §2.9）。
 *
 * <p><b>两步走</b>：① 写 {@code device_event} 流水（{@code FAULT}，{@code detail} = 故障码字符串——
 * 前端硬约定，小程序 {@code toEventView} 直接把 {@code detail} 当 faultCode 解析），流水全量留痕；
 * ② 生成 {@code DEVICE_FAULT} 告警（{@code WARN}）并推 WS。{@code enabled=false} 只跳过 ②，不影响 ①。
 *
 * <p><b>去重</b>：{@code dedup_key = DEVICE_FAULT:{deviceId}:{type}:{bucket}}，bucket 由 payload 的
 * {@code ts} 按 {@code dedup-window-minutes} 截断；命中 {@code uk_alarm_dedup_key} → 静默跳过告警与推送
 * （事件流水已记）。msgId 级幂等由 {@code MqttInboundGateway} 兜住，本类不重复做。
 *
 * <p><b>错服不走这里</b>（{@code dispense.wrongDrug} 通道，{@code WrongDrugAlarmService}）。
 * 全程兜异常：旁路消费，单条失败只记日志，不外溢到 Paho 回调线程。
 */
@Service
public class DeviceFaultService {

    private static final Logger log = LoggerFactory.getLogger(DeviceFaultService.class);

    static final String TYPE_DEVICE_FAULT = "DEVICE_FAULT";
    static final String LEVEL_WARN = "WARN";

    /** 故障码 → 中文标签（与小程序 {@code utils/device.js} 的 FAULT_LABEL 一致）。 */
    static final Map<String, String> FAULT_LABEL = Map.of(
            "DOOR_TIMEOUT", "门超时未关",
            "CAMERA_FAILED", "识别失败",
            "HW_FAULT", "硬件故障");

    private final DeviceMapper deviceMapper;
    private final DeviceEventService deviceEventService;
    private final AlarmMapper alarmMapper;
    private final DeviceFaultAlarmProperties props;
    private final ObjectProvider<WsPushService> wsPushService;

    public DeviceFaultService(DeviceMapper deviceMapper, DeviceEventService deviceEventService,
                              AlarmMapper alarmMapper, DeviceFaultAlarmProperties props,
                              ObjectProvider<WsPushService> wsPushService) {
        this.deviceMapper = deviceMapper;
        this.deviceEventService = deviceEventService;
        this.alarmMapper = alarmMapper;
        this.props = props;
        this.wsPushService = wsPushService;
    }

    /** 消费一条 {@code up/event/error}（已由网关按 msgId 去重）。 */
    public void ingest(String mainDeviceId, DeviceErrorPayload payload) {
        String faultType = payload.getType();
        if (faultType == null || !FAULT_LABEL.containsKey(faultType)) {
            log.warn("up/event/error 非法 type，已丢弃 deviceId={} type={}", mainDeviceId, faultType);
            return;
        }
        Device main = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                .eq(Device::getDeviceId, mainDeviceId));
        if (main == null || main.getDeviceType() != DeviceType.MAIN) {
            // 未登记设备也能连 EMQX（本期匿名连接），是常态，不刷 warn
            log.debug("up/event/error 的设备未登记或非主控，忽略 deviceId={}", mainDeviceId);
            return;
        }

        // ① 事件流水：detail = 故障码（前端硬约定）；record 内部已兜异常，不会因流水失败丢告警
        deviceEventService.record(mainDeviceId, DeviceEventType.FAULT, faultType);

        // ② 告警 + WS（开关只控制这一步）
        if (!props.isEnabled()) {
            return;
        }
        raiseAlarm(main, faultType, payload.getDetail(), payload.getTs());
    }

    private void raiseAlarm(Device main, String faultType, String deviceDetail, Long ts) {
        String elderId = main.getElderId();
        if (elderId == null || elderId.isBlank()) {
            log.debug("DEVICE_FAULT 告警跳过：设备无归属老人 deviceId={}", main.getDeviceId());
            return;
        }
        String label = FAULT_LABEL.get(faultType);
        String message = (deviceDetail == null || deviceDetail.isBlank())
                ? label : label + "：" + deviceDetail;
        String dedupKey = buildDedupKey(main.getDeviceId(), faultType, ts);

        Alarm alarm = new Alarm();
        alarm.setAlarmId(alarmMapper.nextAlarmId());
        alarm.setElderId(elderId);
        alarm.setDeviceId(main.getDeviceId());
        alarm.setPlanId(null);
        alarm.setMedicineId(null);
        alarm.setType(TYPE_DEVICE_FAULT);
        alarm.setLevel(LEVEL_WARN);
        alarm.setMessage(message);
        alarm.setReadState(false);
        alarm.setHandleState("OPEN");
        alarm.setDedupKey(dedupKey);
        alarm.setCreatedAt(OffsetDateTime.now());

        try {
            alarmMapper.insert(alarm);
        } catch (DuplicateKeyException ex) {
            // 去重窗口内同设备同故障码已告警过，跳过，不重复落库、不重复推
            log.debug("DEVICE_FAULT 告警去重命中：跳过 {}", dedupKey);
            return;
        }
        pushWs(alarm, elderId);
    }

    /** 去重键：设备 + 故障码 + 按窗口截断的 bucket 起始瞬时（与 ENV 同范式；ts 为空回落 now）。 */
    private String buildDedupKey(String deviceId, String faultType, Long ts) {
        long windowMin = props.getDedupWindowMinutes();
        Instant at = ts == null ? Instant.now() : Instant.ofEpochMilli(ts);
        long bucketIndex = (at.getEpochSecond() / 60L) / windowMin;
        Instant bucketStart = Instant.ofEpochSecond(bucketIndex * windowMin * 60L);
        return "DEVICE_FAULT:" + deviceId + ":" + faultType + ":" + bucketStart;
    }

    private void pushWs(Alarm alarm, String elderId) {
        WsPushService pusher = wsPushService.getIfAvailable();
        if (pusher == null) {
            log.debug("WS 未启用，跳过 DEVICE_FAULT 告警推送 alarmId={}", alarm.getAlarmId());
            return;
        }
        try {
            pusher.publish(WsTopic.elder(elderId), WsFrameType.ALARM,
                    new AlarmPayload(alarm.getAlarmId(), alarm.getType(), alarm.getLevel(),
                            alarm.getMessage(), elderId, List.of()));
        } catch (RuntimeException ex) {
            log.warn("DEVICE_FAULT 告警 WS 推送失败 alarmId={}:{}", alarm.getAlarmId(), ex.getMessage());
        }
    }
}