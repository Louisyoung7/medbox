package com.medbox.server.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 测试数据硬删的级联清理（见文档 02 第 10.2 / 10.3）。
 *
 * <p>应用层按依赖顺序调用各方法（先子后父），不依赖物理外键；每个方法只删「与某 userId 关联」的行。
 * 与用户无关的表（drug_manual / drug_manual_chunk / llm_config / mqtt_message / idempotency_record）不在范围。
 */
@Mapper
public interface AdminCleanupMapper {

    @Delete("DELETE FROM med_plan_item WHERE plan_id IN (SELECT plan_id FROM med_plan WHERE elder_id = #{userId})")
    int deleteMedPlanItems(@Param("userId") String userId);

    @Delete("DELETE FROM med_plan WHERE elder_id = #{userId}")
    int deleteMedPlans(@Param("userId") String userId);

    @Delete("DELETE FROM guardian_relation WHERE elder_id = #{userId} OR guardian_id = #{userId}")
    int deleteGuardianRelations(@Param("userId") String userId);

    @Delete("DELETE FROM device_event WHERE device_id IN (SELECT device_id FROM device WHERE elder_id = #{userId})")
    int deleteDeviceEvents(@Param("userId") String userId);

    @Delete("DELETE FROM device WHERE elder_id = #{userId}")
    int deleteDevices(@Param("userId") String userId);

    @Delete("DELETE FROM medicine_stock WHERE elder_id = #{userId}")
    int deleteMedicineStocks(@Param("userId") String userId);

    @Delete("DELETE FROM medicine WHERE owner_elder_id = #{userId}")
    int deleteMedicines(@Param("userId") String userId);

    @Delete("DELETE FROM med_record WHERE elder_id = #{userId} OR guardian_id = #{userId}")
    int deleteMedRecords(@Param("userId") String userId);

    @Delete("DELETE FROM capture WHERE elder_id = #{userId}")
    int deleteCaptures(@Param("userId") String userId);

    @Delete("DELETE FROM env_sample WHERE elder_id = #{userId}")
    int deleteEnvSamples(@Param("userId") String userId);

    @Delete("DELETE FROM alarm WHERE elder_id = #{userId}")
    int deleteAlarms(@Param("userId") String userId);

    @Delete("DELETE FROM alarm_setting WHERE elder_id = #{userId}")
    int deleteAlarmSettings(@Param("userId") String userId);

    @Delete("DELETE FROM refresh_token WHERE user_id = #{userId}")
    int deleteRefreshTokens(@Param("userId") String userId);

    @Delete("DELETE FROM \"user\" WHERE user_id = #{userId}")
    int deleteUser(@Param("userId") String userId);
}
