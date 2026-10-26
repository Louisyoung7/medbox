package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.common.security.Permission;
import com.medbox.server.domain.Alarm;
import com.medbox.server.dto.alarm.UnreadCountResponse;
import com.medbox.server.mapper.AlarmMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AlarmServiceTest {

    private static final String ELDER_ID = "u-1001";
    private static final String ALARM_ID = "a-1001";

    @Mock
    private AlarmMapper alarmMapper;

    @Mock
    private AccessService accessService;

    private AlarmService service;

    @BeforeEach
    void setUp() {
        service = new AlarmService(alarmMapper, accessService);
    }

    @Test
    @DisplayName("markRead：告警不存在 → 40401")
    void 标记已读_不存在返回404() {
        when(alarmMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.markRead(ALARM_ID));
        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
        verify(accessService, never()).require(any(), any());
        verify(alarmMapper, never()).updateById(any(Alarm.class));
    }

    @Test
    @DisplayName("markRead：已读权限通过 → 置 readState=true 并 updateById")
    void 标记已读_成功() {
        Alarm alarm = alarm();
        alarm.setReadState(false);
        when(alarmMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(alarm);

        service.markRead(ALARM_ID);

        verify(accessService).require(ELDER_ID, Permission.READ_ALARM);
        verify(alarmMapper).updateById(alarm);
        assertEquals(Boolean.TRUE, alarm.getReadState());
    }

    @Test
    @DisplayName("markRead：无 HANDLE 角色 → require 抛 403（不落库）")
    void 标记已读_越权不落库() {
        Alarm alarm = alarm();
        when(alarmMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(alarm);
        when(accessService.require(ELDER_ID, Permission.READ_ALARM))
                .thenThrow(BizException.of(ErrorCode.FORBIDDEN));

        BizException ex = assertThrows(BizException.class, () -> service.markRead(ALARM_ID));
        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode());
        verify(alarmMapper, never()).updateById(any(Alarm.class));
    }

    @Test
    @DisplayName("handle：HANDLE_ALARM 通过 → 置 HANDLED 并 updateById")
    void 处理_成功() {
        Alarm alarm = alarm();
        alarm.setHandleState("OPEN");
        when(alarmMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(alarm);

        service.handle(ALARM_ID);

        verify(accessService).require(ELDER_ID, Permission.HANDLE_ALARM);
        verify(alarmMapper).updateById(alarm);
        assertEquals("HANDLED", alarm.getHandleState());
    }

    @Test
    @DisplayName("unreadCount：返回 selectCount 计数")
    void 未读数_透传计数() {
        when(alarmMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(3L);
        UnreadCountResponse resp = service.unreadCount(ELDER_ID);
        verify(accessService).require(ELDER_ID, Permission.READ_ALARM);
        assertEquals(3L, resp.unreadCount());
    }

    private Alarm alarm() {
        Alarm a = new Alarm();
        a.setId(1L);
        a.setAlarmId(ALARM_ID);
        a.setElderId(ELDER_ID);
        a.setType("MISS");
        a.setHandleState("OPEN");
        a.setReadState(false);
        return a;
    }
}
