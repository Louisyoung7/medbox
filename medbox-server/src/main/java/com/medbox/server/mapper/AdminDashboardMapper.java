package com.medbox.server.mapper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 管理后台监控看板只读聚合查询（见文档 02 §10.4 / 06 §2.18）。
 *
 * <p>全部为单表 / 简单分组查询，不改数据；聚合所需的服药槽展开在 {@code AdminDashboardService}
 * 内复用 {@code PlanSlotResolver} / {@code DeviceTimezoneResolver} 完成（与核心端 miss-alarm / plan 口径一致）。
 * 设备连接态由 {@code device.online_state} 表示（表无独立 mqtt_connected 列）；下行消息以
 * {@code device_command} 为准（mqtt_message 仅记录上行）。
 */
@Mapper
public interface AdminDashboardMapper {

    /** 设备总数。 */
    @Select("SELECT COUNT(*) FROM device")
    long countDevices();

    /** 按在线状态计数（state = 'ONLINE' | 'OFFLINE'）。 */
    @Select("SELECT COUNT(*) FROM device WHERE online_state = #{state}")
    long countDevicesByState(@Param("state") String state);

    /** 最近设备列表（按心跳倒序），左联 user 取老人姓名。 */
    @Select("""
            SELECT d.device_id AS deviceId, d.elder_id AS elderId, u.name AS elderName,
                   d.online_state AS onlineState, d.last_heartbeat AS lastHeartbeat
            FROM device d
            LEFT JOIN "user" u ON u.user_id = d.elder_id
            ORDER BY d.last_heartbeat DESC NULLS LAST
            LIMIT #{limit}
            """)
    List<DeviceDashboardRow> selectRecentDevices(@Param("limit") int limit);

    /** 老人总数（role = ELDER）。 */
    @Select("SELECT COUNT(*) FROM \"user\" WHERE role = 'ELDER'")
    long countElders();

    /** 启用中计划数（status = ENABLED）。 */
    @Select("SELECT COUNT(*) FROM med_plan WHERE status = 'ENABLED'")
    long countEnabledPlans();

    /** 某老人某窗口内的服药记录数（on_time 取参）。 */
    @Select("""
            SELECT COUNT(*) FROM med_record
            WHERE elder_id = #{elderId} AND on_time = #{onTime}
              AND plan_time >= #{from} AND plan_time < #{to}
            """)
    long countRecordsByElderWindow(@Param("elderId") String elderId,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to,
                                  @Param("onTime") boolean onTime);

    /** 按 UTC 日分组统计已服（on_time = true）。 */
    @Select("""
            SELECT to_char(plan_time AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day, COUNT(*) AS cnt
            FROM med_record
            WHERE on_time = true AND plan_time >= #{from} AND plan_time < #{to}
            GROUP BY day
            """)
    List<DayCountRow> countTakenByDay(@Param("from") Instant from, @Param("to") Instant to);

    /** 按 UTC 日分组统计漏服（on_time = false）。 */
    @Select("""
            SELECT to_char(plan_time AT TIME ZONE 'UTC', 'YYYY-MM-DD') AS day, COUNT(*) AS cnt
            FROM med_record
            WHERE on_time = false AND plan_time >= #{from} AND plan_time < #{to}
            GROUP BY day
            """)
    List<DayCountRow> countMissedByDay(@Param("from") Instant from, @Param("to") Instant to);

    /** 按老人分组统计已服（on_time = true）。 */
    @Select("""
            SELECT mr.elder_id AS elderId, u.name AS elderName, COUNT(*) AS cnt
            FROM med_record mr
            LEFT JOIN "user" u ON u.user_id = mr.elder_id
            WHERE mr.on_time = true AND mr.plan_time >= #{from} AND mr.plan_time < #{to}
            GROUP BY mr.elder_id, u.name
            """)
    List<ElderCountRow> countTakenByElder(@Param("from") Instant from, @Param("to") Instant to);

    /** 按老人分组统计漏服（on_time = false）。 */
    @Select("""
            SELECT mr.elder_id AS elderId, u.name AS elderName, COUNT(*) AS cnt
            FROM med_record mr
            LEFT JOIN "user" u ON u.user_id = mr.elder_id
            WHERE mr.on_time = false AND mr.plan_time >= #{from} AND mr.plan_time < #{to}
            GROUP BY mr.elder_id, u.name
            """)
    List<ElderCountRow> countMissedByElder(@Param("from") Instant from, @Param("to") Instant to);

    /** 漏服按 plan_time 的 UTC 小时分桶。 */
    @Select("""
            SELECT EXTRACT(HOUR FROM plan_time)::int AS hr, COUNT(*) AS cnt
            FROM med_record
            WHERE on_time = false AND plan_time >= #{from} AND plan_time < #{to}
            GROUP BY hr ORDER BY hr
            """)
    List<HourCountRow> countMissedByHour(@Param("from") Instant from, @Param("to") Instant to);

    /** 上行消息累计（mqtt_message 仅上行去重）。 */
    @Select("SELECT COUNT(*) FROM mqtt_message")
    long countMqttMessages();

    /** 上行消息近一分钟（received_at >= since）。 */
    @Select("SELECT COUNT(*) FROM mqtt_message WHERE received_at >= #{since}")
    long countMqttMessagesSince(@Param("since") Instant since);

    /** 下行命令累计（device_command）。 */
    @Select("SELECT COUNT(*) FROM device_command")
    long countDeviceCommands();

    /** 下行命令近一分钟（created_at >= since）。 */
    @Select("SELECT COUNT(*) FROM device_command WHERE created_at >= #{since}")
    long countDeviceCommandsSince(@Param("since") Instant since);
}
