package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.MedicineStock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;


@Mapper
public interface MedicineStockMapper extends BaseMapper<MedicineStock>{
    @Select("SELECT 's-' ||nextval('medicine_stock_biz_id_seq')")
    String nextStockId();
}