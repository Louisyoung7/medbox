package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.Medicine;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

  /**
   * {@code medicine} 表的 Mapper（见文档 06 的 2.4）。
   *
   * <p><b>取号</b>：用独立序列 {@code medicine_biz_id_seq}（V8
  建）而不是 {@code medicine_id_seq} ——
   * 后者是 {@code medicine} 表 {@code id BIGSERIAL}
  自动创建的序列，复用它拿到的会是自增主键的值
   * （第一条药变成 {@code m-1}）。与 {@link UserMapper#nextUserId()}
  同一个坑、同一个解法。
   */

@Mapper
public interface MedicineMapper extends BaseMapper<Medicine>{
    @Select("SELECT 'm-' ||nextval('medicine_biz_id_seq')")
    String nextMedicineId();
}

