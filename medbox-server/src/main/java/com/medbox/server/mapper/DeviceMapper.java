package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.Device;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code device} 表的 Mapper（见文档 06 的 2.3）。
 *
 * <p>本分支（{@code feat/backend-mqtt}）只用到在线状态的读写；
 * {@code feat/device-core} 后续在此扩展设备注册 / 列表查询等方法即可。
 */
@Mapper
public interface DeviceMapper extends BaseMapper<Device> {
}
