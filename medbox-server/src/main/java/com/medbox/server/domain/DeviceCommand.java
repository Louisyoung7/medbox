package com.medbox.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.medbox.server.config.CommandParamsJsonbTypeHandler;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 控制命令（{@code device_command} 表，V15 建，见文档 06 的 2.17）。
 *
 * <p><b>业务 ID {@code commandId}</b> 走 {@code device_command_biz_id_seq} 序列（前缀 {@code c-}），
 * 取号见 {@code DeviceCommandMapper.nextCommandId()}。
 *
 * <p><b>状态机</b>：{@code PENDING → SUCCESS / FAILED}（终态，只翻一次——更新条件带
 * {@code state='PENDING'}，受影响行数 &gt; 0 才算翻转，兼作迟到回执 / 重复结算的幂等）。
 *
 * <p><b>{@code msg_id} 持久化的原因</b>：回执结算按它匹配命令行，进程重启不丢关联
 * （{@code MqttPlanScheduleSync} 用内存映射是因为计划表没这列；自己的表自己加列更稳）。
 */
@TableName(value = "device_command", autoResultMap = true)
public class DeviceCommand {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务 ID，如 {@code c-1001}。 */
    private String commandId;

    /** 目标药箱主控。 */
    private String deviceId;

    /** 命令（见 {@code DeviceCmd}）。 */
    private String cmd;

    /** 命令参数（JSONB，如 {"durationSec":5}）。 */
    @TableField(typeHandler = CommandParamsJsonbTypeHandler.class)
    private Map<String, Object> params;

    /** 操作人（服务端取鉴权用户，不信客户端传值）。 */
    private String operatorId;

    /** 状态（见 {@code DeviceCommandState}）。 */
    private String state;

    /** 失败原因（回执 FAILED / 超时放弃 / 发布失败）。 */
    private String reason;

    /** 下行消息的 msgId（up/ack 按它结算）。 */
    private String msgId;

    private OffsetDateTime createdAt;

    /** 到达终态的时刻。 */
    private OffsetDateTime completedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCommandId() {
        return commandId;
    }

    public void setCommandId(String commandId) {
        this.commandId = commandId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
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

    public String getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(String operatorId) {
        this.operatorId = operatorId;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getMsgId() {
        return msgId;
    }

    public void setMsgId(String msgId) {
        this.msgId = msgId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(OffsetDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
