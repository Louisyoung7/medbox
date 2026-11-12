package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.MedicineStock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * {@code medicine_stock} 表 Mapper（见文档 06 的 2.5）。
 *
 * <p>库存是监护人手工维护的台账：系统不按服药事件自动扣减、不推算消耗，
 * 故本 Mapper 只提供业务号取号，库存增删改走 BaseMapper。
 */
@Mapper
public interface MedicineStockMapper extends BaseMapper<MedicineStock> {

    @Select("SELECT 's-' || nextval('medicine_stock_biz_id_seq')")
    String nextStockId();
}
