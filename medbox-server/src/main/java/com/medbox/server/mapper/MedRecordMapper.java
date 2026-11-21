package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.MedRecord;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * {@code med_record} 表的 Mapper（见文档 06 的 2.7）。
 *
 * <p><b>取号</b>：用独立序列 {@code med_record_biz_id_seq}（V10 建），不碰 {@code id BIGSERIAL}
 * 自动序列（同 medicine-core / plan-core 同一个坑：同名会被 {@code IF NOT EXISTS} 静默跳过）。
 */
@Mapper
public interface MedRecordMapper extends BaseMapper<MedRecord> {

    @Select("SELECT 'mr-' || nextval('med_record_biz_id_seq')")
    String nextRecordId();

    /**
     * 幂等插入服药记录：命中唯一索引 {@code uk_med_record_cell_plan_time} 时**不写、不抛异常**，
     * 返回影响行数 0（调用方据此判"幂等命中"）。
     *
     * <p>为什么不用「先 insert 再 catch DuplicateKeyException + setRollbackOnly」：
     * 设备上报链路 {@code CaptureService.upload} 与本方法共享同一事务（REQUIRED），
     * setRollbackOnly 会把外层事务一起标记回滚，导致外层提交时抛 UnexpectedRollbackException、
     * 连 capture 记录都落不了库。ON CONFLICT DO NOTHING 不破坏事务，是幂等写入的正确姿势。
     */
    @Insert("INSERT INTO med_record (record_id, elder_id, plan_id, cell_no, capture_id, "
            + "plan_time, actual_time, on_time, source, taken_result, device_id, created_at) "
            + "VALUES (#{recordId}, #{elderId}, #{planId}, #{cellNo}, #{captureId}, "
            + "#{planTime}, #{actualTime}, #{onTime}, #{source}, #{takenResult}, #{deviceId}, #{createdAt}) "
            + "ON CONFLICT (plan_id, cell_no, plan_time) "
            + "WHERE plan_id IS NOT NULL AND cell_no IS NOT NULL AND plan_time IS NOT NULL "
            + "DO NOTHING")
    int insertIfAbsent(MedRecord record);

    /**
     * 取某计划在时间窗内的全部服药记录（feat/miss-alarm 判定槽是否缺服）。
     *
     * <p>用 {@code idx_med_record_plan_cell_time (plan_id, cell_no, plan_time)} 索引（V22 建）；
     * 返回后由 Service 按 {@code (cell_no, plan_time.toInstant())} 归一分组，与扫描槽比对
     * （注意落库后 PG 会把 {@code TIMESTAMPTZ} 归一为 UTC 瞬时，必须比瞬时而非 OffsetDateTime）。
     */
    @Select("SELECT * FROM med_record WHERE plan_id = #{planId} "
            + "AND plan_time >= #{from} AND plan_time <= #{to}")
    List<MedRecord> selectByPlanAndTimeRange(String planId, java.time.OffsetDateTime from, java.time.OffsetDateTime to);

    /**
     * 判某计划某格某槽是否已服药（feat/plan-reminder 的"已服跳过"拦截）。
     *
     * <p>按 {@code cell_no} + {@code plan_time} 的<b>瞬时</b>等值计数（落库后 PG 已归一为 UTC，
     * 传参统一用 UTC 偏移）。该槽有任一服药记录（无论 TAKEN / UNTAKEN）即算"已服"，不再提醒。
     * 走 {@code idx_med_record_plan_cell_time (plan_id, cell_no, plan_time)} 索引。
     */
    @Select("SELECT COUNT(*) FROM med_record WHERE plan_id = #{planId} AND cell_no = #{cellNo} "
            + "AND plan_time = #{planTime}")
    int countTaken(@Param("planId") String planId, @Param("cellNo") Integer cellNo,
                   @Param("planTime") OffsetDateTime planTime);
}
