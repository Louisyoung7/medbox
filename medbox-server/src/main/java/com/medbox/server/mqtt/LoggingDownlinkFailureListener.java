package com.medbox.server.mqtt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 下行失败的默认处理：只记日志（见 {@link DownlinkFailureListener} 的说明）。
 *
 * <p>{@code feat/alarm-core} 落地后换成写 {@code alarm} + WS 推送的实现即可，调用方不用动。
 */
public class LoggingDownlinkFailureListener implements DownlinkFailureListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingDownlinkFailureListener.class);

    @Override
    public void onFailed(String deviceId, String topic, String msgId, String reason) {
        log.warn("下行下发失败 deviceId={} topic={} msgId={} reason={}", deviceId, topic, msgId, reason);
    }
}
