package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.security.Permission;
import com.medbox.server.domain.AlarmSetting;
import com.medbox.server.dto.alarm.AlarmSettingRequest;
import com.medbox.server.dto.alarm.AlarmSettingResponse;
import com.medbox.server.mapper.AlarmSettingMapper;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;

/**
 * 老人级告警阈值配置（{@code feat/alarm-core}，见文档 02 第 8 章、06 的 2.10）。
 *
 * <p><b>权限</b>：查询 = {@code READ_ALARM}（四角色）；写（upsert）=
 * {@code WRITE_ALARM_SETTINGS}（仅 FAMILY，监护人代老人配置，镜像 {@code CONFIG_LLM_KEY} 口径）。
 *
 * <p><b>每老人一份</b>：{@code alarm_setting.elder_id} 为 UNIQUE，无则插入、有则更新，落库即幂等。
 * 请求字段可选，仅提供的字段覆盖原值（未提供的保持原值），便于前端只改某一项而不擦掉其余配置。
 */
@Service
public class AlarmSettingService {

    private final AlarmSettingMapper settingMapper;
    private final AccessService accessService;

    public AlarmSettingService(AlarmSettingMapper settingMapper, AccessService accessService) {
        this.settingMapper = settingMapper;
        this.accessService = accessService;
    }

    /** GET /alarm-settings?elderId=：返回当前配置；未配置过则返回全 null（前端按系统默认展示）。 */
    public AlarmSettingResponse get(String elderId) {
        accessService.require(elderId, Permission.READ_ALARM);
        AlarmSetting setting = settingMapper.selectOne(
                new LambdaQueryWrapper<AlarmSetting>().eq(AlarmSetting::getElderId, elderId));
        return AlarmSettingResponse.from(setting);
    }

    /**
     * PUT /alarm-settings?elderId=：按老人 upsert 阈值配置（临期提前期 / 漏服容忍 / 环境上下限）。
     * 仅提供的字段覆盖原值。
     */
    public AlarmSettingResponse save(String elderId, AlarmSettingRequest request) {
        accessService.require(elderId, Permission.WRITE_ALARM_SETTINGS);

        AlarmSetting existing = settingMapper.selectOne(
                new LambdaQueryWrapper<AlarmSetting>().eq(AlarmSetting::getElderId, elderId));
        AlarmSetting entity = existing == null ? new AlarmSetting() : existing;
        entity.setElderId(elderId);

        if (request.expiringAheadDays() != null) {
            entity.setExpiringAheadDays(request.expiringAheadDays());
        }
        if (request.missTolerateMin() != null) {
            entity.setMissTolerateMin(request.missTolerateMin());
        }
        if (request.envThreshold() != null) {
            entity.setEnvThreshold(request.envThreshold());
        }
        entity.setUpdatedAt(OffsetDateTime.now());

        if (existing == null) {
            settingMapper.insert(entity);
        } else {
            settingMapper.updateById(entity);
        }
        return AlarmSettingResponse.from(entity);
    }
}
