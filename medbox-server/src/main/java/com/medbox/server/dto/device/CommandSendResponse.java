package com.medbox.server.dto.device;

/**
 * 命令受理响应（异步下发：只保证"受理"，终态由 up/ack 翻转，
 * 前端经 {@code GET /devices/{id}/events} 的 COMMAND 事件观察，见文档 02 第 4 章）。
 */
public class CommandSendResponse {

    private final String commandId;
    private final String state;

    public CommandSendResponse(String commandId, String state) {
        this.commandId = commandId;
        this.state = state;
    }

    public String getCommandId() {
        return commandId;
    }

    public String getState() {
        return state;
    }
}
