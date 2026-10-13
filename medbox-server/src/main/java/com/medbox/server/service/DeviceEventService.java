package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.config.DeviceProperties;
import com.medbox.server.domain.DeviceEvent;
import com.medbox.server.domain.enums.DeviceEventType;
import com.medbox.server.dto.PageResult;
import com.medbox.server.dto.device.DeviceEventResponse;
import com.medbox.server.mapper.DeviceEventMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 设备事件流水的写入与查询（{@code device_event}，见文档 06 的 2.16）。
 *
 * <p><b>两个调用方，一个出口</b>：
 * <ul>
 *   <li>HTTP 侧：{@code DeviceService} 登记设备后写 {@code REGISTERED}；</li>
 *   <li>MQTT 侧：{@code DeviceStatusService} 状态跃变时经 {@code ObjectProvider} 拿到本类，
 *       写 {@code ONLINE} / {@code OFFLINE}。</li>
 * </ul>
 * 后续 {@code feat/device-fault}、{@code feat/device-command} 也走 {@link #record}，
 * 届时只管用新的 {@link DeviceEventType} 枚举值，表结构与本类都不用改。
 *
 * <p><b>写入失败绝不阻断主流程</b>：状态更新、设备登记是主链路，事件流水只是"留痕"。
 * 流水写不进去就记 WARN，不能让设备因为一张流水表而注册失败 ——
 * 与 {@code DeviceStatusService} 里"数据库异常只告警不抛出"是同一条口径。
 */
@Service
public class DeviceEventService {

    private static final Logger log = LoggerFactory.getLogger(DeviceEventService.class);

    private final DeviceEventMapper deviceEventMapper;
    private final DeviceProperties props;
    private final Clock clock;

    /**
     * 生产用构造器。
     *
     * <p><b>必须显式标注 {@code @Autowired}</b>：本类有两个构造器（另一个带 {@link Clock} 供测试注入固定时间），
     * Spring 在"多构造器且无默认构造器"时不会自己挑，直接报 {@code No default constructor found}。
     */
    @Autowired
    public DeviceEventService(DeviceEventMapper deviceEventMapper, DeviceProperties props) {
        this(deviceEventMapper, props, Clock.systemUTC());
    }

    public DeviceEventService(DeviceEventMapper deviceEventMapper, DeviceProperties props, Clock clock) {
        this.deviceEventMapper = deviceEventMapper;
        this.props = props;
        this.clock = clock;
    }

    /**
     * 记录一条设备事件。
     *
     * <p><b>调用方不需要 try-catch</b>：本方法内部已兜住全部 {@code RuntimeException}。
     * 事件流水是旁路，不该把异常抛回登记 / 状态更新这些主链路。
     */
    public void record(String deviceId, DeviceEventType type, String detail) {
        try {
            DeviceEvent event = new DeviceEvent();
            event.setDeviceId(deviceId);
            event.setEventType(type);
            event.setDetail(detail);
            event.setCreatedAt(OffsetDateTime.now(clock));
            deviceEventMapper.insert(event);
        } catch (RuntimeException ex) {
            log.warn("写入设备事件流水失败 deviceId={} type={}：{}", deviceId, type, ex.getMessage());
        }
    }

    /**
     * 分页查询某设备的事件流水，按时间倒序（最新的在前）。
     *
     * @param deviceId 设备编号
     * @param page     页码，从 1 起；为 null 时取第 1 页
     * @param size     每页条数；为 null 时取 {@code medbox.device.event-page-size}，
     *                 超过 {@code event-max-page-size} 时静默取上限（客户端多要一点无伤大雅），
     *                 但要 0 条或负页是明显的调用错误，按 40001 拒绝
     * @throws com.medbox.server.common.exception.BizException 40001 分页参数非法
     */
    public PageResult<DeviceEventResponse> page(String deviceId, Integer page, Integer size) {
        int safePage = page == null ? 1 : page;
        int safeSize = size == null ? props.getEventPageSize() : size;
        BizAssert.badRequestIf(safePage < 1, "page 需从 1 开始");
        BizAssert.badRequestIf(safeSize < 1, "size 需大于 0");
        safeSize = Math.min(safeSize, props.getEventMaxPageSize());

        Page<DeviceEvent> result = deviceEventMapper.selectPage(
                new Page<>((long) safePage, safeSize),
                new LambdaQueryWrapper<DeviceEvent>()
                        .eq(DeviceEvent::getDeviceId, deviceId)
                        .orderByDesc(DeviceEvent::getCreatedAt));

        List<DeviceEventResponse> list = result.getRecords().stream()
                .map(DeviceEventResponse::from)
                .toList();
        return PageResult.of(list, result.getTotal(), safePage, safeSize);
    }
}
