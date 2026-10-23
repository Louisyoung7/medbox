package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.MedPlan;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * {@code med_plan} 表的 Mapper（见文档 06 的 2.6）。
 *
 * <p><b>取号</b>：用独立序列 {@code med_plan_biz_id_seq}（V9 建），不碰 {@code id BIGSERIAL}
 * 自动序列（同 medicine-core 的 medicine_biz_id_seq 同一个坑）。
 */
@Mapper
public interface MedPlanMapper extends BaseMapper<MedPlan> {

    @Select("SELECT 'p-' || nextval('med_plan_biz_id_seq')")
    String nextPlanId();

    /**
     * 取本次漏服扫描范围内的计划（feat/miss-alarm）。
     *
     * <p>判定口径（文档 06 §2.9 / 用户确认）：只判 {@code status='ENABLED'} 且
     * {@code sync_state <> 'SYNC_FAILED'} 的计划——停用的不判、从未成功下发的设备
     * （SYNC_FAILED）不会提醒老人，判漏服不公平。{@code sync_state} 默认 PENDING 的计划
     * <b>照常判定</b>（本地演示/未跑同步的测试数据如果不判，永远看不到告警）。
     */
    @Select("SELECT * FROM med_plan WHERE status = 'ENABLED' AND sync_state <> 'SYNC_FAILED'")
    List<MedPlan> selectDueForMissScan();
}
