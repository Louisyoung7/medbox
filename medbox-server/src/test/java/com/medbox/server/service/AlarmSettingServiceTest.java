package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.security.Permission;
import com.medbox.server.domain.AlarmSetting;
import com.medbox.server.domain.EnvThreshold;
import com.medbox.server.dto.alarm.AlarmSettingRequest;
import com.medbox.server.dto.alarm.AlarmSettingResponse;
import com.medbox.server.mapper.AlarmSettingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AlarmSettingServiceTest {

    private static final String ELDER_ID = "u-1001";

    @Mock
    private AlarmSettingMapper settingMapper;

    @Mock
    private AccessService accessService;

    private AlarmSettingService service;

    @BeforeEach
    void setUp() {
        service = new AlarmSettingService(settingMapper, accessService);
    }

    @Test
    @DisplayName("get：未配置过 → 全字段 null")
    void 查询_无配置返回空() {
        when(settingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        AlarmSettingResponse resp = service.get(ELDER_ID);
        verify(accessService).require(ELDER_ID, Permission.READ_ALARM);
        assertNull(resp.missTolerateMin());
        assertNull(resp.envThreshold());
    }

    @Test
    @DisplayName("save：无现存记录 → insert，envThreshold 保留")
    void 保存_新建() {
        when(settingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        EnvThreshold env = new EnvThreshold(10, 30, 30, 70, 500);
        AlarmSettingRequest req = new AlarmSettingRequest(20, env);

        AlarmSettingResponse resp = service.save(ELDER_ID, req);

        verify(accessService).require(ELDER_ID, Permission.WRITE_ALARM_SETTINGS);
        verify(settingMapper).insert(any(AlarmSetting.class));
        verify(settingMapper, never()).updateById(any(AlarmSetting.class));
        assertEquals(20, resp.missTolerateMin());
        assertEquals(env, resp.envThreshold());
    }

    @Test
    @DisplayName("save：已存在记录仅覆盖请求字段（其余保持原值）")
    void 保存_合并不擦除() {
        AlarmSetting existing = new AlarmSetting();
        existing.setId(1L);
        existing.setElderId(ELDER_ID);
        existing.setMissTolerateMin(15);
        when(settingMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        // 只改 envThreshold，不动 missTolerateMin
        EnvThreshold env = new EnvThreshold(12, 28, null, null, 600);
        AlarmSettingRequest req = new AlarmSettingRequest(null, env);

        AlarmSettingResponse resp = service.save(ELDER_ID, req);

        verify(settingMapper, never()).insert(any(AlarmSetting.class));
        verify(settingMapper).updateById(any(AlarmSetting.class));
        assertEquals(15, resp.missTolerateMin()); // 保持原值
        assertEquals(env, resp.envThreshold());
    }
}
