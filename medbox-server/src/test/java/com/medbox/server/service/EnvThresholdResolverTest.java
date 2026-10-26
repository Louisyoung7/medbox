package com.medbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.domain.AlarmSetting;
import com.medbox.server.domain.EnvThreshold;
import com.medbox.server.mapper.AlarmSettingMapper;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link EnvThresholdResolver} 纯单测：仅验证"按 elderId 取环境阈值"的分支，
 * 阈值越界判定在 {@link EnvAlarmServiceTest} 覆盖。
 */
class EnvThresholdResolverTest {

    private final AlarmSettingMapper alarmSettingMapper = mock(AlarmSettingMapper.class);
    private final EnvThresholdResolver resolver = new EnvThresholdResolver(alarmSettingMapper);

    @Test
    @DisplayName("alarm_setting 行不存在 → empty")
    void noSetting() {
        when(alarmSettingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        assertThat(resolver.resolve("u-1")).isEmpty();
    }

    @Test
    @DisplayName("env_threshold 为 null → empty")
    void thresholdNull() {
        AlarmSetting setting = new AlarmSetting();
        setting.setElderId("u-1");
        setting.setEnvThreshold(null);
        when(alarmSettingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);
        assertThat(resolver.resolve("u-1")).isEmpty();
    }

    @Test
    @DisplayName("行存在且 env_threshold 非空 → 返回阈值")
    void thresholdPresent() {
        EnvThreshold t = new EnvThreshold(10, 30, 40, 70, 600);
        AlarmSetting setting = new AlarmSetting();
        setting.setEnvThreshold(t);
        when(alarmSettingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(setting);
        Optional<EnvThreshold> got = resolver.resolve("u-1");
        assertThat(got).isPresent();
        assertThat(got.get().tempMax()).isEqualTo(30);
    }

    @Test
    @DisplayName("elderId 为空 → empty 且不查库")
    void blankElderId() {
        assertThat(resolver.resolve("  ")).isEmpty();
        verify(alarmSettingMapper, never()).selectOne(any());
    }
}
