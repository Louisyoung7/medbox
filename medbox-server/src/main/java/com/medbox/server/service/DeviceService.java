package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.security.Permission;
import com.medbox.server.config.DeviceProperties;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.DeviceEventType;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.dto.PageResult;
import com.medbox.server.dto.device.DeviceDetailResponse;
import com.medbox.server.dto.device.DeviceEventResponse;
import com.medbox.server.dto.device.DeviceRegisterRequest;
import com.medbox.server.dto.device.DeviceRegisterResponse;
import com.medbox.server.dto.device.DeviceStatusResponse;
import com.medbox.server.dto.device.DeviceSummaryResponse;
import com.medbox.server.mapper.DeviceMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备注册与查询（见文档 02 第 4 章、06 的 2.3）。
 *
 * <p><b>权限分工</b>：注册走 {@link Permission#WRITE_DEVICE}（仅 FAMILY 监护人具备 ——
 * 老人本人不能自注册，医生 / 护理也没有这个权限）；四个查询走 {@link Permission#READ_DEVICE}
 * （老人本人与全部监护角色都放行）。判定一律下沉在本类，Controller 不做权限判断。
 *
 * <p><b>四个 GET 的两种过滤方式</b>：
 * <ul>
 *   <li><b>列表</b>：{@code visibleElderIds()} 取可见老人集合 → {@code elder_id IN (...)} 一次查完，
 *       共 2 次 SQL（老人集合 + 设备），与老人数量无关，不会 N+1；</li>
 *   <li><b>详情 / 状态 / 事件</b>：{@code requireDevice(deviceId, READ_DEVICE)}，
 *       内部先解析归属老人再走四步校验。设备查不到按既有口径返回 <b>40301 而非 40401</b>（防设备编号枚举）。</li>
 * </ul>
 */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceMapper deviceMapper;
    private final DeviceEventService deviceEventService;
    private final AccessService accessService;
    private final DeviceProperties props;
    private final Clock clock;

    /**
     * 生产用构造器。
     *
     * <p><b>必须显式标注 {@code @Autowired}</b>：本类有两个构造器（另一个带 {@link Clock} 供测试注入固定时间），
     * Spring 在"多构造器且无默认构造器"时不会自己挑，直接报 {@code No default constructor found}。
     */
    @Autowired
    public DeviceService(DeviceMapper deviceMapper,
                         DeviceEventService deviceEventService,
                         AccessService accessService,
                         DeviceProperties props) {
        this(deviceMapper, deviceEventService, accessService, props, Clock.systemUTC());
    }

    public DeviceService(DeviceMapper deviceMapper,
                         DeviceEventService deviceEventService,
                         AccessService accessService,
                         DeviceProperties props,
                         Clock clock) {
        this.deviceMapper = deviceMapper;
        this.deviceEventService = deviceEventService;
        this.accessService = accessService;
        this.props = props;
        this.clock = clock;
    }

    /**
     * 登记一台药箱主控及其传感器子设备，**注册即生效**（无需审核）。
     *
     * <p><b>重复登记返回 40901</b>：{@code device_id} 有唯一约束，已存在时直接冲突，
     * 不静默覆盖、也不改绑 —— 换绑老人走后续独立的解绑 / 重绑接口（本分支不做）。
     *
     * <p><b>为什么整个方法要事务</b>：主控 + N 个子设备要么一起成功，要么一起回滚。
     * 只写进一半会让子设备变成"父设备缺失"的脏数据（{@code DeviceOwnerResolverImpl}
     * 遇到这种数据只能判无权限）。
     *
     * @throws com.medbox.server.common.exception.BizException 40101 未登录 / 40301 越权或无权限 /
     *         40302 监护关系未生效 / 40901 设备编号已登记 / 40001 参数非法
     */
    @Transactional
    public DeviceRegisterResponse register(DeviceRegisterRequest request) {
        // ① 权限：只有 ACTIVE 的 FAMILY 监护人能代老人登记
        accessService.require(request.elderId(), Permission.WRITE_DEVICE);

        // ② 参数：子设备类型、编号重复、时区合法性
        validateRegistration(request);
        String timezone = resolveTimezone(request.timezone());
        String productKey = (request.productKey() == null || request.productKey().isBlank())
                ? props.getDefaultProductKey()
                : request.productKey().trim();

        // ③ 冲突：主控与子设备编号都不能与库里已有设备撞（含彼此）
        List<String> allIds = new ArrayList<>();
        allIds.add(request.deviceId());
        if (request.sensors() != null) {
            request.sensors().forEach(s -> allIds.add(s.deviceId()));
        }
        BizAssert.conflictIf(existsAny(allIds), "该设备编号已登记");

        // ④ 落库：主控 + 子设备一起写
        OffsetDateTime now = OffsetDateTime.now(clock);
        Device main = buildDevice(request.deviceId(), DeviceType.MAIN, null,
                request.elderId(), productKey, timezone, now);
        deviceMapper.insert(main);

        List<DeviceRegisterResponse.RegisteredSensor> registered = new ArrayList<>();
        if (request.sensors() != null) {
            for (DeviceRegisterRequest.SensorItem item : request.sensors()) {
                Device sensor = buildDevice(item.deviceId(), item.deviceType(), request.deviceId(),
                        request.elderId(), productKey, timezone, now);
                deviceMapper.insert(sensor);
                registered.add(new DeviceRegisterResponse.RegisteredSensor(
                        item.deviceId(), item.deviceType().name()));
            }
        }

        // ⑤ 事件流水：主控与每个子设备各记一条 REGISTERED
        deviceEventService.record(request.deviceId(), DeviceEventType.REGISTERED, null);
        registered.forEach(s -> deviceEventService.record(s.deviceId(), DeviceEventType.REGISTERED, null));

        log.info("设备登记成功 deviceId={} elderId={} 子设备 {} 个",
                request.deviceId(), request.elderId(), registered.size());

        return new DeviceRegisterResponse(
                request.deviceId(),
                DeviceType.MAIN.name(),
                request.elderId(),
                productKey,
                timezone,
                OnlineState.OFFLINE.name(),
                now,
                registered);
    }

    /**
     * 我监护老人的药箱列表（只列主控，含各自在线状态与子设备数量）。
     *
     * <p><b>两条 SQL 查完</b>：先按可见老人集合取主控，再按主控编号集合一次取出全部子设备做计数，
     * 避免"每台药箱查一次子设备"的 N+1。
     */
    public List<DeviceSummaryResponse> list() {
        Set<String> elderIds = accessService.visibleElderIds();
        if (elderIds.isEmpty()) {
            return List.of();
        }

        List<Device> mains = deviceMapper.selectList(new LambdaQueryWrapper<Device>()
                .in(Device::getElderId, elderIds)
                .eq(Device::getDeviceType, DeviceType.MAIN)
                .orderByAsc(Device::getDeviceId));
        if (mains.isEmpty()) {
            return List.of();
        }

        Map<String, Long> sensorCounts = countSensors(mains.stream().map(Device::getDeviceId).toList());
        Function<String, Integer> countOf = id -> sensorCounts.getOrDefault(id, 0L).intValue();
        return mains.stream()
                .map(device -> DeviceSummaryResponse.from(device, countOf))
                .toList();
    }

    /** 设备详情：档案 + 下属传感器子设备及其在线状态。 */
    public DeviceDetailResponse detail(String deviceId) {
        Device main = requireMain(deviceId);
        List<DeviceDetailResponse.SensorSummary> sensors = deviceMapper
                .selectList(new LambdaQueryWrapper<Device>()
                        .eq(Device::getParentDeviceId, deviceId)
                        .orderByAsc(Device::getDeviceId))
                .stream()
                .map(DeviceDetailResponse.SensorSummary::from)
                .toList();

        return new DeviceDetailResponse(
                main.getDeviceId(),
                DeviceType.MAIN.name(),
                main.getElderId(),
                main.getProductKey(),
                main.getTimezone(),
                main.getOnlineState() == null ? OnlineState.OFFLINE.name() : main.getOnlineState().name(),
                main.getLastHeartbeat(),
                main.getCreatedAt(),
                sensors);
    }

    /** 实时状态：在线与否 + 最后心跳（仓门状态等设备侧上报后再补）。 */
    public DeviceStatusResponse status(String deviceId) {
        return DeviceStatusResponse.from(requireMain(deviceId));
    }

    /**
     * 设备事件流水（分页，按时间倒序）。
     *
     * @param page 页码，从 1 起；不传即第 1 页
     * @param size 每页条数；不传即用 {@code medbox.device.event-page-size}
     */
    public PageResult<DeviceEventResponse> events(String deviceId, Integer page, Integer size) {
        requireMain(deviceId);
        return deviceEventService.page(deviceId, page, size);
    }

    // ------------------------------------------------------------------ 内部方法

    /**
     * 校验并取出一台**药箱主控**（含权限判定）。
     *
     * <p>传入子设备编号时返回 <b>40301</b>：文档 02 第 4 章明确 {@code deviceId} 指药箱主控，
     * 子设备通过详情里的 {@code sensors} 展示。此处沿用"不区分不存在与无权限"的口径，
     * 不返回 40401，避免拿编号去探设备类型。
     */
    private Device requireMain(String deviceId) {
        accessService.requireDevice(deviceId, Permission.READ_DEVICE);
        Device device = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                .eq(Device::getDeviceId, deviceId));
        BizAssert.forbiddenIf(device == null || device.getDeviceType() != DeviceType.MAIN,
                "无权限访问该资源");
        return device;
    }

    /** 一次性统计各主控下属子设备数量。 */
    private Map<String, Long> countSensors(List<String> mainDeviceIds) {
        if (mainDeviceIds.isEmpty()) {
            return Map.of();
        }
        return deviceMapper.selectList(new LambdaQueryWrapper<Device>()
                        .in(Device::getParentDeviceId, mainDeviceIds))
                .stream()
                .collect(Collectors.groupingBy(Device::getParentDeviceId, Collectors.counting()));
    }

    /** 请求体内不允许出现重复编号（主控与子设备之间、子设备彼此之间都不行）。 */
    private void validateRegistration(DeviceRegisterRequest request) {
        Set<String> seen = new LinkedHashSet<>();
        seen.add(request.deviceId());

        if (request.sensors() != null) {
            for (DeviceRegisterRequest.SensorItem item : request.sensors()) {
                BizAssert.badRequestIf(item.deviceType() == null, "子设备 deviceType 不能为空");
                BizAssert.badRequestIf(item.deviceType().isMain(),
                        "子设备类型不能是 MAIN（药箱主控由 deviceId 指定）");
                BizAssert.badRequestIf(!seen.add(item.deviceId()),
                        "设备编号重复：" + item.deviceId());
            }
        }
    }

    /** 时区兜底与合法性校验：非法时区直接 40001，避免把错误值写进库影响服药计划换算。 */
    private String resolveTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return props.getDefaultTimezone();
        }
        String value = timezone.trim();
        try {
            ZoneId.of(value);
        } catch (RuntimeException ex) {
            throw com.medbox.server.common.exception.BizException.of(
                    com.medbox.server.common.exception.ErrorCode.BAD_REQUEST, "timezone 不是合法时区：" + value);
        }
        return value;
    }

    /** 这批编号里是否有任何一个已登记（一次查询，不走循环）。 */
    private boolean existsAny(List<String> deviceIds) {
        if (deviceIds.isEmpty()) {
            return false;
        }
        Long count = deviceMapper.selectCount(new LambdaQueryWrapper<Device>()
                .in(Device::getDeviceId, new HashSet<>(deviceIds)));
        return count != null && count > 0;
    }

    private Device buildDevice(String deviceId,
                               DeviceType type,
                               String parentDeviceId,
                               String elderId,
                               String productKey,
                               String timezone,
                               OffsetDateTime now) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setDeviceType(type);
        device.setParentDeviceId(parentDeviceId);
        device.setElderId(elderId);
        device.setProductKey(productKey);
        device.setTimezone(timezone);
        device.setOnlineState(OnlineState.OFFLINE);
        device.setCreatedAt(now);
        return device;
    }
}
