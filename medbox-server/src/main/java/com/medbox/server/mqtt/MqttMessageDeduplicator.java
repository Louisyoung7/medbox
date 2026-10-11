package com.medbox.server.mqtt;

import com.medbox.server.domain.MqttMessage;
import com.medbox.server.mapper.MqttMessageMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

/**
 * 上行消息 {@code msgId} 去重（见文档 03 第 1 章 / 06 的 2.15）。
 *
 * <p><b>做法是"先 INSERT 抢唯一键"，不是"先查再插"</b>：后者在并发下，同一条消息的两次投递
 * 可以同时通过 {@code SELECT} 检查然后都写进来，去重失效。
 * 唯一键冲突（PG 经 Spring 转成 {@link DuplicateKeyException}）即判定为重复。
 *
 * <p>为什么设备会重复发：断线重连后重發未完成确认的上行消息（协议允许），
 * 不去重就会重复写服药记录 / 重复生成告警。
 */
public class MqttMessageDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(MqttMessageDeduplicator.class);

    private final MqttMessageMapper mqttMessageMapper;
    private final MqttProperties props;
    private final Clock clock;

    public MqttMessageDeduplicator(MqttMessageMapper mqttMessageMapper, MqttProperties props) {
        this(mqttMessageMapper, props, Clock.systemUTC());
    }

    public MqttMessageDeduplicator(MqttMessageMapper mqttMessageMapper, MqttProperties props, Clock clock) {
        this.mqttMessageMapper = mqttMessageMapper;
        this.props = props;
        this.clock = clock;
    }

    /**
     * 抢占这条消息的处理权。
     *
     * @param msgId    上行消息 ID（{@code {deviceId}-{10位序号}}）
     * @param deviceId 归属设备
     * @param topic    完整上行 topic（排障用）
     * @return true = 首次出现，继续处理；false = 重复投递，调用方应直接丢弃
     */
    public boolean claim(String msgId, String deviceId, String topic) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        MqttMessage record = new MqttMessage();
        record.setMsgId(msgId);
        record.setDeviceId(deviceId);
        record.setTopic(topic);
        record.setReceivedAt(now);
        record.setExpiresAt(now.plusDays(props.getDedupRetentionDays()));
        try {
            return mqttMessageMapper.insert(record) > 0;
        } catch (DuplicateKeyException ex) {
            // 正常路径（设备重发），不是错误：DEBUG 级，避免日志刷屏
            log.debug("重复 msgId 已丢弃 msgId={} topic={}", msgId, topic);
            return false;
        }
    }
}
