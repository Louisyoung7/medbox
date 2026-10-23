package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.Alarm;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * {@code alarm} 表的 Mapper（见文档 06 的 2.9）。
 *
 * <p><b>取号</b>：用独立序列 {@code alarm_biz_id_seq}（V12 建）而不是 {@code alarm_id_seq} ——
 * 后者是 {@code alarm} 表 {@code id BIGSERIAL} 自动创建的序列，复用会取到自增主键值
 * （与 med_record / medicine / med_plan 等同一个坑）。前缀 {@code a-}。
 *
 * <p>去重由 {@code alarm.dedup_key} 的部分唯一索引（V12 的 {@code uk_alarm_dedup_key}）兜底：
 * 重复扫描同一槽时 INSERT 命中唯一索引冲突，Service 层捕获 {@code DuplicateKeyException} 后跳过。
 */
@Mapper
public interface AlarmMapper extends BaseMapper<Alarm> {

    @Select("SELECT 'a-' || nextval('alarm_biz_id_seq')")
    String nextAlarmId();
}
