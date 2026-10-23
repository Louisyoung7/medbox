package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.MedicineStock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;


@Mapper
public interface MedicineStockMapper extends BaseMapper<MedicineStock>{
    @Select("SELECT 's-' ||nextval('medicine_stock_biz_id_seq')")
    String nextStockId();

    /**
     * 服药后扣减库存（feat/dispense-consume）。
     *
     * <p><b>按识别到的药品扣</b>：{@code device_id} 是上报主控、{@code medicine_id} 是摄像头识别结果
     * （错服时为"错拿的药"，照样扣 —— 药确实被拿走了）。
     *
     * <p><b>不阻断写记录</b>：库存不足只扣到 0（{@code GREATEST(quantity - delta, 0)}）；
     * 没有对应库存行（影响行数 0）由调用方 warn，不抛异常。
     * {@code updated_at} 由 DB 侧 {@code NOW()} 维护，避免应用层时钟漂移。
     *
     * @return 受影响行数（0 = 该药箱无此药品库存行）
     */
    @Update("UPDATE medicine_stock SET quantity = GREATEST(quantity - #{delta}, 0), updated_at = NOW() "
            + "WHERE device_id = #{deviceId} AND medicine_id = #{medicineId}")
    int deduct(@Param("deviceId") String deviceId,
               @Param("medicineId") String medicineId,
               @Param("delta") int delta);
}