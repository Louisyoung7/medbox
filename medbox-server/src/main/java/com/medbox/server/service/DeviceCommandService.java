package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.common.security.AccessIdentity;
import com.medbox.server.common.security.Permission;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.DeviceCommand;
import com.medbox.server.domain.enums.DeviceCmd;
import com.medbox.server.domain.enums.DeviceCommandState;
import com.medbox.server.domain.enums.DeviceEventType;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.dto.device.CommandSendRequest;
import com.medbox.server.dto.device.CommandSendResponse;
import com.medbox.server.mapper.DeviceCommandMapper;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mqtt.DownlinkPublisher;
import com.medbox.server.mqtt.PendingAckRegistry;
import com.medbox.server.mqtt.payload.CommandDownlinkPayload;
import jakarta.annotation.PostConstruct;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 控制命令下发（{@code feat/device-command}，见文档 02 第 4 章 / 03 第 2~3 章）。
 *
 * <p><b>链路</b>：REST 受理 → 落 {@code device_command}（PENDING）→ {@link DownlinkPublisher}
 * 转 MQTT QoS1（{@code needAck=true}，5s 超时重发、最多 3 次由 {@link PendingAckRegistry} 兜底）
 * → 设备回执 {@code up/ack} 或超时放弃 → {@link #onComplete} 按持久化的 {@code msg_id}
 * 把行翻成 SUCCESS / FAILED 终态。
 *
 * <p><b>判定表（07 清单本分支规则，顺序即优先级）</b>：
 * 设备不存在 / 越权 / 传子设备编号 / 老人本人 → 40301；关系 PENDING → 40302；
 * cmd 不在白名单 → 40001；<b>设备离线 → 40901</b>（小程序 mp-device-extra 已按此提示
 * "设备离线，无法下发"）；MQTT 未启用 / 发布失败 → 50000（未启用时不落库，与 devtools
 * 设备注册的口径一致）。
 *
 * <p><b>事件流水契约（硬约定，前端已按此解析）</b>：{@code device_event} 的 COMMAND 行
 * {@code detail} 是 JSON：{@code {"commandId","cmd","state"}}（FAILED 时多带 {@code reason}），
 * 下发时写 PENDING 一条、终态时再写一条，见 02 §4.0 第 2 条。
 *
 * <p><b>明确不做</b>：不生成告警（02 告警枚举无对应类型，失败在事件流水可见）；
 * 不提供命令状态查询 REST / WS 帧（契约如此，后续要加先改 02 / 04）。
 */
@Service
public class DeviceCommandService implements PendingAckRegistry.DownlinkCompletionListener {

    private static final Logger log = LoggerFactory.getLogger(DeviceCommandService.class);

    private final DeviceMapper deviceMapper;
    private final DeviceCommandMapper commandMapper;
    private final DeviceEventService deviceEventService;
    private final AccessService accessService;
    private final ObjectMapper objectMapper;

    /** 下行发布器；MQTT 禁用时为 null（{@code @Autowired(required = false)}）。 */
    @Autowired(required = false)
    private DownlinkPublisher downlinkPublisher;

    /** 待回执账本；MQTT 禁用时为 null。 */
    @Autowired(required = false)
    private PendingAckRegistry registry;

    public DeviceCommandService(DeviceMapper deviceMapper,
                                DeviceCommandMapper commandMapper,
                                DeviceEventService deviceEventService,
                                AccessService accessService,
                                ObjectMapper objectMapper) {
        this.deviceMapper = deviceMapper;
        this.commandMapper = commandMapper;
        this.deviceEventService = deviceEventService;
        this.accessService = accessService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        if (registry != null) {
            registry.addCompletionListener(this);
        }
    }

    /**
     * 受理一条控制命令：权限与参数校验 → 落 PENDING 行 → QoS1 下发。
     * 判定顺序与错误码见类注释的判定表。
     */
    public CommandSendResponse send(String deviceId, CommandSendRequest request) {
        // ① 写权限（WRITE_DEVICE 仅 FAMILY 监护人；不存在/越权/关系空一律 40301，PENDING 40302）
        AccessIdentity identity = accessService.requireDevice(deviceId, Permission.WRITE_DEVICE);
        Device device = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                .eq(Device::getDeviceId, deviceId));
        if (device == null || device.getDeviceType() != DeviceType.MAIN) {
            // 子设备编号与越权同码，防设备编号枚举
            throw BizException.of(ErrorCode.FORBIDDEN);
        }

        // ② 命令白名单
        DeviceCmd cmd = DeviceCmd.of(request.getCmd());
        if (cmd == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "未知命令：" + request.getCmd());
        }

        // ③ 设备离线：40901（小程序已按"设备离线，无法下发"对接此码）
        if (device.getOnlineState() != OnlineState.ONLINE) {
            throw BizException.of(ErrorCode.CONFLICT, "设备离线，无法下发");
        }

        // ④ MQTT 未启用：不落库（命令是用户主动的同步操作，悬挂 PENDING 没意义）
        if (downlinkPublisher == null) {
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "MQTT 未启用，无法下发命令");
        }

        // ⑤ 落 PENDING 行
        DeviceCommand row = new DeviceCommand();
        row.setCommandId(commandMapper.nextCommandId());
        row.setDeviceId(deviceId);
        row.setCmd(cmd.name());
        row.setParams(request.getParams() == null ? Map.of() : request.getParams());
        row.setOperatorId(identity.userId());
        row.setState(DeviceCommandState.PENDING.name());
        commandMapper.insert(row);

        // ⑥ 下发；发布失败（如 broker 断连）→ 行置 FAILED 留痕，对调用方报 50000
        try {
            String msgId = downlinkPublisher.publish(deviceId,
                    DownlinkPublisher.SUB_TOPIC_COMMAND,
                    new CommandDownlinkPayload(cmd.name(), row.getParams()), true);
            commandMapper.update(null, new LambdaUpdateWrapper<DeviceCommand>()
                    .eq(DeviceCommand::getCommandId, row.getCommandId())
                    .set(DeviceCommand::getMsgId, msgId));
            recordEvent(deviceId, row.getCommandId(), cmd.name(),
                    DeviceCommandState.PENDING.name(), null);
            log.info("命令已下发 commandId={} deviceId={} cmd={} msgId={}",
                    row.getCommandId(), deviceId, cmd, msgId);
            return new CommandSendResponse(row.getCommandId(), row.getState());
        } catch (RuntimeException ex) {
            commandMapper.update(null, new LambdaUpdateWrapper<DeviceCommand>()
                    .eq(DeviceCommand::getCommandId, row.getCommandId())
                    .set(DeviceCommand::getState, DeviceCommandState.FAILED.name())
                    .set(DeviceCommand::getReason, "MQTT 发布失败：" + ex.getMessage())
                    .set(DeviceCommand::getCompletedAt, OffsetDateTime.now()));
            recordEvent(deviceId, row.getCommandId(), cmd.name(),
                    DeviceCommandState.FAILED.name(), "MQTT 发布失败");
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "MQTT 发布失败，请检查 broker");
        }
    }

    /**
     * 回执结算（设备 OK / 设备 FAILED / 超时放弃三路都走这里）。按持久化的 {@code msg_id} 匹配，
     * 更新条件带 {@code state='PENDING'}：迟到回执 / 重复结算 / 重启后账本丢失都天然幂等。
     */
    @Override
    public void onComplete(String msgId, String deviceId, boolean success, String reason) {
        DeviceCommand row = commandMapper.selectOne(new LambdaQueryWrapper<DeviceCommand>()
                .eq(DeviceCommand::getMsgId, msgId));
        if (row == null) {
            return; // 计划下行的回执，不归我
        }
        DeviceCommandState state = success ? DeviceCommandState.SUCCESS : DeviceCommandState.FAILED;
        int updated = commandMapper.update(null, new LambdaUpdateWrapper<DeviceCommand>()
                .eq(DeviceCommand::getCommandId, row.getCommandId())
                .eq(DeviceCommand::getState, DeviceCommandState.PENDING.name())
                .set(DeviceCommand::getState, state.name())
                .set(DeviceCommand::getReason, reason)
                .set(DeviceCommand::getCompletedAt, OffsetDateTime.now()));
        if (updated == 0) {
            return; // 已是终态：迟到 / 重复的回执
        }
        recordEvent(deviceId, row.getCommandId(), row.getCmd(), state.name(), reason);
        log.info("命令终态 commandId={} deviceId={} result={} reason={}",
                row.getCommandId(), deviceId, state, reason);
    }

    /**
     * 写 COMMAND 事件流水。{@code detail} 是 JSON（契约：{@code {"commandId","cmd","state"[,"reason"]}}，
     * 前端按此解析，见 02 §4.0）。事件写失败只记日志，不阻断主流程。
     */
    private void recordEvent(String deviceId, String commandId, String cmd, String state, String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("commandId", commandId);
        detail.put("cmd", cmd);
        detail.put("state", state);
        if (reason != null) {
            detail.put("reason", reason);
        }
        try {
            deviceEventService.record(deviceId, DeviceEventType.COMMAND, objectMapper.writeValueAsString(detail));
        } catch (RuntimeException ex) {
            log.warn("命令事件流水写入失败 deviceId={} commandId={} state={}：{}",
                    deviceId, commandId, state, ex.getMessage());
        }
    }
}
