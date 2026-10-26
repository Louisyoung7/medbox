package com.medbox.server.domain.event;

import com.medbox.server.domain.EnvSample;

/**
 * 环境遥测样本入库后发布的事件（feat/env-alarm）。
 *
 * <p>{@link com.medbox.server.mqtt.TelemetryUpMessageHandler} 在
 * {@link com.medbox.server.service.EnvSampleService#ingest} 返回非 null 的样本后发布本事件；
 * {@code EnvAlarmService} 用普通 {@code @EventListener}（同步、不依赖事务）消费，做环境超标判定。
 *
 * <p>之所以走事件而非直接在 ingest 内联判定：保持 MQTT 上行"薄适配"分工（与
 * {@code DispenseRecordedEvent} → {@code wrong-drug-alarm} 同一范式），且 ingest 当前无事务，
 * 用普通 {@code @EventListener} 即可触发，无需给 env-core 引入事务边界。
 */
public class EnvSampleRecordedEvent {

    private final EnvSample sample;

    public EnvSampleRecordedEvent(EnvSample sample) {
        this.sample = sample;
    }

    public EnvSample getSample() {
        return sample;
    }
}
