package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.MedRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * {@code med_record} 表的 Mapper（见文档 06 的 2.7）。
 *
 * <p><b>取号</b>：用独立序列 {@code med_record_biz_id_seq}（V10 建），不碰 {@code id BIGSERIAL}
 * 自动序列（同 medicine-core / plan-core 同一个坑：同名会被 {@code IF NOT EXISTS} 静默跳过）。
 */
@Mapper
public interface MedRecordMapper extends BaseMapper<MedRecord> {

    @Select("SELECT 'mr-' || nextval('med_record_biz_id_seq')")
    String nextRecordId();
}
