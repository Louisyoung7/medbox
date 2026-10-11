package com.medbox.server.mqtt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 兜底上行处理器：只记摘要日志（**本分支的验收入口就在这里** —— 文档 03 第 5 章的
 * "EMQX Dashboard 发一条 up/telemetry，后端日志能收到"）。
 *
 * <p><b>为什么要有兜底而不是直接丢弃</b>：每个 topic 的具体业务分散在不同分支
 * （见 {@link UpMessageHandler} 的列表），某个分支还没落地时，消息应当"看得见但不报错"，
 * 而不是静默消失 —— 否则联调时会误判成设备没发出去。
 *
 * <p>日志口径：上行可能含健康数据（遥测 / 服药），INFO 只打摘要
 * （topic / deviceId / 字节数），完整 payload 留 DEBUG。
 */
public class LoggingUpMessageHandler implements UpMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingUpMessageHandler.class);

    @Override
    public String supports() {
        return FALLBACK;
    }

    @Override
    public void handle(String deviceId, String topic, String payloadJson) {
        log.info("收到上行消息 deviceId={} topic={} bytes={}", deviceId, topic, payloadJson.length());
        log.debug("上行消息完整内容 topic={} payload={}", topic, payloadJson);
    }
}
