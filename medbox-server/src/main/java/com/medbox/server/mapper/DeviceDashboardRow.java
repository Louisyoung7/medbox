package com.medbox.server.mapper;

import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 设备网络状态查询行（{@code device} 左联 {@code "user"}），仅供 {@link AdminDashboardMapper} 结果映射。
 *
 * <p>字段名与 SQL 别名（device_id / elder_id / elder_name / online_state / last_heartbeat）对应，
 * 依赖 MyBatis 下划线转驼峰（online_state → onlineState）。
 */
@Data
public class DeviceDashboardRow {

    private String deviceId;
    private String elderId;
    private String elderName;
    private String onlineState;
    private OffsetDateTime lastHeartbeat;
}
