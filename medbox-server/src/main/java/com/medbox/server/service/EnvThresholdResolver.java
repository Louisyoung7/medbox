package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.domain.AlarmSetting;
import com.medbox.server.domain.EnvThreshold;
import com.medbox.server.mapper.AlarmSettingMapper;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 老人级环境阈值解析（feat/env-alarm，见文档 06 的 2.10 / 01 的 5.4）。
 *
 * <p><b>为什么单独成类</b>：告警判定在 MQTT / 事件监听上下文运行，无登录态，不能走带
 * {@code AccessService} 鉴权的 {@code AlarmSettingService}；故直接注入 {@code AlarmSettingMapper}
 * 按 {@code elder_id} 取，与 {@code MissToleranceResolver} 同一套路。
 *
 * <p>判定链（任一缺失即视为"未配置环境阈值"）：{@code alarm_setting} 行不存在 → empty；
 * 行存在但 {@code env_threshold} 为 null → empty；返回的非空 {@link EnvThreshold} 中，
 * 具体某指标上下限为 null 表示该指标不启用（由 {@code EnvAlarmService} 判定时跳过）。
 */
@Component
public class EnvThresholdResolver {

    private final AlarmSettingMapper alarmSettingMapper;

    public EnvThresholdResolver(AlarmSettingMapper alarmSettingMapper) {
        this.alarmSettingMapper = alarmSettingMapper;
    }

    /** 取某老人的环境阈值；未配置或为空返回 {@link Optional#empty()}。 */
    public Optional<EnvThreshold> resolve(String elderId) {
        if (elderId == null || elderId.isBlank()) {
            return Optional.empty();
        }
        AlarmSetting setting = alarmSettingMapper.selectOne(
                new LambdaQueryWrapper<AlarmSetting>().eq(AlarmSetting::getElderId, elderId));
        if (setting == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(setting.getEnvThreshold());
    }
}
