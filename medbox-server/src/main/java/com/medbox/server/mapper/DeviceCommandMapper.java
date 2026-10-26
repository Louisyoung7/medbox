package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.DeviceCommand;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** {@code device_command} 表（V15，feat/device-command）。 */
@Mapper
public interface DeviceCommandMapper extends BaseMapper<DeviceCommand> {

    /** 取业务 ID（V15 的序列，前缀 c-，同 MedPlanMapper 的取号形态）。 */
    @Select("SELECT 'c-' || nextval('device_command_biz_id_seq')")
    String nextCommandId();
}
