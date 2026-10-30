package com.medbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.config.CaptureProperties;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedPlanItem;
import com.medbox.server.domain.MedRecord;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.dto.capture.CaptureDispenseInput;
import com.medbox.server.mapper.AlarmSettingMapper;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.MedPlanItemMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.MedRecordMapper;
import com.medbox.server.support.TableInfoWarmup;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@link DispenseConsumeService} 的集成测试：真实 Spring 链路（Bean 装配 / 事务 / 事件派发），
 * 仅把 Mapper 与 MedicineStockService / CaptureProperties 换成替身，不连 PostgreSQL（H2 替换 DataSource）。
 *
 * <p>本类重点验两件事：
 * <ol>
 *   <li><b>第二层业务幂等</b>：{@code med_record} 唯一索引冲突 → {@code setRollbackOnly()} → 写记录与扣库存都回滚、不重复；</li>
 *   <li><b>领域事件</b>：事务提交后（AFTER_COMMIT）{@link DispenseRecordedEvent} 被监听器收到（带 faceResult）。</li>
 * </ol>
 */
@SpringBootTest
class DispenseConsumeIntegrationTest {

    @TestConfiguration
    static class H2DataSourceConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-test")
                    .build();
        }

        @Bean
        public CapturingListener capturingListener() {
            return new CapturingListener();
        }
    }

    /** 捕获 DispenseRecordedEvent，验证 AFTER_COMMIT 派发。 */
    static class CapturingListener {
        final List<DispenseRecordedEvent> events = new java.util.ArrayList<>();
        @EventListener
        public void on(DispenseRecordedEvent e) {
            events.add(e);
        }
    }

    private static final String CAMERA_ID = "CAM01";
    private static final String MAIN_ID = "BOXA1001";
    private static final String ELDER_ID = "u-1001";
    private static final String PLAN_ID = "p-1001";
    private static final String ITEM_ID = "pi-1001";
    private static final String MED_ID = "m-205";

    @Autowired
    private DispenseConsumeService consumeService;

    @Autowired
    private CapturingListener listener;

    @MockitoBean
    private MedRecordMapper medRecordMapper;
    @MockitoBean
    private MedPlanMapper medPlanMapper;
    @MockitoBean
    private MedPlanItemMapper medPlanItemMapper;
    @MockitoBean
    private DeviceMapper deviceMapper;
    @MockitoBean
    private MedicineStockService medicineStockService;
    @MockitoBean
    private CaptureProperties captureProperties;
    @MockitoBean
    private AlarmSettingMapper alarmSettingMapper;

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(Device.class, MedPlan.class, MedPlanItem.class, MedRecord.class);
        listener.events.clear();

        when(medRecordMapper.nextRecordId()).thenReturn("mr-1001");
        when(medRecordMapper.insert(any(MedRecord.class))).thenReturn(1);
        when(medicineStockService.deduct(anyString(), anyString(), anyInt())).thenReturn(1);
        when(captureProperties.getConfidenceThreshold()).thenReturn(new BigDecimal("0.6"));
        when(alarmSettingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null); // 无老人级阈值 → 默认 15

        Device device = new Device();
        device.setDeviceId(MAIN_ID);
        device.setDeviceType(DeviceType.MAIN);
        device.setParentDeviceId(MAIN_ID);
        device.setElderId(ELDER_ID);
        device.setTimezone("Asia/Shanghai");
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(device);

        MedPlan plan = new MedPlan();
        plan.setPlanId(PLAN_ID);
        plan.setElderId(ELDER_ID);
        plan.setTimes(List.of("08:00"));
        when(medPlanMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(plan);

        MedPlanItem item = new MedPlanItem();
        item.setItemId(ITEM_ID);
        item.setPlanId(PLAN_ID);
        item.setMedicineId(MED_ID);
        when(medPlanItemMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(item);
    }

    private CaptureDispenseInput payload() {
        CaptureDispenseInput i = new CaptureDispenseInput();
        i.setCameraDeviceId(CAMERA_ID);
        i.setCaptureId("cap-1");
        i.setPlanId(PLAN_ID);
        i.setPlanItemId(ITEM_ID);
        i.setMedicineId(MED_ID);
        i.setActualDose("1");
        i.setSource("CAMERA");
        i.setFaceResult("TAKEN");
        i.setConfidence(new BigDecimal("0.9"));
        i.setCapturedAt(OffsetDateTime.parse("2026-10-23T00:05:00Z")); // 本地 08:05，onTime=true
        return i;
    }

    @Test
    @DisplayName("正常链路：写记录 + 扣库存 + 事务提交后派发 DispenseRecordedEvent（带 faceResult）")
    void commitsAndPublishesEvent() {
        assertThat(consumeService.consume(payload())).isPresent();

        verify(medRecordMapper).insert(any(MedRecord.class));
        verify(medicineStockService).deduct(MAIN_ID, MED_ID, 1);
        // AFTER_COMMIT 事件在事务提交后派发，监听器应收到
        assertThat(listener.events).hasSize(1);
        assertThat(listener.events.get(0).getPlanId()).isEqualTo(PLAN_ID);
        assertThat(listener.events.get(0).getOnTime()).isTrue();
        assertThat(listener.events.get(0).getFaceResult()).isEqualTo("TAKEN");
    }

    @Test
    @DisplayName("第二层幂等：唯一索引冲突 → 回滚，写记录与扣库存都不发生（只一次），不发布事件")
    void idempotentConflictRollback() {
        when(medRecordMapper.insert(any(MedRecord.class)))
                .thenThrow(new DuplicateKeyException("duplicate key (plan_item_id, plan_time)"));

        assertThat(consumeService.consume(payload())).isEmpty();

        // 冲突后整事务回滚：扣库存不应执行
        verify(medicineStockService, never()).deduct(anyString(), anyString(), anyInt());
        // 写记录失败 → 未到发布事件那一步
        assertThat(listener.events).isEmpty();
    }
}
