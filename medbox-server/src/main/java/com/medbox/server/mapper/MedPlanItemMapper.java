package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.MedPlanItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * {@code med_plan_item} 表的 Mapper（见文档 06 的 2.6.1）。
 *
 * <p><b>取号</b>：用独立序列 {@code med_plan_item_biz_id_seq}（V9 建），不碰 {@code id BIGSERIAL}。
 */
@Mapper
public interface MedPlanItemMapper extends BaseMapper<MedPlanItem> {

    @Select("SELECT 'pi-' || nextval('med_plan_item_biz_id_seq')")
    String nextItemId();
}
