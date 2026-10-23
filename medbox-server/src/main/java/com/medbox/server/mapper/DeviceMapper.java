package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.DeviceType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * {@code device} 表的 Mapper（见文档 06 的 2.3）。
 *
 * <p>本分支（{@code feat/backend-mqtt}）只用到在线状态的读写；
 * {@code feat/device-core} 后续在此扩展设备注册 / 列表查询等方法即可。
 */
@Mapper
public interface DeviceMapper extends BaseMapper<Device> {

    /** 取老人名下的 MAIN 主控设备（漏服扫描用它判在线状态与归属，见文档 06 §2.3）。 */
    @Select("SELECT * FROM device WHERE elder_id = #{elderId} AND device_type = 'MAIN' LIMIT 1")
    Device selectMainByElderId(String elderId);
}
