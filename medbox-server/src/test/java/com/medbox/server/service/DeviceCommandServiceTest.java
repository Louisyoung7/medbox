package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.common.security.AccessIdentity;
import com.medbox.server.common.security.Permission;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.DeviceCommand;
import com.medbox.server.domain.enums.DeviceEventType;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.dto.device.CommandSendRequest;
import com.medbox.server.dto.device.CommandSendResponse;
import com.medbox.server.mapper.DeviceCommandMapper;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mqtt.DownlinkPublisher;
import com.medbox.server.mqtt.payload.CommandDownlinkPayload;
import com.medbox.server.support.TableInfoWarmup;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link DeviceCommandService} 的纯单测（判定表逐行一个用例，见 07 清单 feat/device-command）。
 *
 * <p>重点验：
 * <ul>
 *   <li>判定顺序与错误码：40301（不存在/子设备）→ 40001（未知 cmd）→ 40901（离线）→ 50000（MQTT 未启用/发布失败）；</li>
 *   <li>正常路径：PENDING 落库 → QoS1 needAck 下发 → 回填 msg_id → 写 COMMAND 事件（detail 为契约 JSON）；</li>
 *   <li>回执结算：按 msg_id 匹配、终态只翻一次、不写告警。</li>
 * </ul>
 */
class DeviceCommandServiceTest {

    private static final String DEVICE_ID = "BOXA1001";
    private static final String SENSOR_ID = "BOXA1001-S-TH01";
    private static final String ELDER_ID = "u-1001";
    private static final String GUARDIAN_ID = "u-2001";

    private DeviceMapper deviceMapper;
    private DeviceCommandMapper commandMapper;
    private DeviceEventService deviceEventService;
    private AccessService accessService;
    private DownlinkPublisher publisher;
    private DeviceCommandService service;

    @BeforeEach
    void setUp() {
        TableInfoWarmup.init(Device.class, DeviceCommand.class);
        deviceMapper = mock(DeviceMapper.class);
        commandMapper = mock(DeviceCommandMapper.class);
        deviceEventService = mock(DeviceEventService.class);
        accessService = mock(AccessService.class);
        publisher = mock(DownlinkPublisher.class);
        service = new DeviceCommandService(deviceMapper, commandMapper, deviceEventService,
                accessService, new JsonMapper());
        ReflectionTestUtils.setField(service, "downlinkPublisher", publisher);

        when(accessService.requireDevice(eq(DEVICE_ID), eq(Permission.WRITE_DEVICE)))
                .thenReturn(AccessIdentity.guardian(GUARDIAN_ID, ELDER_ID));
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(mainDevice(OnlineState.ONLINE));
        when(commandMapper.nextCommandId()).thenReturn("c-1001");
        when(commandMapper.insert(any(DeviceCommand.class))).thenReturn(1);
        when(commandMapper.update(any(), ArgumentMatchers.<LambdaUpdateWrapper<DeviceCommand>>any())).thenReturn(1);
        when(publisher.publish(eq(DEVICE_ID), eq(DownlinkPublisher.SUB_TOPIC_COMMAND),
                any(CommandDownlinkPayload.class), eq(true))).thenReturn("d-cmd-1");
    }

    // ————————————————— send —————————————————

    @Test
    @DisplayName("正常下发：PENDING 落库 → QoS1 needAck 下发 → 回填 msg_id → 写 PENDING 事件")
    void 正常下发() {
        CommandSendResponse resp = service.send(DEVICE_ID, buzz());

        assertEquals("c-1001", resp.getCommandId());
        assertEquals("PENDING", resp.getState());

        ArgumentCaptor<DeviceCommand> insertCaptor = ArgumentCaptor.forClass(DeviceCommand.class);
        verify(commandMapper).insert(insertCaptor.capture());
        DeviceCommand row = insertCaptor.getValue();
        assertEquals("BUZZ", row.getCmd());
        assertEquals(GUARDIAN_ID, row.getOperatorId(), "operator 取鉴权用户，不信客户端");
        assertEquals(5, row.getParams().get("durationSec"));

        verify(publisher).publish(eq(DEVICE_ID), eq(DownlinkPublisher.SUB_TOPIC_COMMAND),
                argThat(p -> p instanceof CommandDownlinkPayload cp
                        && "BUZZ".equals(cp.getCmd())
                        && cp.getParams().get("durationSec").equals(5)), eq(true));
        verify(deviceEventService).record(eq(DEVICE_ID), eq(DeviceEventType.COMMAND),
                argThat(detail -> detail.contains("\"commandId\":\"c-1001\"")
                        && detail.contains("\"cmd\":\"BUZZ\"")
                        && detail.contains("\"state\":\"PENDING\"")));
    }

    @Test
    @DisplayName("未知 cmd → 40001，不落库不下发")
    void 未知命令_40001() {
        CommandSendRequest request = new CommandSendRequest();
        request.setCmd("EXPLODE");

        BizException ex = assertThrows(BizException.class, () -> service.send(DEVICE_ID, request));
        assertEquals(ErrorCode.BAD_REQUEST.code(), ex.code());
        verify(commandMapper, never()).insert(any(DeviceCommand.class));
        verify(publisher, never()).publish(any(), any(), any(), eq(true));
    }

    @Test
    @DisplayName("设备不存在 → 40301")
    void 设备不存在_40301() {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any())).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.send(DEVICE_ID, buzz()));
        assertEquals(ErrorCode.FORBIDDEN.code(), ex.code());
    }

    @Test
    @DisplayName("传子设备编号 → 40301（防编号枚举）")
    void 子设备编号_40301() {
        Device sensor = new Device();
        sensor.setDeviceId(SENSOR_ID);
        sensor.setDeviceType(DeviceType.TEMP_HUMI);
        sensor.setElderId(ELDER_ID);
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any())).thenReturn(sensor);

        BizException ex = assertThrows(BizException.class, () -> service.send(SENSOR_ID, buzz()));
        assertEquals(ErrorCode.FORBIDDEN.code(), ex.code());
    }

    @Test
    @DisplayName("设备离线 → 40901（小程序按此码提示'设备离线，无法下发'）")
    void 设备离线_40901() {
        when(deviceMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<Device>>any()))
                .thenReturn(mainDevice(OnlineState.OFFLINE));

        BizException ex = assertThrows(BizException.class, () -> service.send(DEVICE_ID, buzz()));
        assertEquals(ErrorCode.CONFLICT.code(), ex.code());
        verify(commandMapper, never()).insert(any(DeviceCommand.class));
    }

    @Test
    @DisplayName("MQTT 未启用（publisher 为 null）→ 50000，不落库")
    void MQTT未启用_50000() {
        ReflectionTestUtils.setField(service, "downlinkPublisher", null);

        BizException ex = assertThrows(BizException.class, () -> service.send(DEVICE_ID, buzz()));
        assertEquals(ErrorCode.INTERNAL_ERROR.code(), ex.code());
        verify(commandMapper, never()).insert(any(DeviceCommand.class));
    }

    @Test
    @DisplayName("publish 抛异常（broker 断连）→ 行置 FAILED 留痕 + 50000")
    void 发布失败_行置失败_50000() {
        when(publisher.publish(any(), any(), any(CommandDownlinkPayload.class), eq(true)))
                .thenThrow(new RuntimeException("connection lost"));

        BizException ex = assertThrows(BizException.class, () -> service.send(DEVICE_ID, buzz()));
        assertEquals(ErrorCode.INTERNAL_ERROR.code(), ex.code());

        ArgumentCaptor<LambdaUpdateWrapper<DeviceCommand>> captor = updateCaptor();
        verify(commandMapper).update(any(), captor.capture());
        // getSqlSet() 只渲染列名（值是 #{...} 占位符），字面值要查 paramNameValuePairs
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("FAILED"), "发布失败要把行置 FAILED");
        verify(deviceEventService).record(eq(DEVICE_ID), eq(DeviceEventType.COMMAND),
                argThat(detail -> detail.contains("\"state\":\"FAILED\"")));
    }

    // ————————————————— onComplete —————————————————

    @Test
    @DisplayName("回执 OK → 行翻 SUCCESS + 写成功事件")
    void 回执成功() {
        givenPendingRow("d-cmd-1");
        when(commandMapper.update(any(), ArgumentMatchers.<LambdaUpdateWrapper<DeviceCommand>>any())).thenReturn(1);

        service.onComplete("d-cmd-1", DEVICE_ID, true, null);

        ArgumentCaptor<LambdaUpdateWrapper<DeviceCommand>> captor = updateCaptor();
        verify(commandMapper).update(any(), captor.capture());
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("SUCCESS"));
        verify(deviceEventService).record(eq(DEVICE_ID), eq(DeviceEventType.COMMAND),
                argThat(detail -> detail.contains("\"state\":\"SUCCESS\"")));
    }

    @Test
    @DisplayName("回执 FAILED（含超时放弃）→ 行翻 FAILED 带 reason + 写失败事件；不生成告警")
    void 回执失败() {
        givenPendingRow("d-cmd-1");
        when(commandMapper.update(any(), ArgumentMatchers.<LambdaUpdateWrapper<DeviceCommand>>any())).thenReturn(1);

        service.onComplete("d-cmd-1", DEVICE_ID, false, "超过最大下发次数仍无回执");

        ArgumentCaptor<LambdaUpdateWrapper<DeviceCommand>> captor = updateCaptor();
        verify(commandMapper).update(any(), captor.capture());
        var params = captor.getValue().getParamNameValuePairs();
        assertTrue(params.containsValue("FAILED") && params.containsValue("超过最大下发次数仍无回执"));
        verify(deviceEventService).record(eq(DEVICE_ID), eq(DeviceEventType.COMMAND),
                argThat(detail -> detail.contains("\"state\":\"FAILED\"") && detail.contains("reason")));
        // 不生成告警是构造性保证：本类不持有 AlarmMapper
    }

    @Test
    @DisplayName("msg_id 无匹配行（计划下行的回执）→ 不动")
    void 回执无匹配() {
        when(commandMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<DeviceCommand>>any())).thenReturn(null);

        service.onComplete("d-unknown", DEVICE_ID, true, null);

        verify(commandMapper, never()).update(any(), ArgumentMatchers.<LambdaUpdateWrapper<DeviceCommand>>any());
        verify(deviceEventService, never()).record(any(), any(), any());
    }

    @Test
    @DisplayName("行已是终态（迟到/重复回执）→ 幂等，不再写事件")
    void 重复回执幂等() {
        givenPendingRow("d-cmd-1");
        when(commandMapper.update(any(), ArgumentMatchers.<LambdaUpdateWrapper<DeviceCommand>>any())).thenReturn(0);

        service.onComplete("d-cmd-1", DEVICE_ID, true, null);

        verify(deviceEventService, never()).record(any(), any(), any());
    }

    // ————————————————— 辅助 —————————————————

    private void givenPendingRow(String msgId) {
        DeviceCommand row = new DeviceCommand();
        row.setCommandId("c-1001");
        row.setDeviceId(DEVICE_ID);
        row.setCmd("BUZZ");
        row.setState("PENDING");
        row.setMsgId(msgId);
        when(commandMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<DeviceCommand>>any())).thenReturn(row);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<LambdaUpdateWrapper<DeviceCommand>> updateCaptor() {
        return ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    }

    private static CommandSendRequest buzz() {
        CommandSendRequest request = new CommandSendRequest();
        request.setCmd("BUZZ");
        request.setParams(Map.of("durationSec", 5));
        return request;
    }

    private static Device mainDevice(OnlineState state) {
        Device device = new Device();
        device.setDeviceId(DEVICE_ID);
        device.setDeviceType(DeviceType.MAIN);
        device.setElderId(ELDER_ID);
        device.setOnlineState(state);
        return device;
    }
}
