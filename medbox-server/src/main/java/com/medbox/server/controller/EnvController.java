package com.medbox.server.controller;

import com.medbox.server.dto.R;
import com.medbox.server.dto.env.EnvHistoryResponse;
import com.medbox.server.dto.env.EnvRealtimeResponse;
import com.medbox.server.service.EnvSampleService;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 环境监测接口（见文档 02 第 9 章）。
 *
 * <p>只做参数绑定与 {@code R<T>} 包装，<b>权限判定全在 {@link EnvSampleService}</b>
 * （与 {@code DeviceController} 同一形态）：两个接口均经
 * {@code AccessService.requireDevice(deviceId, READ_ENV)}，越权 / 设备查不到一律 {@code 40301}
 * （与设备类接口同口径，防设备编号枚举）。未登录由 {@code AuthInterceptor} 统一兜 {@code 40101}。
 *
 * <p><b>本分支不告警、不推 WS</b>：环境超标告警归 {@code feat/env-alarm}（阈值走
 * {@code PUT /alarm-settings} 的 {@code envThreshold}）。历史查询的 {@code from}/{@code to}
 * 必填、{@code interval} 可选（分钟，分桶聚合），边界校验在 Service 层。
 */
@RestController
@RequestMapping("/api/v1/env")
public class EnvController {

    private final EnvSampleService envSampleService;

    public EnvController(EnvSampleService envSampleService) {
        this.envSampleService = envSampleService;
    }

    /** 实时读数：最新温/湿/光与采样时间（可按传感器过滤）。 */
    @GetMapping("/realtime")
    public R<EnvRealtimeResponse> realtime(@RequestParam("deviceId") String deviceId,
                                           @RequestParam(value = "sensorId", required = false) String sensorId) {
        return R.ok(envSampleService.realtime(deviceId, sensorId));
    }

    /** 历史曲线：时间有序读数点（可选 interval 分钟分桶聚合，可按传感器查）。 */
    @GetMapping("/history")
    public R<EnvHistoryResponse> history(@RequestParam("deviceId") String deviceId,
                                         @RequestParam(value = "sensorId", required = false) String sensorId,
                                         @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                                         @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
                                         @RequestParam(value = "interval", required = false) Integer interval) {
        return R.ok(envSampleService.history(deviceId, sensorId, from, to, interval));
    }
}
