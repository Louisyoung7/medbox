package com.medbox.server.controller;

import com.medbox.server.dto.PageResult;
import com.medbox.server.dto.R;
import com.medbox.server.dto.alarm.AlarmResponse;
import com.medbox.server.dto.alarm.UnreadCountResponse;
import com.medbox.server.service.AlarmService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 告警查询与处理接口（见文档 02 第 8 章、07 的 {@code feat/alarm-core}）。
 *
 * <p>只做参数绑定与 {@code R<T>} 包装：权限 / 筛选 / 跨老人校验 / 幂等置位全在
 * {@link AlarmService}；未登录由 {@code AuthInterceptor} 统一兜 40101。
 *
 * <p>四个端点：列表（分页 + 类型 / 已读态筛选）、未读角标、标记已读、处理闭环。
 * 告警的实时推送走 WebSocket（{@code ALARM} 帧），不在此处。
 */
@RestController
@RequestMapping("/api/v1/alarms")
public class AlarmController {

    private final AlarmService alarmService;

    public AlarmController(AlarmService alarmService) {
        this.alarmService = alarmService;
    }

    /** GET /alarms?elderId=&type=&readState=：分页告警列表（按创建时间倒序）。 */
    @GetMapping
    public R<PageResult<AlarmResponse>> list(
            @RequestParam("elderId") String elderId,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "readState", required = false) String readState,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
        return R.ok(alarmService.list(elderId, type, readState, page, size));
    }

    /** GET /alarms/unread-count?elderId=：未读告警数（小程序角标）。 */
    @GetMapping("/unread-count")
    public R<UnreadCountResponse> unreadCount(@RequestParam("elderId") String elderId) {
        return R.ok(alarmService.unreadCount(elderId));
    }

    /** PATCH /alarms/{alarmId}/read：标记已读（幂等）。 */
    @PatchMapping("/{alarmId}/read")
    public R<Void> markRead(@PathVariable String alarmId) {
        alarmService.markRead(alarmId);
        return R.ok();
    }

    /** PATCH /alarms/{alarmId}/handle：处理 / 确认告警（幂等，仅 FAMILY + NURSE）。 */
    @PatchMapping("/{alarmId}/handle")
    public R<Void> handle(@PathVariable String alarmId) {
        alarmService.handle(alarmId);
        return R.ok();
    }
}
