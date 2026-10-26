package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.security.Permission;
import com.medbox.server.domain.Alarm;
import com.medbox.server.dto.PageResult;
import com.medbox.server.dto.alarm.AlarmResponse;
import com.medbox.server.dto.alarm.UnreadCountResponse;
import com.medbox.server.mapper.AlarmMapper;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 告警查询与处理闭环（{@code feat/alarm-core}，见文档 02 第 8 章、07）。
 *
 * <p><b>本类只做查询 / 阅读 / 处理</b>：告警的<b>生成</b>由 {@code miss-alarm} /
 * {@code wrong-drug-alarm} 完成（落 {@code alarm} 表 + 经 WS 推 {@code ALARM} 帧），
 * 本分支不触碰生成逻辑，blast radius 仅限于查询侧。
 *
 * <p><b>权限</b>：列表 / 未读 / 标记已读 = {@code READ_ALARM}（四角色，老人仅本人）；
 * 处理闭环 = {@code HANDLE_ALARM}（仅 FAMILY + NURSE）；越权统一由
 * {@link AccessService#require(String, Permission)} 转 40301 / 40302。
 *
 * <p><b>跨老人防越权</b>：{@code read}/{@code handle} 按 {@code alarmId} 反查归属老人，
 * 告警不存在 → 40401；当前用户对该老人无关系 → 40301（不返回 404，避免枚举老人 ID，
 * 与 {@code AccessService} 口径一致）。
 */
@Service
public class AlarmService {

    private static final String HANDLE_STATE_HANDLED = "HANDLED";

    private final AlarmMapper alarmMapper;
    private final AccessService accessService;

    public AlarmService(AlarmMapper alarmMapper, AccessService accessService) {
        this.alarmMapper = alarmMapper;
        this.accessService = accessService;
    }

    // ———————————————— 列表 ————————————————

    /**
     * GET /alarms?elderId=&type=&readState=：按老人分页（命中 {@code idx_alarm_elder_read}），
     * 可按类型 / 已读态筛选，按 {@code created_at} 倒序。
     */
    public PageResult<AlarmResponse> list(String elderId, String type, String readState,
                                         Integer page, Integer size) {
        accessService.require(elderId, Permission.READ_ALARM);

        int safePage = page == null ? 1 : page;
        int safeSize = size == null ? 20 : size;
        BizAssert.badRequestIf(safePage < 1, "page 需从 1 开始");
        BizAssert.badRequestIf(safeSize < 1, "size 需大于 0");
        safeSize = Math.min(safeSize, 100);

        LambdaQueryWrapper<Alarm> wrapper = new LambdaQueryWrapper<Alarm>()
                .eq(Alarm::getElderId, elderId)
                .orderByDesc(Alarm::getCreatedAt);
        if (type != null && !type.isBlank()) {
            wrapper.eq(Alarm::getType, type);
        }
        Boolean rs = parseReadState(readState);
        if (rs != null) {
            wrapper.eq(Alarm::getReadState, rs);
        }

        Page<Alarm> result = alarmMapper.selectPage(new Page<>((long) safePage, safeSize), wrapper);
        List<AlarmResponse> list = result.getRecords().stream()
                .map(AlarmResponse::from)
                .toList();
        return PageResult.of(list, result.getTotal(), safePage, safeSize);
    }

    /** readState 解析：true/READ → 已读；false/UNREAD → 未读；其余 → 不过滤。 */
    private static Boolean parseReadState(String readState) {
        if (readState == null || readState.isBlank()) {
            return null;
        }
        if ("true".equalsIgnoreCase(readState) || "READ".equalsIgnoreCase(readState)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(readState) || "UNREAD".equalsIgnoreCase(readState)) {
            return Boolean.FALSE;
        }
        return null;
    }

    // ———————————————— 未读角标 ————————————————

    /** GET /alarms/unread-count：该老人未读告警数（{@code read_state = false} 计数）。 */
    public UnreadCountResponse unreadCount(String elderId) {
        accessService.require(elderId, Permission.READ_ALARM);
        long n = alarmMapper.selectCount(new LambdaQueryWrapper<Alarm>()
                .eq(Alarm::getElderId, elderId)
                .eq(Alarm::getReadState, false));
        return new UnreadCountResponse(n);
    }

    // ———————————————— 标记已读 ————————————————

    /** PATCH /alarms/{alarmId}/read：幂等置 {@code read_state = true}。 */
    public void markRead(String alarmId) {
        Alarm alarm = alarmMapper.selectOne(
                new LambdaQueryWrapper<Alarm>().eq(Alarm::getAlarmId, alarmId));
        BizAssert.notFound(alarm, "告警不存在: " + alarmId);
        accessService.require(alarm.getElderId(), Permission.READ_ALARM);
        if (alarm.getReadState() == null || !alarm.getReadState()) {
            alarm.setReadState(true);
            alarmMapper.updateById(alarm);
        }
    }

    // ———————————————— 处理闭环 ————————————————

    /** PATCH /alarms/{alarmId}/handle：幂等置 {@code handle_state = HANDLED}（仅 FAMILY + NURSE）。 */
    public void handle(String alarmId) {
        Alarm alarm = alarmMapper.selectOne(
                new LambdaQueryWrapper<Alarm>().eq(Alarm::getAlarmId, alarmId));
        BizAssert.notFound(alarm, "告警不存在: " + alarmId);
        accessService.require(alarm.getElderId(), Permission.HANDLE_ALARM);
        if (!HANDLE_STATE_HANDLED.equals(alarm.getHandleState())) {
            alarm.setHandleState(HANDLE_STATE_HANDLED);
            alarmMapper.updateById(alarm);
        }
    }
}
