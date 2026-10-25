package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.MedRecord;
import java.time.OffsetDateTime;
import java.util.List;
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
     * 取某计划在时间窗内的全部服药记录（feat/miss-alarm 判定槽是否缺服）。
     *
     * <p>用 {@code idx_med_record_plan_time (plan_id, plan_time)} 索引（V10 建）；
     * 返回后由 Service 按 {@code plan_time} 的瞬时（{@code toInstant()}）归一分组，
     * 与扫描槽比对（注意落库后 PG 会把 {@code TIMESTAMPTZ} 归一为 UTC 瞬时，必须比瞬时而非 OffsetDateTime）。
     */
    @Select("SELECT * FROM med_record WHERE plan_id = #{planId} "
            + "AND plan_time >= #{from} AND plan_time <= #{to}")
    List<MedRecord> selectByPlanAndTimeRange(String planId, java.time.OffsetDateTime from, java.time.OffsetDateTime to);

    /**
     * 判某计划某槽是否已服药（feat/plan-reminder 的"已服跳过"拦截）。
     *
     * <p>按 {@code plan_time} 的<b>瞬时</b>等值计数（落库后 PG 已归一为 UTC，传参统一用 UTC 偏移）。
     * 该 slot 有任何服药记录（哪怕只服了其中一种药）即算"已服"，不再提醒。走
     * {@code idx_med_record_plan_time (plan_id, plan_time)} 索引。
     */
    @Select("SELECT COUNT(*) FROM med_record WHERE plan_id = #{planId} AND plan_time = #{planTime}")
    int countTaken(@Param("planId") String planId, @Param("planTime") OffsetDateTime planTime);
}
