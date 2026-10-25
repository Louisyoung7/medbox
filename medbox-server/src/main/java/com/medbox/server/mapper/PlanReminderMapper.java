package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.PlanReminder;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code plan_reminder} 表 Mapper（feat/plan-reminder，见文档 06 的 2.6.x）。
 *
 * <p>仅作去重账本：插入靠 {@code dedup_key} 唯一索引冲突判定"是否已提醒过"，冲突由 Service 捕获
 * {@link org.springframework.dao.DuplicateKeyException} 后跳过。无独立业务号序列。
 */
@Mapper
public interface PlanReminderMapper extends BaseMapper<PlanReminder> {
}
