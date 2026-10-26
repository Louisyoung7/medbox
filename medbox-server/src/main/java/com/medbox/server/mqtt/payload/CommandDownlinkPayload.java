package com.medbox.server.mqtt.payload;

import java.util.Map;

/**
 * 下行控制命令载荷（{@code down/command}，见文档 03 第 2~3 章）。
 * 只含业务字段；{@code msgId} / {@code needAck} 由 {@code DownlinkPublisher} 的信封注入。
 */
public class CommandDownlinkPayload {

    private String cmd;
    private Map<String, Object> params;

    public CommandDownlinkPayload() {
    }

    public CommandDownlinkPayload(String cmd, Map<String, Object> params) {
        this.cmd = cmd;
        this.params = params;
    }

    public String getCmd() {
        return cmd;
    }

    public void setCmd(String cmd) {
        this.cmd = cmd;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }
}
