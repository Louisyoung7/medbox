package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.DeviceEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 设备事件流水（{@code device_event}，见文档 06 的 2.16）。
 *
 * <p>只写 / 读"某台设备的流水"这一件事，因此不需要自定义 SQL：分页查询走
 * MyBatis-Plus 的 {@code selectPage}（分页插件见 {@code MybatisPlusConfig}），
 * 条件用 {@code LambdaQueryWrapper} 拼 —— 与本项目"无 mapper XML"的约定一致。
 *
 * <p>写入方有两处：HTTP 侧的登记事件（{@code DeviceEventService}）与 MQTT 侧的状态跃变
 * （{@code DeviceStatusService} 通过 {@code ObjectProvider} 间接调用）。
 */
@Mapper
public interface DeviceEventMapper extends BaseMapper<DeviceEvent> {
}
