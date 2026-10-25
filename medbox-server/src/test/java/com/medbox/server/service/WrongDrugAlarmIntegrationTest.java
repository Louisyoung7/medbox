package com.medbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.config.WrongDrugProperties;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedPlanItem;
import com.medbox.server.domain.MedRecord;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.mapper.AlarmMapper;
import com.medbox.server.mapper.CaptureMapper;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.MedPlanItemMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.MedRecordMapper;
import com.medbox.server.mapper.MedicineMapper;
import com.medbox.server.mapper.MedicineStockMapper;
import com.medbox.server.mapper.AlarmSettingMapper;
import com.medbox.server.mqtt.payload.DispenseEventPayload;
import com.medbox.server.support.TableInfoWarmup;
import com.medbox.server.ws.WsFrameType;
import com.medbox.server.ws.WsPushService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@link WrongDrugAlarmService} 集成测试：真实 Spring 链路（Bean 装配 / 事务 / 事件派发），
 * 仅把 Mapper 换成替身，不连 PostgreSQL（H2 替换 DataSource）。
 *
 * <p>重点验：真实 {@link DispenseConsumeService#consume} 事务提交后派发 {@link DispenseRecordedEvent}，
 * 被真实 {@link WrongDrugAlarmService} 消费，按文档 06 §2.9 完成"判定 → 回写 → 告警 → 标记 → 推送"。
 * 覆盖三种典型场景：错服（告警 + 推送）、正确服药（只回写）、低置信度（只标记抓拍）。
 *
 * <p><b>不加 {@code @Transactional}</b>：否则 AFTER_COMMIT 事件不会触发（事务未提交）。
 */
@SpringBootTest
class WrongDrugAlarmIntegrationTest {

    @TestConfiguration
    static class H2DataSourceConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-test-wrong-drug")
                    .build();
        }
    }

    private static final String DEVICE_ID = "BOXA1001";
    private static final String ELDER_ID = "u-1001";
    private static final String PLAN_ID = "p-1001";
    private static final String ITEM_ID = "pi-1001";
    private static final String PLANNED_MED = "m-plan";
    private static final String ACTUAL_MED = "m-wrong";

    @Autowired
    private DispenseConsumeService consumeService;

    @Autowired
    private WrongDrugProperties wrongDrugProperties;

    @MockitoBean
    private MedRecordMapper medRecordMapper;
    @MockitoBean
    private MedPlanMapper medPlanMapper;
    @MockitoBean
    private MedPlanItemMapper medPlanItemMapper;
    @MockitoBean
    private DeviceMapper deviceMapper;
    @MockitoBean
    private MedicineStockMapper medicineStockMapper;
    @MockitoBean
    private AlarmSettingMapper alarmSettingMapper;
    @MockitoBean
    private MedicineMapper medicineMapper;
    @MockitoBean
    private AlarmMapper alarmMapper;
    @MockitoBean
    private CaptureMapper captureMapper;
    @MockitoBean
    private WsPushService wsPushService;

    @BeforeEach
    void setUp() {
        // 还原默认配置（enabled=true，threshold=0.6）
        wrongDrugProperties.setEnabled(true);
        wrongDrugProperties.setConfidenceThreshold(0.6);

        TableInfoWarmup.init(Device.class, MedPlan.class, MedPlanItem.class, MedRecord.class);

        when(medRecordMapper.nextRecordId()).thenReturn("mr-1001");
        when(medRecordMapper.insert(any(MedRecord.class))).thenReturn(1);
        when(medRecordMapper.update(any(), any())).thenReturn(1);
        when(medicineStockMapper.deduct(anyString(), anyString(), anyInt())).thenReturn(1);
        when(alarmSettingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(alarmMapper.nextAlarmId()).thenReturn("a-1001");
        when(alarmMapper.insert(any(com.medbox.server.domain.Alarm.class))).thenReturn(1);
        when(captureMapper.markLowConfidence(anyString(), anyString())).thenReturn(1);

        Device device = new Device();
        device.setDeviceId(DEVICE_ID);
        device.setDeviceType(DeviceType.MAIN);
        device.setElderId(ELDER_ID);
        device.setTimezone("Asia/Shanghai");
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(device);

        MedPlan plan = new MedPlan();
        plan.setPlanId(PLAN_ID);
        plan.setElderId(ELDER_ID);
        plan.setTimes(List.of("08:00"));
        when(medPlanMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(plan);

        // 同一 mock 同时满足 consume 的校验查询与 wrong-drug 的计划药查询
        MedPlanItem item = new MedPlanItem();
        item.setItemId(ITEM_ID);
        item.setPlanId(PLAN_ID);
        item.setMedicineId(PLANNED_MED);
        when(medPlanItemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(item);

        com.medbox.server.domain.Medicine planned = new com.medbox.server.domain.Medicine();
        planned.setMedicineId(PLANNED_MED);
        planned.setName("阿莫西林");
        com.medbox.server.domain.Medicine actual = new com.medbox.server.domain.Medicine();
        actual.setMedicineId(ACTUAL_MED);
        actual.setName("钙片");
        when(medicineMapper.selectById(PLANNED_MED)).thenReturn(planned);
        when(medicineMapper.selectById(ACTUAL_MED)).thenReturn(actual);
    }

    private DispenseEventPayload payload(String medicineId, Double confidence, String imageId) {
        DispenseEventPayload p = new DispenseEventPayload();
        p.setMsgId("BOXA1001-0000000300");
        p.setTs(Instant.parse("2026-10-23T00:05:00Z").toEpochMilli()); // 本地 08:05，onTime=true
        p.setPlanId(PLAN_ID);
        p.setPlanItemId(ITEM_ID);
        p.setMedicineId(medicineId);
        p.setActualDose("1");
        p.setWrongDrug(false);
        p.setSource("CAMERA");
        p.setConfidence(confidence);
        p.setImageId(imageId);
        return p;
    }

    @Test
    @DisplayName("集成链路：错服（实际药 != 计划药，高置信度）→ 回写 wrong_drug=true + 生成 WRONG_DRUG 告警 + 推 WS")
    void wrongDrugRaisesAlarm() {
        consumeService.consume(DEVICE_ID, payload(ACTUAL_MED, 0.9, "cap-1"));

        // 回写
        verify(medRecordMapper).update(any(), any());
        // 落库告警并经 WS 推送
        verify(alarmMapper).insert(any(com.medbox.server.domain.Alarm.class));
        verify(wsPushService).publish(anyString(), eq(WsFrameType.ALARM), any());
    }

    @Test
    @DisplayName("集成链路：正确服药（实际药 == 计划药）→ 回写 wrong_drug=false，不告警、不推送")
    void correctDrugNoAlarm() {
        consumeService.consume(DEVICE_ID, payload(PLANNED_MED, 0.9, "cap-1"));

        verify(medRecordMapper).update(any(), any());
        verify(alarmMapper, never()).insert(any(com.medbox.server.domain.Alarm.class));
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("集成链路：低置信度（<阈值）且有抓拍 → 只标记 LOW_CONFIDENCE，不告警、不回写")
    void lowConfidenceMarksCapture() {
        consumeService.consume(DEVICE_ID, payload(PLANNED_MED, 0.3, "cap-1"));

        verify(captureMapper).markLowConfidence(eq("cap-1"), eq("LOW_CONFIDENCE"));
        verify(alarmMapper, never()).insert(any(com.medbox.server.domain.Alarm.class));
        verify(medRecordMapper, never()).update(any(), any());
        verify(wsPushService, never()).publish(any(), any(), any());
    }

    @Test
    @DisplayName("集成链路：去重冲突 → 不重复推送 WS")
    void dedupConflictSkipsPush() {
        when(alarmMapper.insert(any(com.medbox.server.domain.Alarm.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("dup dedup_key"));

        consumeService.consume(DEVICE_ID, payload(ACTUAL_MED, 0.9, "cap-1"));

        verify(alarmMapper, atLeastOnce()).insert(any(com.medbox.server.domain.Alarm.class));
        verify(wsPushService, never()).publish(any(), any(), any());
    }
}
