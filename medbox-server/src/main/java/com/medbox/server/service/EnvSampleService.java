package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.security.Permission;
import com.medbox.server.service.AccessService;
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
 * 环境遥测（{@code feat/env-core}，见文档 02 第 9 章 / 06 的 2.8 / 03 第 3 章）。
 *
 * <p>两条链路共用本类：
 * <ol>
 *   <li><b>上行入库</b> {@link #ingest}：被 {@code TelemetryUpMessageHandler} 调用，按
 *       {@code sensorId} 定位子设备后逐条落 {@code env_sample}（温湿度一行、光照一行，
 *       其余数值列留 {@code NULL}）；</li>
 *   <li><b>查询</b> {@link #realtime} / {@link #history}：被 {@link com.medbox.server.controller.EnvController}
 *       与 {@link DeviceService#detail} 调用，权限走 {@link AccessService#requireDevice}
 *       （{@link Permission#READ_ENV}，四角色可读），越权/查不到一律 {@code 40301}。</li>
 * </ol>
 *
 * <p><b>与 dispense / sensor-heartbeat 同源的口径</b>：MQTT 匿名、信任 topic 中的 deviceId，
 * ingest 不查权限；单条子设备解析失败只记 warn，不影响同帧其余；{@code msgId} 去重已由
 * {@code MqttInboundGateway} 完成，本类不重复做。
 *
 * <p><b>告警与 WS 不在本类做</b>：环境超标判定归 {@code feat/env-alarm}——{@link #ingest} 入库后返回
 * 该样本，由 {@code TelemetryUpMessageHandler} 发布 {@code EnvSampleRecordedEvent} 触发判定。
 */
 @Service
 public class EnvSampleService {

    private static final Logger log = LoggerFactory.getLogger(EnvSampleService.class);

    /** 历史分桶粒度上限（分钟），防止过度聚合请求。 */
    private static final int MAX_INTERVAL_MIN = 1440;
    /** 历史查询最大跨度（小时 = 30 天）。 */
    private static final long MAX_SPAN_HOURS = 30L * 24;

    private final EnvSampleMapper envSampleMapper;
    private final DeviceMapper deviceMapper;
    private final AccessService accessService;
    private final Clock clock;

    /** 生产用构造器（双构造器必须显式 {@code @Autowired}，同 {@code SensorStatusService}）。 */
    @Autowired
    public EnvSampleService(EnvSampleMapper envSampleMapper,
                           DeviceMapper deviceMapper,
                           AccessService accessService) {
        this(envSampleMapper, deviceMapper, accessService, Clock.systemUTC());
    }

    /** 测试用构造器：注入固定时钟与可打桩的 Mapper。 */
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
     * 入库一条 {@code up/telemetry}。幂等由网关 {@code msgId} 保证，此处只做归属校验与落库。
     *
     * @param mainDeviceId 上报的主控（topic 第 3 层）
     * @param payload      已反序列化的遥测负载
     * @return 已入库的样本（归属校验失败 / 未登记 / 不属于该主控时返回 {@code null}），
     *         供调用方发布 {@code EnvSampleRecordedEvent} 触发环境超标判定
     */
    public EnvSample ingest(String mainDeviceId, TelemetryPayload payload) {
        String sensorId = payload.getSensorId();
        if (sensorId == null || sensorId.isBlank()) {
            log.warn("telemetry 缺少 sensorId，已跳过 mainDeviceId={}", mainDeviceId);
            return null;
        }
        Device sensor = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                .eq(Device::getDeviceId, sensorId));
        if (sensor == null) {
            // 未登记传感器（本期匿名连接，固件可能上报未登记子设备）是常态，不刷 warn
            log.debug("telemetry 携带未登记传感器，已跳过 mainDeviceId={} sensorId={}", mainDeviceId, sensorId);
            return null;
        }
        if (sensor.getParentDeviceId() == null || !sensor.getParentDeviceId().equals(mainDeviceId)) {
            // 不属于该主控：跳过，不建脏行（与 device-sensor 同口径）
            log.warn("telemetry 传感器不属于该主控，已跳过 mainDeviceId={} sensorId={} parent={}",
                    mainDeviceId, sensorId, sensor.getParentDeviceId());
            return null;
        }

        EnvSample sample = new EnvSample();
        sample.setDeviceId(sensorId);
        sample.setGatewayDeviceId(mainDeviceId);
        sample.setElderId(sensor.getElderId());
        sample.setTemperature(toBigDecimal(payload.getTemperature()));
        sample.setHumidity(toBigDecimal(payload.getHumidity()));
        sample.setLux(toBigDecimal(payload.getLux()));
        sample.setSampleTime(resolveSampleTime(payload.getTs()));
        envSampleMapper.insert(sample);
        log.debug("env_sample 入库 mainDeviceId={} sensorId={} sampleTime={}",
                mainDeviceId, sensorId, sample.getSampleTime());
        return sample;
    }

    /**
     * 实时读数：合并「最新非空温度/湿度行」与「最新非空光照行」。
     *
     * @param sensorId 可选；非空时只查该传感器
     */
    public EnvRealtimeResponse realtime(String deviceId, String sensorId) {
        accessService.requireDevice(deviceId, Permission.READ_ENV);

        EnvSample th = envSampleMapper.selectLatestTempHumi(deviceId, sensorId);
        EnvSample lx = envSampleMapper.selectLatestLux(deviceId, sensorId);

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

    /**
     * 历史曲线。
     *
     * @param interval 分桶粒度（分钟），为空返回原始有序点
     */
    public EnvHistoryResponse history(String deviceId,
                                     String sensorId,
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

        List<EnvHistoryPoint> points = envSampleMapper.selectHistory(deviceId, sensorId, from, to, interval);
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
