package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.security.Permission;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.EnvSample;
import com.medbox.server.dto.env.EnvHistoryPoint;
import com.medbox.server.dto.env.EnvHistoryResponse;
import com.medbox.server.dto.env.EnvRealtimeResponse;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.EnvSampleMapper;
import com.medbox.server.mqtt.payload.TelemetryPayload;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 环境遥测（{@code feat/env-core}，见文档 02g / 06 的 2.8 / 03 第 3 章）。
 *
 * <p>两条链路共用本类：
 * <ol>
 *   <li><b>上行入库</b> {@link #ingest}：被 {@code TelemetryUpMessageHandler} 调用，
 *       按 topic 中的主控 deviceId 直接落 {@code env_sample}，不再有 sensorId / 父设备校验；</li>
 *   <li><b>查询</b> {@link #realtime} / {@link #history}：被 {@code EnvController} 与
 *       {@code DeviceService.detail} 调用，权限走 {@code AccessService#requireDevice}。</li>
 * </ol>
 *
 * <p>MQTT 匿名、信任 topic 中的 deviceId；{@code msgId} 去重已由 {@code MqttInboundGateway} 完成。
 */
@Service
public class EnvSampleService {

    private static final Logger log = LoggerFactory.getLogger(EnvSampleService.class);

    private static final int MAX_INTERVAL_MIN = 1440;
    private static final long MAX_SPAN_HOURS = 30L * 24;

    private final EnvSampleMapper envSampleMapper;
    private final DeviceMapper deviceMapper;
    private final AccessService accessService;
    private final Clock clock;

    @Autowired
    public EnvSampleService(EnvSampleMapper envSampleMapper,
                            DeviceMapper deviceMapper,
                            AccessService accessService) {
        this(envSampleMapper, deviceMapper, accessService, Clock.systemUTC());
    }

    public EnvSampleService(EnvSampleMapper envSampleMapper,
                            DeviceMapper deviceMapper,
                            AccessService accessService,
                            Clock clock) {
        this.envSampleMapper = envSampleMapper;
        this.deviceMapper = deviceMapper;
        this.accessService = accessService;
        this.clock = clock;
    }

    /**
     * 入库一条 {@code up/telemetry}：只认主控 deviceId，设备未登记时跳过。
     *
     * @return 已入库样本，供调用方发布 {@code EnvSampleRecordedEvent} 触发环境超标判定
     */
    public EnvSample ingest(String deviceId, TelemetryPayload payload) {
        Device device = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                .eq(Device::getDeviceId, deviceId));
        if (device == null || device.getElderId() == null || device.getElderId().isBlank()) {
            log.debug("telemetry 设备未登记或无归属老人，已跳过 deviceId={}", deviceId);
            return null;
        }

        EnvSample sample = new EnvSample();
        sample.setDeviceId(deviceId);
        sample.setElderId(device.getElderId());
        sample.setTemperature(toBigDecimal(payload.getTemperature()));
        sample.setHumidity(toBigDecimal(payload.getHumidity()));
        sample.setLux(toBigDecimal(payload.getLux()));
        sample.setSampleTime(resolveSampleTime(payload.getTs()));
        envSampleMapper.insert(sample);
        log.debug("env_sample 入库 deviceId={} sampleTime={}", deviceId, sample.getSampleTime());
        return sample;
    }

    /** 实时读数：合并「最新非空温湿度行」与「最新非空光照行」。 */
    public EnvRealtimeResponse realtime(String deviceId) {
        accessService.requireDevice(deviceId, Permission.READ_ENV);

        EnvSample th = envSampleMapper.selectLatestTempHumi(deviceId);
        EnvSample lx = envSampleMapper.selectLatestLux(deviceId);

        BigDecimal temperature = th != null ? th.getTemperature() : null;
        BigDecimal humidity = th != null ? th.getHumidity() : null;
        BigDecimal lux = lx != null ? lx.getLux() : null;

        OffsetDateTime sampleTime = null;
        if (th != null && th.getSampleTime() != null) {
            sampleTime = th.getSampleTime();
        }
        if (lx != null && lx.getSampleTime() != null
                && (sampleTime == null || lx.getSampleTime().isAfter(sampleTime))) {
            sampleTime = lx.getSampleTime();
        }
        return new EnvRealtimeResponse(temperature, humidity, lux, sampleTime);
    }

    /** 历史曲线：按主控 deviceId 查询，不再有传感器来源筛选。 */
    public EnvHistoryResponse history(String deviceId,
                                      OffsetDateTime from,
                                      OffsetDateTime to,
                                      Integer interval) {
        accessService.requireDevice(deviceId, Permission.READ_ENV);

        BizAssert.badRequestIf(from == null || to == null, "from/to 不能为空");
        BizAssert.badRequestIf(from.isAfter(to), "from 不能晚于 to");
        BizAssert.badRequestIf(interval != null && (interval < 1 || interval > MAX_INTERVAL_MIN),
                "interval 必须介于 1~" + MAX_INTERVAL_MIN + " 分钟");
        BizAssert.badRequestIf(ChronoUnit.HOURS.between(from, to) > MAX_SPAN_HOURS,
                "时间跨度不能超过 " + (MAX_SPAN_HOURS / 24) + " 天");

        List<EnvHistoryPoint> points = envSampleMapper.selectHistory(deviceId, from, to, interval);
        return new EnvHistoryResponse(points == null ? List.of() : points);
    }

    private BigDecimal toBigDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private OffsetDateTime resolveSampleTime(Long ts) {
        if (ts != null && ts > 0) {
            return OffsetDateTime.ofInstant(Instant.ofEpochMilli(ts), clock.getZone());
        }
        return OffsetDateTime.now(clock);
    }
}
