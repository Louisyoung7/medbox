package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.domain.AlarmSetting;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.mapper.AlarmSettingMapper;
import org.springframework.stereotype.Component;

/**
 * 漏服容忍时长解析（见文档 06 §2.6.1）。
 *
 * <p><b>三级优先级</b>：计划级 {@code med_plan.miss_alarm_after_min} &gt; 老人级
 * {@code alarm_setting.miss_tolerate_min} &gt; 系统默认 15 分钟。非空且 &gt; 0 才生效，
 * 否则下探到下一级；老人级配置行缺失或字段为空 → 系统默认。
 *
 * <p>本解析器与 {@code feat/miss-alarm} 共用同一套口径，抽在此处避免两处实现漂移。
 */
@Component
public class MissToleranceResolver {

    private static final int DEFAULT_TOLERANCE_MIN = 15;

    private final AlarmSettingMapper alarmSettingMapper;

    public MissToleranceResolver(AlarmSettingMapper alarmSettingMapper) {
        this.alarmSettingMapper = alarmSettingMapper;
    }

    /** 漏服容忍时长（分钟）。 */
    public int resolveMinutes(MedPlan plan, String elderId) {
        if (plan != null && plan.getMissAlarmAfterMin() != null && plan.getMissAlarmAfterMin() > 0) {
            return plan.getMissAlarmAfterMin();
        }
        if (elderId != null && !elderId.isBlank()) {
            AlarmSetting setting = alarmSettingMapper.selectOne(
                    new LambdaQueryWrapper<AlarmSetting>().eq(AlarmSetting::getElderId, elderId));
            if (setting != null && setting.getMissTolerateMin() != null && setting.getMissTolerateMin() > 0) {
                return setting.getMissTolerateMin();
            }
        }
        return DEFAULT_TOLERANCE_MIN;
    }
}
