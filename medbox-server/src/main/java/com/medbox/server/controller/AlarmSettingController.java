package com.medbox.server.controller;

import com.medbox.server.dto.R;
import com.medbox.server.dto.alarm.AlarmSettingRequest;
import com.medbox.server.dto.alarm.AlarmSettingResponse;
import com.medbox.server.service.AlarmSettingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 老人级告警阈值配置接口（见文档 02 第 8 章、07 的 {@code feat/alarm-core}）。
 *
 * <p>只做参数绑定与 {@code R<T>} 包装：权限（读 {@code READ_ALARM} / 写
 * {@code WRITE_ALARM_SETTINGS}）+ 按老人 upsert 全在 {@link AlarmSettingService}。
 *
 * <p>两个端点：查询当前配置、upsert 配置（临期提前期 / 漏服容忍 / 环境上下限）。
 */
@RestController
@RequestMapping("/api/v1/alarm-settings")
public class AlarmSettingController {

    private final AlarmSettingService alarmSettingService;

    public AlarmSettingController(AlarmSettingService alarmSettingService) {
        this.alarmSettingService = alarmSettingService;
    }

    /** GET /alarm-settings?elderId=：查询当前老人级告警阈值配置。 */
    @GetMapping
    public R<AlarmSettingResponse> get(@RequestParam("elderId") String elderId) {
        return R.ok(alarmSettingService.get(elderId));
    }

    /** PUT /alarm-settings?elderId=：upsert 老人级告警阈值（仅 FAMILY 可写）。 */
    @PutMapping
    public R<AlarmSettingResponse> save(@RequestParam("elderId") String elderId,
                                        @RequestBody AlarmSettingRequest request) {
        return R.ok(alarmSettingService.save(elderId, request));
    }
}
