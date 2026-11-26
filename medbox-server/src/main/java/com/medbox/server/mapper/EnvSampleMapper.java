package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.EnvSample;
import com.medbox.server.dto.env.EnvHistoryPoint;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code env_sample} 表的 Mapper（见文档 06 的 2.8）。
 *
 * <p>表与索引由 {@code V1__init_schema.sql} 建好；V28 收敛掉历史子设备数据后，
 * 所有查询统一按主控 {@code device_id} 过滤。历史分桶用
 * {@code floor(extract(epoch from sample_time)/(interval*60))} 聚合 {@code avg()}。
 */
@Mapper
public interface EnvSampleMapper extends BaseMapper<EnvSample> {

    /** 最新一条「温度非空」样本（兼得湿度），用于实时读数合并。 */
    @Select("SELECT * FROM env_sample WHERE temperature IS NOT NULL AND device_id = #{deviceId} "
            + "ORDER BY sample_time DESC LIMIT 1")
    EnvSample selectLatestTempHumi(@Param("deviceId") String deviceId);

    /** 最新一条「光照非空」样本。 */
    @Select("SELECT * FROM env_sample WHERE lux IS NOT NULL AND device_id = #{deviceId} "
            + "ORDER BY sample_time DESC LIMIT 1")
    EnvSample selectLatestLux(@Param("deviceId") String deviceId);

    /**
     * 历史曲线：{@code interval} 分钟非空时按桶 {@code avg()} 聚合，否则返回原始有序点。
     *
     * @param deviceId 药箱主控
     * @param from     起时间（含）
     * @param to       止时间（含）
     * @param interval 分桶粒度（分钟），为空表示原始点
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
            + " FROM env_sample WHERE device_id = #{deviceId}"
            + " AND sample_time &gt;= #{from} AND sample_time &lt;= #{to}"
            + "<if test='interval != null'> GROUP BY floor(extract(epoch from sample_time) / (#{interval} * 60)) ORDER BY 1</if>"
            + "<if test='interval == null'> ORDER BY sample_time</if>"
            + "</script>")
    List<EnvHistoryPoint> selectHistory(@Param("deviceId") String deviceId,
                                        @Param("from") OffsetDateTime from,
                                        @Param("to") OffsetDateTime to,
                                        @Param("interval") Integer interval);
}
