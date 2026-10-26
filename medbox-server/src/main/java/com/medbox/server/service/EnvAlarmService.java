package com.medbox.server.service;

import com.medbox.server.config.EnvAlarmProperties;
import com.medbox.server.domain.Alarm;
import com.medbox.server.domain.EnvSample;
import com.medbox.server.domain.EnvThreshold;
import com.medbox.server.domain.event.EnvSampleRecordedEvent;
import com.medbox.server.mapper.AlarmMapper;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import com.medbox.server.ws.WsTopic;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 环境超标判定与 ENV 告警（feat/env-alarm，07 清单 P1，见文档 06 的 2.9 / 01 的 5.4）。
 *
 * <p><b>触发</b>：消费 {@link EnvSampleRecordedEvent}（由 {@code TelemetryUpMessageHandler} 在遥测入库后发布），
 * 用普通 {@code @EventListener} 同步执行（ingest 无事务，不依赖 {@code AFTER_COMMIT}）。
 *
 * <p><b>阈值来源</b>：仅老人级 {@code alarm_setting.env_threshold}（温湿双向上下限、光照仅上限），
 * 经 {@link EnvThresholdResolver} 取得；行不存在 / {@code env_threshold} 为 null → 不判定；
 * 某指标上下限为 null → 该指标跳过。
 *
 * <p><b>指标级告警</b>：一条样本每个越界指标各生成 1 条 ENV 告警（{@code type=ENV}、{@code level=WARN}），
 * 独立去重；{@code device_id} 记传感器自身（与 {@code env_sample.device_id} 同源）。
 *
 * <p><b>去重</b>：{@code dedup_key = ENV:{elderId}:{sensorId}:{metric}:{bucket}}，{@code bucket} 由
 * {@code sampleTime} 按 {@code dedup-window-minutes} 截断；命中 {@code uk_alarm_dedup_key}（
 * {@code DuplicateKeyException}）→ 静默跳过，不重复落库、不重复推 WS。
 *
 * <p><b>恢复</b>：不另发恢复告警；数值回落即停止告警，下一越界在去重窗口过后可再告警。
 *
 * <p><b>推送</b>：落库成功后经 {@link WsPushService} 唯一出口推 {@code ALARM} 帧到 {@code elder:{elderId}}；
 * {@code medbox.ws.enabled=false} 或推送异常 → 静默跳过。全程兜异常，单条样本判定失败只记日志不阻断其余上行。
 *
 * <p>规则表细节沉淀在文档 06 §2.9。
 */
@Component
public class EnvAlarmService {

    private static final Logger log = LoggerFactory.getLogger(EnvAlarmService.class);

    static final String TYPE_ENV = "ENV";
    static final String LEVEL_WARN = "WARN";

    private final EnvThresholdResolver thresholdResolver;
    private final AlarmMapper alarmMapper;
    private final EnvAlarmProperties envAlarmProperties;
    private final ObjectProvider<WsPushService> wsPushService;

    public EnvAlarmService(EnvThresholdResolver thresholdResolver, AlarmMapper alarmMapper,
                           EnvAlarmProperties envAlarmProperties, ObjectProvider<WsPushService> wsPushService) {
        this.thresholdResolver = thresholdResolver;
        this.alarmMapper = alarmMapper;
        this.envAlarmProperties = envAlarmProperties;
        this.wsPushService = wsPushService;
    }

    /** 消费遥测入库事件，做环境超标判定。 */
    @EventListener
    public void onEnvSampleRecorded(EnvSampleRecordedEvent event) {
        if (!envAlarmProperties.isEnabled()) {
            return;
        }
        EnvSample sample = event.getSample();
        if (sample == null) {
            return;
        }
        String elderId = sample.getElderId();
        if (elderId == null || elderId.isBlank()) {
            log.debug("ENV 判定跳过：样本无 elderId");
            return;
        }
        EnvThreshold threshold = thresholdResolver.resolve(elderId).orElse(null);
        if (threshold == null) {
            log.debug("ENV 判定跳过：老人未配置环境阈值 elderId={}", elderId);
            return;
        }

        String sensorId = sample.getDeviceId();
        OffsetDateTime sampleTime = sample.getSampleTime() != null ? sample.getSampleTime() : OffsetDateTime.now();

        evaluate(elderId, sensorId, sampleTime, "温度", "TEMP", sample.getTemperature(),
                threshold.tempMin(), threshold.tempMax(), "℃");
        evaluate(elderId, sensorId, sampleTime, "湿度", "HUMI", sample.getHumidity(),
                threshold.humiMin(), threshold.humiMax(), "%");
        // 光照仅上限
        evaluate(elderId, sensorId, sampleTime, "光照", "LUX", sample.getLux(),
                null, threshold.luxMax(), "lux");
    }

    private void evaluate(String elderId, String sensorId, OffsetDateTime sampleTime,
                          String label, String metric, BigDecimal value,
                          Integer min, Integer max, String unit) {
        if (value == null) {
            return; // 该指标本行未上报
        }
        if (min != null && value.compareTo(BigDecimal.valueOf(min)) < 0) {
            raise(elderId, sensorId, sampleTime, label, metric, value, unit, "低于下限 " + min + unit);
        } else if (max != null && value.compareTo(BigDecimal.valueOf(max)) > 0) {
            raise(elderId, sensorId, sampleTime, label, metric, value, unit, "超出上限 " + max + unit);
        }
    }

    private void raise(String elderId, String sensorId, OffsetDateTime sampleTime,
                       String label, String metric, BigDecimal value, String unit, String reason) {
        String message = label + " " + value.stripTrailingZeros().toPlainString() + unit + " " + reason;
        String dedupKey = buildDedupKey(elderId, sensorId, metric, sampleTime);

        Alarm alarm = new Alarm();
        alarm.setAlarmId(alarmMapper.nextAlarmId());
        alarm.setElderId(elderId);
        alarm.setDeviceId(sensorId);
        alarm.setPlanId(null);
        alarm.setMedicineId(null);
        alarm.setType(TYPE_ENV);
        alarm.setLevel(LEVEL_WARN);
        alarm.setMessage(message);
        alarm.setReadState(false);
        alarm.setHandleState("OPEN");
        alarm.setDedupKey(dedupKey);
        alarm.setCreatedAt(OffsetDateTime.now());

        try {
            alarmMapper.insert(alarm);
        } catch (DuplicateKeyException ex) {
            // 去重窗口内同指标已告警过（重启 / 重发同窗口样本），跳过，不重复告警、不重复推
            log.debug("ENV 告警去重命中：跳过 {}", dedupKey);
            return;
        }

        pushWs(alarm, elderId);
    }

    /** 去重键：老人 + 传感器 + 指标 + 按窗口截断的 bucket 起始瞬时。 */
    private String buildDedupKey(String elderId, String sensorId, String metric, OffsetDateTime sampleTime) {
        long epochMin = sampleTime.toInstant().getEpochSecond() / 60L;
        long bucketIndex = epochMin / envAlarmProperties.getDedupWindowMinutes();
        Instant bucketStart = Instant.ofEpochSecond(bucketIndex * envAlarmProperties.getDedupWindowMinutes() * 60L);
        return "ENV:" + elderId + ":" + sensorId + ":" + metric + ":" + bucketStart;
    }

    private void pushWs(Alarm alarm, String elderId) {
        WsPushService pusher = wsPushService.getIfAvailable();
        if (pusher == null) {
            log.debug("WS 未启用，跳过 ENV 告警推送 alarmId={}", alarm.getAlarmId());
            return;
        }
        try {
            pusher.publish(WsTopic.elder(elderId), WsFrameType.ALARM,
                    new com.medbox.server.ws.payload.AlarmPayload(
                            alarm.getAlarmId(), alarm.getType(), alarm.getLevel(),
                            alarm.getMessage(), elderId, List.of()));
        } catch (RuntimeException ex) {
            log.warn("ENV 告警 WS 推送失败 alarmId={}：{}", alarm.getAlarmId(), ex.getMessage());
        }
    }
}
