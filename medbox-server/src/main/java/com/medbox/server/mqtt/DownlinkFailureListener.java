package com.medbox.server.mqtt;

/**
 * 下行失败的兜底出口（**扩展点**）。
 *
 * <p>文档 03 第 1 章要求"3 次仍无回执则标记下发失败并生成告警"，但告警实体
 * （{@code alarm} 表与告警类型）属 {@code feat/alarm-core}，本分支不越界；
 * 因此这里留出 hook：默认实现只打日志，将来由告警分支换成写库 + WS 推送即可。
 */
public interface DownlinkFailureListener {

    /**
     * @param deviceId 目标设备
     * @param topic    下行 topic（{@code down/schedule} / {@code down/command}）
     * @param msgId    失败的下行消息 ID
     * @param reason   失败原因（超时为固定文案；设备明确失败时为 {@code up/ack.reason}）
     */
    void onFailed(String deviceId, String topic, String msgId, String reason);
}
