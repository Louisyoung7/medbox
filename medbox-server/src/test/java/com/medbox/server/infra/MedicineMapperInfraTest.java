package com.medbox.server.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medbox.server.domain.Medicine;
import com.medbox.server.mapper.MedicineMapper;
import com.medbox.server.testsupport.PostgresInfraTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link MedicineMapper} 的真实 SQL 验证：业务号序列 {@code medicine_biz_id_seq}
 * 与药品档案的落库 / 读回（V23~V26 删列、改名后的列集合）。
 */
@Tag("infra")
@DisplayName("MedicineMapper：真实 PostgreSQL 序列与药品档案读回")
class MedicineMapperInfraTest extends PostgresInfraTestBase {

    @Autowired
    private MedicineMapper medicineMapper;

    @Test
    @DisplayName("nextMedicineId 返回 m- 业务号且递增")
    void nextMedicineIdReturnsIncreasingBizId() {
        String first = medicineMapper.nextMedicineId();
        String second = medicineMapper.nextMedicineId();

        assertTrue(first.matches("m-\\d+"), "业务号格式不对：" + first);
        assertTrue(bizNo(first) < bizNo(second), "业务号应递增：" + first + " -> " + second);
    }

    @Test
    @DisplayName("药品档案落库后按主键读回，字段与 JSONB 无关列均保留")
    void insertAndSelectRoundTrip() {
        Medicine medicine = new Medicine();
        medicine.setMedicineId(medicineMapper.nextMedicineId());
        medicine.setOwnerElderId("u-infra-med-" + bizNo(medicine.getMedicineId()));
        medicine.setName("阿司匹林肠溶片");
        medicine.setDosePerTime("1 片");
        medicine.setUnit("片");
        medicine.setDescription("饭后服用，胃溃疡患者慎用");
        medicine.setStorageCond("避光密封，室温保存");

        assertEquals(1, medicineMapper.insert(medicine));
        assertNotNull(medicine.getId(), "自增主键应回填");

        Medicine loaded = medicineMapper.selectById(medicine.getId());
        assertNotNull(loaded);
        assertEquals(medicine.getMedicineId(), loaded.getMedicineId());
        assertEquals(medicine.getOwnerElderId(), loaded.getOwnerElderId());
        assertEquals("阿司匹林肠溶片", loaded.getName());
        assertEquals("1 片", loaded.getDosePerTime());
        assertEquals("片", loaded.getUnit());
        assertEquals("饭后服用，胃溃疡患者慎用", loaded.getDescription());
        assertEquals("避光密封，室温保存", loaded.getStorageCond());
        assertNotNull(loaded.getCreatedAt(), "created_at 应由数据库默认值填充");
    }

    private static long bizNo(String medicineId) {
        return Long.parseLong(medicineId.substring("m-".length()));
    }
}
