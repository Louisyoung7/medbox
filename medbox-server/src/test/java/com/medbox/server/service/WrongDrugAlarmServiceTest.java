package com.medbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.medbox.server.config.WrongDrugProperties;
import com.medbox.server.domain.Alarm;
import com.medbox.server.domain.MedPlanItem;
import com.medbox.server.domain.Medicine;
import com.medbox.server.domain.MedRecord;
import com.medbox.server.mapper.AlarmMapper;
import com.medbox.server.mapper.CaptureMapper;
import com.medbox.server.mapper.MedPlanItemMapper;
import com.medbox.server.mapper.MedRecordMapper;
import com.medbox.server.mapper.MedicineMapper;
import com.medbox.server.support.TableInfoWarmup;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import com.medbox.server.ws.WsTopic;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

/**
 * {@link WrongDrugAlarmService} 单元测试：纯 Mockito，不连库、不启 Spring。
 *
 * <p>按文档 06 §2.9 规则表覆盖：② 低置信度只标记抓拍不告警；③ 实际药 == 计划药回写 false 不告警；
 * ④ 实际药 / 计划药缺失不判定；⑤ 实际药 != 计划药回写 true + 生成 WRONG_DRUG(CRITICAL) 并推 WS；
 * ⑥ 去重冲突跳过不重复推；⑦ disabled 整体跳过。{@code confidence == null} 视作高置信度、仍按药品比对。
 */
class WrongDrugAlarmServiceTest {

    private static final String RECORD = "mr-1001";
    private static final String ELDER = "u-1001";
    private static final String PLAN = "p-1001";
    private static final String ITEM = "pi-1001";
    private static final String PLANNED_MED = "m-plan";
    private static final String ACTUAL_MED = "m-wrong";
    private static final String DEVICE = "BOXA1";

    private final MedPlanItemMapper medPlanItemMapper = mock(MedPlanItemMapper.class);
    private final MedRecordMapper medRecordMapper = mock(MedRecordMapper.class);
    private final MedicineMapper medicineMapper = mock(MedicineMapper.class);
    private final AlarmMapper alarmMapper = mock(AlarmMapper.class);
    private final CaptureMapper captureMapper = mock(CaptureMapper.class);
    private final WrongDrugProperties props = new WrongDrugProperties();
    private final ObjectProvider<WsPushService> wsProvider = mock(ObjectProvider.class);
    private final WsPushService wsPushService = mock(WsPushService.class);

    private WrongDrugAlarmService service;

    @BeforeEach
    void setUp() {
        // Lambda 条件构造器（LambdaUpdateWrapper / LambdaQueryWrapper）需要实体 TableInfo 才能把方法引用解析成列名
        TableInfoWarmup.init(MedRecord.class, MedPlanItem.class, Medicine.class);

        props.setEnabled(true);
        props.setConfidenceThreshold(0.6);
        when(wsProvider.getIfAvailable()).thenReturn(wsPushService);
        when(alarmMapper.nextAlarmId()).thenReturn("a-1001");
        when(medRecordMapper.update(any(), any())).thenReturn(1);
        when(captureMapper.markLowConfidence(anyString(), anyString())).thenReturn(1);
        stubMedicine(PLANNED_MED, "阿莫西林");
        stubMedicine(ACTUAL_MED, "钙片");
        service = new WrongDrugAlarmService(medPlanItemMapper, medRecordMapper, medicineMapper,
                alarmMapper, captureMapper, props, wsProvider);
    }

    private void stubMedicine(String id, String name) {
        Medicine m = new Medicine();
        m.setMedicineId(id);
        m.setName(name);
        when(medicineMapper.selectById(id)).thenReturn(m);
    }

    private DispenseRecordedEvent event(String medicineId, Double confidence, String captureId) {
        return new DispenseRecordedEvent(RECORD, ELDER, PLAN, ITEM, medicineId,
                OffsetDateTime.now(), OffsetDateTime.now(), true, false, confidence, DEVICE, captureId);
    }

    private MedPlanItem plannedItem() {
        MedPlanItem item = new MedPlanItem();
        item.setItemId(ITEM);
        item.setPlanId(PLAN);
        item.setMedicineId(PLANNED_MED);
        return item;
    }

    private void stubPlannedItem() {
        when(medPlanItemMapper.selectOne(any())).thenReturn(plannedItem());
    }

    @Test
    @DisplayName("② 低置信度（<阈值）且无 captureId：只跳过，不标记、不告警、不回写")
    void lowConfidenceNoCaptureId() {
        service.onDispenseRecorded(event(PLANNED_MED, 0.3, null));

        verify(captureMapper, never()).markLowConfidence(anyString(), anyString());
        verify(alarmMapper, never()).insert(any(Alarm.class));
        verify(medRecordMapper, never()).update(any(), any());
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("② 低置信度（<阈值）且有 captureId：标记 LOW_CONFIDENCE，不告警、不回写")
    void lowConfidenceMarksCapture() {
        service.onDispenseRecorded(event(PLANNED_MED, 0.3, "cap-1"));

        verify(captureMapper).markLowConfidence(eq("cap-1"), eq("LOW_CONFIDENCE"));
        verify(alarmMapper, never()).insert(any(Alarm.class));
        verify(medRecordMapper, never()).update(any(), any());
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("③ 实际药 == 计划药：回写 wrong_drug=false，不告警、不推送")
    void medicineMatchesNoAlarm() {
        stubPlannedItem();
        service.onDispenseRecorded(event(PLANNED_MED, 0.9, null));

        ArgumentCaptor<LambdaUpdateWrapper<MedRecord>> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(medRecordMapper).update(isNull(), wrapper.capture());
        // 回写值为 Boolean.FALSE（update 用 ? 占位，值落在参数表里，故查参数表而非 SQL 串）
        assertThat(wrapper.getValue().getParamNameValuePairs().containsValue(false)).isTrue();

        verify(alarmMapper, never()).insert(any(Alarm.class));
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("⑤ 实际药 != 计划药：回写 wrong_drug=true + 生成 WRONG_DRUG(CRITICAL) 并推 WS")
    void medicineDiffersRaisesAlarm() {
        stubPlannedItem();
        service.onDispenseRecorded(event(ACTUAL_MED, 0.9, "cap-1"));

        ArgumentCaptor<LambdaUpdateWrapper<MedRecord>> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(medRecordMapper).update(isNull(), wrapper.capture());
        // 回写值为 Boolean.TRUE（update 用 ? 占位，值落在参数表里，故查参数表而非 SQL 串）
        assertThat(wrapper.getValue().getParamNameValuePairs().containsValue(true)).isTrue();

        org.mockito.ArgumentCaptor<Alarm> alarmCaptor =
                org.mockito.ArgumentCaptor.forClass(Alarm.class);
        verify(alarmMapper).insert(alarmCaptor.capture());
        assertThat(alarmCaptor.getValue().getType()).isEqualTo("WRONG_DRUG");
        assertThat(alarmCaptor.getValue().getLevel()).isEqualTo("CRITICAL");
        assertThat(alarmCaptor.getValue().getMedicineId()).isEqualTo(ACTUAL_MED);
        assertThat(alarmCaptor.getValue().getDedupKey()).isEqualTo("WRONG_DRUG:" + RECORD);
        assertThat(alarmCaptor.getValue().getMessage()).contains("阿莫西林").contains("钙片");

        verify(wsPushService).publish(eq(WsTopic.elder(ELDER)), eq(WsFrameType.ALARM), any());
    }

    @Test
    @DisplayName("③ confidence == null 视作高置信度：仍按药品比对（此处错服 → 告警）")
    void nullConfidenceStillCompares() {
        stubPlannedItem();
        service.onDispenseRecorded(event(ACTUAL_MED, null, null));

        verify(alarmMapper).insert(any(Alarm.class));
        verify(wsPushService).publish(any(), eq(WsFrameType.ALARM), any());
    }

    @Test
    @DisplayName("④ 实际药为空：无法判定，不告警、不标记、不回写")
    void blankActualMedicineNoAction() {
        stubPlannedItem();
        service.onDispenseRecorded(event(null, 0.9, "cap-1"));

        verify(alarmMapper, never()).insert(any(Alarm.class));
        verify(medRecordMapper, never()).update(any(), any());
        verify(captureMapper, never()).markLowConfidence(anyString(), anyString());
    }

    @Test
    @DisplayName("④ 计划药缺失：无法判定，不告警、不回写")
    void plannedItemMissingNoAction() {
        when(medPlanItemMapper.selectOne(any())).thenReturn(null);
        service.onDispenseRecorded(event(ACTUAL_MED, 0.9, "cap-1"));

        verify(alarmMapper, never()).insert(any(Alarm.class));
        verify(medRecordMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("⑥ 去重：dedup_key 唯一索引冲突命中 → 跳过，不重复推送")
    void dedupConflictSkipsPush() {
        stubPlannedItem();
        when(alarmMapper.insert(any(Alarm.class))).thenThrow(new DuplicateKeyException("dup dedup_key"));
        service.onDispenseRecorded(event(ACTUAL_MED, 0.9, "cap-1"));

        verify(alarmMapper, atLeastOnce()).insert(any(Alarm.class));
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("⑦ disabled：整体跳过，不告警、不标记、不回写")
    void disabledSkips() {
        props.setEnabled(false);
        stubPlannedItem();
        service.onDispenseRecorded(event(ACTUAL_MED, 0.9, "cap-1"));

        verify(alarmMapper, never()).insert(any(Alarm.class));
        verify(medRecordMapper, never()).update(any(), any());
        verify(captureMapper, never()).markLowConfidence(anyString(), anyString());
    }

    @Test
    @DisplayName("低置信度阈值边界：confidence 恰好等于阈值仍按药品比对（不放过错服）")
    void confidenceAtThresholdCompares() {
        stubPlannedItem();
        // threshold 默认 0.6，confidence = 0.6 不满足 < 阈值，进入比对分支
        service.onDispenseRecorded(event(ACTUAL_MED, 0.6, null));

        verify(alarmMapper).insert(any(Alarm.class));
    }
}
