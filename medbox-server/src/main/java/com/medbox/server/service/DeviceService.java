package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.security.Permission;
import com.medbox.server.config.DeviceProperties;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.enums.DeviceEventType;
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
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备注册与查询（见文档 02b §1.0 / 06 的 2.3）。
 *
 * <p><b>系统只建模药箱主控一种设备</b>：没有传感器 / 摄像头子设备记录，也没有
 * {@code sensors} 数组、子设备在线状态与 {@code GET /devices/{id}/sensors} 接口。
 * 环境遥测直接挂主控 {@code deviceId}，摄像头抓拍也用同一个主控 ID。
 *
 * <p><b>权限分工</b>：注册走 {@link Permission#WRITE_DEVICE}（仅 GUARDIAN），
 * 查询走 {@link Permission#READ_DEVICE}（老人本人与监护人）。判定一律下沉在本类。
 *
 * <p><b>设备查不到按 40301 处理</b>：防设备编号枚举。
 */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceMapper deviceMapper;
    private final DeviceEventService deviceEventService;
    private final AccessService accessService;
    private final DeviceProperties props;
    private final EnvSampleService envSampleService;
    private final Clock clock;

    @Autowired
    public DeviceService(DeviceMapper deviceMapper,
                         DeviceEventService deviceEventService,
                         AccessService accessService,
                         DeviceProperties props,
                         EnvSampleService envSampleService) {
        this(deviceMapper, deviceEventService, accessService, props, envSampleService, Clock.systemUTC());
    }

    public DeviceService(DeviceMapper deviceMapper,
                         DeviceEventService deviceEventService,
                         AccessService accessService,
                         DeviceProperties props,
                         EnvSampleService envSampleService,
                         Clock clock) {
        this.deviceMapper = deviceMapper;
        this.deviceEventService = deviceEventService;
        this.accessService = accessService;
        this.props = props;
        this.envSampleService = envSampleService;
        this.clock = clock;
    }

    /**
     * 登记一台药箱，**注册即生效**（无需审核）。
     *
     * <p>{@code deviceId} 由设备固件生成并固化，监护人扫码 / 手填；后端只校验格式与全局唯一。
     * 重复登记返回 40901，不静默覆盖、不改绑。
     */
    @Transactional
    public DeviceRegisterResponse register(DeviceRegisterRequest request) {
        accessService.require(request.elderId(), Permission.WRITE_DEVICE);

        String timezone = resolveTimezone(request.timezone());
        String productKey = (request.productKey() == null || request.productKey().isBlank())
                ? props.getDefaultProductKey()
                : request.productKey().trim();

        BizAssert.conflictIf(existsAny(request.deviceId()), "该设备编号已登记");

        OffsetDateTime now = OffsetDateTime.now(clock);
        Device device = buildDevice(request.deviceId(), request.elderId(), productKey, timezone, now);
        deviceMapper.insert(device);
        deviceEventService.record(request.deviceId(), DeviceEventType.REGISTERED, null);

        log.info("设备登记成功 deviceId={} elderId={}", request.deviceId(), request.elderId());
        return new DeviceRegisterResponse(request.deviceId(), request.elderId(), productKey, timezone,
                OnlineState.OFFLINE.name(), now);
    }

    /** 我可见的设备列表（只列主控，含在线状态与最近心跳）。 */
    public List<DeviceSummaryResponse> list() {
        Set<String> elderIds = accessService.visibleElderIds();
        if (elderIds.isEmpty()) {
            return List.of();
        }
        return deviceMapper.selectList(new LambdaQueryWrapper<Device>()
                        .in(Device::getElderId, elderIds)
                        .orderByAsc(Device::getDeviceId))
                .stream()
                .map(DeviceSummaryResponse::from)
                .toList();
    }

    /** 设备详情：档案 + 最新遥测（无子设备数组）。 */
    public DeviceDetailResponse detail(String deviceId) {
        Device device = requireDevice(deviceId);
        return new DeviceDetailResponse(
                device.getDeviceId(),
                device.getElderId(),
                device.getProductKey(),
                device.getTimezone(),
                device.getOnlineState() == null ? OnlineState.OFFLINE.name() : device.getOnlineState().name(),
                device.getLastHeartbeat(),
                device.getCreatedAt(),
                envSampleService.realtime(deviceId));
    }

    /** 实时状态：在线与否 + 最后心跳。 */
    public DeviceStatusResponse status(String deviceId) {
        return DeviceStatusResponse.from(requireDevice(deviceId));
    }

    /** 设备事件流水（分页，按时间倒序）。 */
    public PageResult<DeviceEventResponse> events(String deviceId, Integer page, Integer size) {
        requireDevice(deviceId);
        return deviceEventService.page(deviceId, page, size);
    }

    private Device requireDevice(String deviceId) {
        accessService.requireDevice(deviceId, Permission.READ_DEVICE);
        Device device = deviceMapper.selectOne(new LambdaQueryWrapper<Device>().eq(Device::getDeviceId, deviceId));
        BizAssert.forbiddenIf(device == null, "无权限访问该资源");
        return device;
    }

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

    private boolean existsAny(String deviceId) {
        Long count = deviceMapper.selectCount(new LambdaQueryWrapper<Device>().eq(Device::getDeviceId, deviceId));
        return count != null && count > 0;
    }

    private Device buildDevice(String deviceId, String elderId, String productKey,
                               String timezone, OffsetDateTime now) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setElderId(elderId);
        device.setProductKey(productKey);
        device.setTimezone(timezone);
        device.setOnlineState(OnlineState.OFFLINE);
        device.setCreatedAt(now);
        return device;
    }
}
