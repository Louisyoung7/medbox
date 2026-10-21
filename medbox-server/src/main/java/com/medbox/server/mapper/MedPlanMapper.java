package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.MedPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * {@code med_plan} 表的 Mapper（见文档 06 的 2.6）。
 *
 * <p><b>取号</b>：用独立序列 {@code med_plan_biz_id_seq}（V9 建），不碰 {@code id BIGSERIAL}
 * 自动序列（同 medicine-core 的 medicine_biz_id_seq 同一个坑）。
 */
@Mapper
public interface MedPlanMapper extends BaseMapper<MedPlan> {

    @Select("SELECT 'p-' || nextval('med_plan_biz_id_seq')")
    String nextPlanId();
}
