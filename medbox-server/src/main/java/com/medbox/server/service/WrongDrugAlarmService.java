package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.medbox.server.config.WrongDrugProperties;
import com.medbox.server.domain.Alarm;
import com.medbox.server.domain.MedPlanItem;
import com.medbox.server.domain.MedRecord;
import com.medbox.server.domain.Medicine;
import com.medbox.server.mapper.AlarmMapper;
import com.medbox.server.mapper.CaptureMapper;
import com.medbox.server.mapper.MedPlanItemMapper;
import com.medbox.server.mapper.MedRecordMapper;
import com.medbox.server.mapper.MedicineMapper;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import com.medbox.server.ws.WsTopic;
import com.medbox.server.ws.payload.AlarmPayload;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 错服判定与 WRONG_DRUG 告警（feat/wrong-drug-alarm，07 清单 P1）。
 *
 * <p><b>触发</b>：消费 {@code dispense-consume} 在事务提交后派发的 {@link DispenseRecordedEvent}
 * （{@code @TransactionalEventListener(AFTER_COMMIT)}），不自行扫库、不重复消费 MQTT 上报。
 *
 * <p><b>判定口径（后端权威，用户已确认）</b>：按事件携带的 {@code planItemId} 查出计划药品，
 * 与事件上报的 {@code medicineId}（实际拿的药）比对，不一致即判错服——不直接信任设备的 {@code wrongDrug} 标志
 * （与 {@code onTime} 的"后端权威"一致）。
 *
 * <p><b>处理流程（规则表沉淀在文档 06 §2.9）</b>：
 * <ol>
 *   <li>{@code enabled=false} 整体跳过；</li>
 *   <li>{@code confidence} 存在且低于阈值 → <b>不判错服</b>，仅把关联抓拍 {@code capture.reason}
 *       标记为 LOW_CONFIDENCE 交人工复核（无 {@code captureId} 则仅跳过）；</li>
 *   <li>实际药为空 / 计划药缺失 → 无法判定，不告警、不标记、不回写；</li>
 *   <li>实际药 == 计划药 → 回写 {@code med_record.wrong_drug=false}，不告警；</li>
 *   <li>实际药 != 计划药 → 回写 {@code wrong_drug=true} + 生成 WRONG_DRUG 告警（CRITICAL）
 *       + 经 {@link WsPushService} 唯一出口推 ALARM 帧；</li>
 *   <li>告警 {@code dedup_key = WRONG_DRUG:{recordId}} 命中唯一索引冲突 → 跳过、不重复推送。</li>
 * </ol>
 *
 * <p><b>回写</b>：判定后回写 {@code med_record.wrong_drug}，使记录字段与告警一致（用户已确认）；
 * 监护人后续仍可经 {@code feat/record-core} 的 confirm 端点手动纠正。
 *
 * <p><b>可靠性</b>：整流程兜异常，单条记录判定失败只记日志、不阻断其余；去重靠 DB 唯一索引；
 * WS 走 {@link ObjectProvider} 注入，{@code medbox.ws.enabled=false} 时取不到实例则静默跳过。
 */
@Component
public class WrongDrugAlarmService {

    private static final Logger log = LoggerFactory.getLogger(WrongDrugAlarmService.class);

    static final String TYPE_WRONG_DRUG = "WRONG_DRUG";
    static final String LEVEL_CRITICAL = "CRITICAL";
    static final String REASON_LOW_CONFIDENCE = "LOW_CONFIDENCE";

    private final MedPlanItemMapper medPlanItemMapper;
    private final MedRecordMapper medRecordMapper;
    private final MedicineMapper medicineMapper;
    private final AlarmMapper alarmMapper;
    private final CaptureMapper captureMapper;
    private final WrongDrugProperties props;
    private final ObjectProvider<WsPushService> wsPushService;

    public WrongDrugAlarmService(MedPlanItemMapper medPlanItemMapper, MedRecordMapper medRecordMapper,
                                 MedicineMapper medicineMapper, AlarmMapper alarmMapper,
                                 CaptureMapper captureMapper, WrongDrugProperties props,
                                 ObjectProvider<WsPushService> wsPushService) {
        this.medPlanItemMapper = medPlanItemMapper;
        this.medRecordMapper = medRecordMapper;
        this.medicineMapper = medicineMapper;
        this.alarmMapper = alarmMapper;
        this.captureMapper = captureMapper;
        this.props = props;
        this.wsPushService = wsPushService;
    }

    /** 事件入口：事务提交后触发；关闭开关或单条失败都不影响其余记录。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDispenseRecorded(DispenseRecordedEvent event) {
        if (!props.isEnabled()) {
            return;
        }
        try {
            handle(event);
        } catch (RuntimeException ex) {
            // 旁路判定：单条记录出错不应阻断主链路（consume 已提交），只记日志
            log.warn("错服判定失败 recordId={}：{}", event.getRecordId(), ex.getMessage());
        }
    }

    private void handle(DispenseRecordedEvent event) {
        // 规则 2：低置信度 → 只标记抓拍，不判错服
        Double confidence = event.getConfidence();
        if (confidence != null && confidence < props.getConfidenceThreshold()) {
            if (StringUtils.isNotBlank(event.getCaptureId())) {
                int affected = captureMapper.markLowConfidence(event.getCaptureId(), REASON_LOW_CONFIDENCE);
                log.info("错服判定：低置信度标记 LOW_CONFIDENCE recordId={} captureId={} confidence={} affected={}",
                        event.getRecordId(), event.getCaptureId(), confidence, affected);
            } else {
                log.debug("错服判定：低置信度但无 captureId，仅跳过 recordId={} confidence={}",
                        event.getRecordId(), confidence);
            }
            return;
        }

        // 规则 4（前半）：实际药品为空，无法判定
        String actualMedicine = event.getMedicineId();
        if (StringUtils.isBlank(actualMedicine)) {
            log.debug("错服判定：实际药品为空，跳过 recordId={}", event.getRecordId());
            return;
        }

        // 规则 3：查计划药品
        MedPlanItem planned = medPlanItemMapper.selectOne(
                new LambdaQueryWrapper<MedPlanItem>().eq(MedPlanItem::getItemId, event.getPlanItemId()));

        // 规则 4（后半）：计划药缺失，无法判定
        if (planned == null || StringUtils.isBlank(planned.getMedicineId())) {
            log.debug("错服判定：计划药缺失，跳过 recordId={} planItemId={}",
                    event.getRecordId(), event.getPlanItemId());
            return;
        }

        boolean wrong = !actualMedicine.equals(planned.getMedicineId());

        // 规则 5/6：回写 med_record.wrong_drug（使记录与告警一致）
        medRecordMapper.update(null, new LambdaUpdateWrapper<MedRecord>()
                .eq(MedRecord::getRecordId, event.getRecordId())
                .set(MedRecord::getWrongDrug, wrong));

        if (!wrong) {
            // 规则 5：正确服药，不告警
            return;
        }

        // 规则 6：生成 WRONG_DRUG 告警（CRITICAL）
        Alarm alarm = new Alarm();
        alarm.setAlarmId(alarmMapper.nextAlarmId());
        alarm.setElderId(event.getElderId());
        alarm.setPlanId(event.getPlanId());
        alarm.setDeviceId(event.getDeviceId());
        alarm.setMedicineId(actualMedicine);
        alarm.setType(TYPE_WRONG_DRUG);
        alarm.setLevel(LEVEL_CRITICAL);
        alarm.setMessage(buildMessage(planned.getMedicineId(), actualMedicine));
        alarm.setReadState(false);
        alarm.setHandleState("OPEN");
        alarm.setDedupKey(TYPE_WRONG_DRUG + ":" + event.getRecordId());
        alarm.setCreatedAt(OffsetDateTime.now());

        try {
            alarmMapper.insert(alarm);
        } catch (DuplicateKeyException ex) {
            // 规则 7：去重命中（同一条记录重复处理），跳过，不重复推送
            log.debug("错服告警去重命中：跳过 recordId={}", event.getRecordId());
            return;
        }

        pushWs(event, alarm, actualMedicine);
    }

    private String buildMessage(String plannedMedicineId, String actualMedicineId) {
        return "错服：本应服用 " + medicineName(plannedMedicineId)
                + "，实际服用 " + medicineName(actualMedicineId);
    }

    private String medicineName(String medicineId) {
        if (medicineId == null) {
            return "未知药品";
        }
        Medicine medicine = medicineMapper.selectById(medicineId);
        return (medicine != null && StringUtils.isNotBlank(medicine.getName()))
                ? medicine.getName() : medicineId;
    }

    private void pushWs(DispenseRecordedEvent event, Alarm alarm, String actualMedicineId) {
        WsPushService pusher = wsPushService.getIfAvailable();
        if (pusher == null) {
            log.debug("WS 未启用，跳过 WRONG_DRUG 告警推送 alarmId={}", alarm.getAlarmId());
            return;
        }
        try {
            List<String> medicineIds = new ArrayList<>();
            medicineIds.add(actualMedicineId);
            pusher.publish(WsTopic.elder(event.getElderId()), WsFrameType.ALARM,
                    new AlarmPayload(alarm.getAlarmId(), alarm.getType(), alarm.getLevel(),
                            alarm.getMessage(), event.getElderId(), medicineIds));
        } catch (RuntimeException ex) {
            log.warn("WRONG_DRUG 告警 WS 推送失败 alarmId={}：{}", alarm.getAlarmId(), ex.getMessage());
        }
    }
}
