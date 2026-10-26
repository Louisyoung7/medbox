package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.EnvSample;
import com.medbox.server.dto.env.EnvHistoryPoint;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code env_sample} 表的 Mapper（见文档 06 的 2.8）。
 *
 * <p>表与索引由 {@code V1__init_schema.sql} 建好，本分支不引入新 Flyway 迁移
 * （无业务号序列，规避 AGENTS 3.2 的 out-of-order 风险）。
 *
 * <p><b>动态 SQL 要点</b>：{@code sensorId} 非空时按传感器自身 {@code device_id} 筛，
 * 否则按药箱 {@code gateway_device_id} 筛（{@code <choose>}）；历史分桶时按
 * {@code floor(extract(epoch from sample_time)/(interval*60))} 聚合 {@code avg()}，
 * 否则返回原始有序点。列名统一别名成驼峰，避免依赖全局 mapUnderscoreToCamelCase 配置。
 */
@Mapper
public interface EnvSampleMapper extends BaseMapper<EnvSample> {

    /**
     * 最新一条「温度非空」样本（兼得湿度），用于实时读数合并。
     */
    @Select("<script>"
            + "SELECT * FROM env_sample WHERE temperature IS NOT NULL "
            + "<choose>"
            + "  <when test='sensorId != null'>AND device_id = #{sensorId}</when>"
            + "  <otherwise>AND gateway_device_id = #{gatewayDeviceId}</otherwise>"
            + "</choose>"
            + "ORDER BY sample_time DESC LIMIT 1"
            + "</script>")
    EnvSample selectLatestTempHumi(@Param("gatewayDeviceId") String gatewayDeviceId,
                                   @Param("sensorId") String sensorId);

    /**
     * 最新一条「光照非空」样本。
     */
    @Select("<script>"
            + "SELECT * FROM env_sample WHERE lux IS NOT NULL "
            + "<choose>"
            + "  <when test='sensorId != null'>AND device_id = #{sensorId}</when>"
            + "  <otherwise>AND gateway_device_id = #{gatewayDeviceId}</otherwise>"
            + "</choose>"
            + "ORDER BY sample_time DESC LIMIT 1"
            + "</script>")
    EnvSample selectLatestLux(@Param("gatewayDeviceId") String gatewayDeviceId,
                              @Param("sensorId") String sensorId);

    /**
     * 历史曲线：{@code interval} 分钟非空时按桶 {@code avg()} 聚合，否则返回原始有序点。
     *
     * @param gatewayDeviceId 药箱主控（{@code sensorId} 为空时作为筛选维度）
     * @param sensorId        传感器自身编号（非空时优先按它筛）
     * @param from           起时间（含）
     * @param to             止时间（含）
     * @param interval       分桶粒度（分钟），为空表示原始点
     */
    @Select("<script>"
            + "SELECT "
            + "<choose>"
            + "  <when test='interval != null'>"
            + "    to_timestamp(floor(extract(epoch from sample_time) / (#{interval} * 60)) * (#{interval} * 60)) AS sampleTime, "
            + "    avg(temperature) AS temperature, avg(humidity) AS humidity, avg(lux) AS lux"
            + "  </when>"
            + "  <otherwise>sample_time AS sampleTime, temperature, humidity, lux</otherwise>"
            + "</choose>"
            + " FROM env_sample WHERE "
            + "<choose>"
            + "  <when test='sensorId != null'>device_id = #{sensorId}</when>"
            + "  <otherwise>gateway_device_id = #{gatewayDeviceId}</otherwise>"
            + "</choose>"
            + " AND sample_time &gt;= #{from} AND sample_time &lt;= #{to}"
            + "<if test='interval != null'> GROUP BY floor(extract(epoch from sample_time) / (#{interval} * 60)) ORDER BY 1</if>"
            + "<if test='interval == null'> ORDER BY sample_time</if>"
            + "</script>")
    java.util.List<EnvHistoryPoint> selectHistory(@Param("gatewayDeviceId") String gatewayDeviceId,
                                                  @Param("sensorId") String sensorId,
                                                  @Param("from") OffsetDateTime from,
                                                  @Param("to") OffsetDateTime to,
                                                  @Param("interval") Integer interval);
}
