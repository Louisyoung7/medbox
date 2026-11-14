package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.security.Permission;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.PlanSlot;
import com.medbox.server.dto.PageResult;
import com.medbox.server.dto.plan.NextDoseResponse;
import com.medbox.server.dto.plan.PlanListResponse;
import com.medbox.server.dto.plan.PlanResponse;
import com.medbox.server.dto.plan.PlanSaveRequest;
import com.medbox.server.dto.plan.PlanStatusRequest;
import com.medbox.server.mapper.MedPlanMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 服药计划 CRUD + 未来待服时间线（见文档 02d / 06 §2.6）。
 *
 * <p><b>一老人一计划</b>：{@code med_plan.elder_id} 唯一；重复创建返回 40901。
 * 计划只含 1~3 个 {@code slots}（格号 1/2/3 + 本地墙钟 HH:mm），不关联药品。
 * 缺失的格号 = 该格不排程。
 *
 * <p><b>权限</b>：写（增 / 改 / 启停 / 删）= {@code WRITE_PLAN}，读 = {@code READ_PLAN}。
 *
 * <p><b>时区换算</b>：{@code slots[].time} 是设备本地墙钟，换算用老人名下 MAIN 主控设备的
 * {@code device.timezone}（无设备 / 非法回退 {@code Asia/Shanghai}）。本期仅 DAILY。
 */
@Service
public class PlanService {

    private static final Pattern TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final String STATUS_ENABLED = "ENABLED";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String SYNC_PENDING = "PENDING";
    private static final String REPEAT_DAILY = "DAILY";
    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 60;
    private static final int MAX_DOSES = 200;
    private static final DateTimeFormatter LOCAL_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final MedPlanMapper medPlanMapper;
    private final DeviceTimezoneResolver timezoneResolver;
    private final PlanSlotResolver slotResolver;
    private final AccessService accessService;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public PlanService(MedPlanMapper medPlanMapper, DeviceTimezoneResolver timezoneResolver,
                       PlanSlotResolver slotResolver, AccessService accessService,
                       ApplicationEventPublisher eventPublisher) {
        this(medPlanMapper, timezoneResolver, slotResolver, accessService,
                Clock.systemUTC(), eventPublisher);
    }

    public PlanService(MedPlanMapper medPlanMapper, DeviceTimezoneResolver timezoneResolver,
                       PlanSlotResolver slotResolver, AccessService accessService, Clock clock,
                       ApplicationEventPublisher eventPublisher) {
        this.medPlanMapper = medPlanMapper;
        this.timezoneResolver = timezoneResolver;
        this.slotResolver = slotResolver;
        this.accessService = accessService;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
    }

    // ———————————————— 创建 ————————————————

    /** POST /plans：elderId 必填；一老人一计划，重复创建 40901。 */
    @Transactional
    public PlanResponse create(PlanSaveRequest request) {
        BizAssert.notBlank(request.elderId(), "elderId 不能为空");
        accessService.require(request.elderId(), Permission.WRITE_PLAN);

        List<PlanSlot> slots = validateContent(request);
        MedPlan existing = medPlanMapper.selectOne(
                new LambdaQueryWrapper<MedPlan>().eq(MedPlan::getElderId, request.elderId()));
        BizAssert.conflictIf(existing != null, "该老人已有服药计划");

        MedPlan plan = new MedPlan();
        plan.setPlanId(medPlanMapper.nextPlanId());
        plan.setElderId(request.elderId());
        plan.setName(request.name());
        plan.setSlots(slots);
        plan.setRepeatRule(normalizeRepeat(request.repeatRule()));
        plan.setMissAlarmAfterMin(request.missAlarmAfterMin());
        plan.setAlarmRule(request.alarmRule());
        plan.setStatus(STATUS_ENABLED);
        plan.setSyncState(SYNC_PENDING);
        plan.setCreatedAt(OffsetDateTime.now(clock));
        medPlanMapper.insert(plan);

        publishSync(plan.getPlanId(), request.elderId(), PlanSyncEvent.Op.UPSERT);
        return PlanResponse.from(plan);
    }

    // ———————————————— 整体覆盖修改 ————————————————

    /** PUT /plans/{planId}：归属老人与状态不可变，slots / 周期 / 报警规则整体覆盖。 */
    @Transactional
    public PlanResponse update(String planId, PlanSaveRequest request) {
        MedPlan plan = findForWrite(planId);
        List<PlanSlot> slots = validateContent(request);

        plan.setName(request.name());
        plan.setSlots(slots);
        plan.setRepeatRule(normalizeRepeat(request.repeatRule()));
        plan.setMissAlarmAfterMin(request.missAlarmAfterMin());
        plan.setAlarmRule(request.alarmRule());
        medPlanMapper.updateById(plan);

        publishSync(planId, plan.getElderId(), PlanSyncEvent.Op.UPSERT);
        return PlanResponse.from(plan);
    }

    // ———————————————— 启停 ————————————————

    /** PATCH /plans/{planId}/status：整体切换 ENABLED / DISABLED。 */
    @Transactional
    public PlanResponse updateStatus(String planId, PlanStatusRequest request) {
        MedPlan plan = findForWrite(planId);
        String status = request.status();
        BizAssert.badRequestIf(!STATUS_ENABLED.equals(status) && !STATUS_DISABLED.equals(status),
                "status 仅支持 ENABLED / DISABLED");
        plan.setStatus(status);
        medPlanMapper.updateById(plan);
        PlanSyncEvent.Op op = STATUS_ENABLED.equals(status) ? PlanSyncEvent.Op.UPSERT : PlanSyncEvent.Op.DELETE;
        publishSync(planId, plan.getElderId(), op);
        return PlanResponse.from(plan);
    }

    // ———————————————— 删除 ————————————————

    /** DELETE /plans/{planId}：一老人一计划，无明细级联。 */
    @Transactional
    public void delete(String planId) {
        MedPlan plan = findForWrite(planId);
        String elderId = plan.getElderId();
        medPlanMapper.deleteById(plan.getId());
        publishSync(planId, elderId, PlanSyncEvent.Op.DELETE);
    }

    /** 计划写事务提交后由 {@code MqttPlanScheduleSync} 下发 slots 到设备。 */
    private void publishSync(String planId, String elderId, PlanSyncEvent.Op op) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new PlanSyncEvent(planId, elderId, op));
        }
    }

    // ———————————————— 查询 ————————————————

    /** GET /plans/{planId}：详情（含 slots）。 */
    public PlanResponse detail(String planId) {
        return PlanResponse.from(findForRead(planId));
    }

    /** GET /plans?elderId=：分页列表（一老人一计划，返回 0 或 1 条）。 */
    public PageResult<PlanListResponse> list(String elderId, Integer page, Integer size) {
        accessService.require(elderId, Permission.READ_PLAN);

        int safePage = page == null ? 1 : page;
        int safeSize = size == null ? 20 : size;
        BizAssert.badRequestIf(safePage < 1, "page 需从 1 开始");
        BizAssert.badRequestIf(safeSize < 1, "size 需大于 0");
        safeSize = Math.min(safeSize, 100);

        Page<MedPlan> result = medPlanMapper.selectPage(
                new Page<>((long) safePage, safeSize),
                new LambdaQueryWrapper<MedPlan>()
                        .eq(MedPlan::getElderId, elderId)
                        .orderByDesc(MedPlan::getCreatedAt));
        List<MedPlan> plans = result.getRecords();
        if (plans.isEmpty()) {
            return PageResult.empty(safePage, safeSize);
        }
        return PageResult.of(plans.stream().map(PlanListResponse::from).toList(),
                result.getTotal(), safePage, safeSize);
    }

    // ———————————————— 未来待服时间线（仅 DAILY） ————————————————

    /**
     * GET /plans/{planId}/next-doses?days=N：按 slots 展开未来时间线，每条含 {@code cellNo}、
     * 不含药品信息。DISABLED 计划返回空；days 默认 7、上限 60；绝对上限 200 条。
     */
    public List<NextDoseResponse> nextDoses(String planId, Integer days) {
        MedPlan plan = findForRead(planId);
        if (STATUS_DISABLED.equals(plan.getStatus())) {
            return List.of();
        }

        ZoneId zone = timezoneResolver.resolve(plan.getElderId());
        int window = clampDays(days);
        OffsetDateTime now = OffsetDateTime.now(clock);
        Instant nowInstant = clock.instant();
        List<PlanSlotResolver.SlotInstant> slots = slotResolver.expandSlots(
                nowInstant, nowInstant.plus(window, ChronoUnit.DAYS), zone, plan.getSlots());

        List<PlanSlotResolver.SlotInstant> future = slots.stream()
                .filter(s -> s.slotTime().toInstant().isAfter(nowInstant))
                .sorted(Comparator.comparing(s -> s.slotTime().toInstant()))
                .limit(MAX_DOSES)
                .toList();

        List<NextDoseResponse> result = new ArrayList<>(future.size());
        for (PlanSlotResolver.SlotInstant slot : future) {
            OffsetDateTime utc = slot.slotTime().withOffsetSameInstant(ZoneOffset.UTC);
            result.add(new NextDoseResponse(
                    slot.cellNo(),
                    utc.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    slot.slotTime().format(LOCAL_FORMATTER)));
        }
        return result;
    }

    // ———————————————— 内部：权限定位 ————————————————

    /** 读：不存在一律 40301（防枚举），再走 READ_PLAN。 */
    private MedPlan findForRead(String planId) {
        MedPlan plan = medPlanMapper.selectOne(
                new LambdaQueryWrapper<MedPlan>().eq(MedPlan::getPlanId, planId));
        BizAssert.forbiddenIf(plan == null, "无权限访问该资源");
        accessService.require(plan.getElderId(), Permission.READ_PLAN);
        return plan;
    }

    /** 写：不存在一律 40301，再按归属老人判 WRITE_PLAN。 */
    private MedPlan findForWrite(String planId) {
        MedPlan plan = medPlanMapper.selectOne(
                new LambdaQueryWrapper<MedPlan>().eq(MedPlan::getPlanId, planId));
        BizAssert.forbiddenIf(plan == null, "无权限访问该资源");
        accessService.require(plan.getElderId(), Permission.WRITE_PLAN);
        return plan;
    }

    // ———————————————— 内部：校验 ————————————————

    /** 校验并返回按格号升序的 slots 副本；格号 1/2/3 且不重复，time 为 HH:mm 设备本地墙钟。 */
    private List<PlanSlot> validateContent(PlanSaveRequest request) {
        List<PlanSlot> slots = request.slots();
        BizAssert.badRequestIf(slots == null || slots.isEmpty(), "slots 不能为空");
        BizAssert.badRequestIf(slots.size() > 3, "slots 最多 3 项");

        Set<Integer> seen = new HashSet<>();
        for (PlanSlot slot : slots) {
            BizAssert.badRequestIf(slot == null || !PlanSlotResolver.isValidCellNo(slot.getCellNo()),
                    "cellNo 只能为 1/2/3");
            BizAssert.badRequestIf(!seen.add(slot.getCellNo()), "格号不可重复: " + slot.getCellNo());
            BizAssert.badRequestIf(slot.getTime() == null
                            || !TIME_PATTERN.matcher(slot.getTime()).matches(),
                    "slot.time 格式应为 HH:mm: " + (slot == null ? "null" : slot.getTime()));
        }
        List<PlanSlot> sorted = new ArrayList<>(slots);
        sorted.sort(Comparator.comparing(PlanSlot::getCellNo));
        return sorted;
    }

    private String normalizeRepeat(String repeatRule) {
        if (repeatRule == null || repeatRule.isBlank()) {
            return REPEAT_DAILY;
        }
        if (REPEAT_DAILY.equalsIgnoreCase(repeatRule.trim())) {
            return REPEAT_DAILY;
        }
        BizAssert.badRequestIf(true, "repeatRule 本期仅支持 DAILY");
        return REPEAT_DAILY;
    }

    private int clampDays(Integer days) {
        int value = days == null ? DEFAULT_DAYS : days;
        if (value < 1) {
            value = DEFAULT_DAYS;
        }
        return Math.min(value, MAX_DAYS);
    }
}
