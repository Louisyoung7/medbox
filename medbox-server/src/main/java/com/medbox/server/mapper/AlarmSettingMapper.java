package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.AlarmSetting;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code alarm_setting} 表的 Mapper（见文档 06 的 2.10）。
 *
 * <p>本期（feat/dispense-consume）仅 {@code selectOne} 按 {@code elder_id} 取老人级漏服容忍；
 * 写入与 {@code env_threshold} JSONB 处理归 {@code feat/alarm-core}。
 */
@Mapper
public interface AlarmSettingMapper extends BaseMapper<AlarmSetting> {
}
