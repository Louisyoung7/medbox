package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.MqttMessage;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code mqtt_message} 表（上行去重）的 Mapper（见文档 06 的 2.15）。
 *
 * <p><b>去重的正确姿势是 {@code insert} 抢唯一键</b>，不是"先查再插"：
 * 后者在并发下，两条重复消息可以同时通过 {@code SELECT} 检查，然后都写进去。
 *
 * <p>自定义 SQL 直接写注解，不引 mapper XML —— 本项目没有 XML 映射目录。
 */
@Mapper
public interface MqttMessageMapper extends BaseMapper<MqttMessage> {

    /**
     * 删除过期的去重记录（保留窗口外的记录对"设备重发"已经没有意义）。
     *
     * @param now 当前时间（UTC）
     * @return 删除行数
     */
    @Delete("DELETE FROM mqtt_message WHERE expires_at < #{now}")
    int deleteExpired(@Param("now") OffsetDateTime now);
}
