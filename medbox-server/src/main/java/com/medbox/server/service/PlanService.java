package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.security.Permission;
import com.medbox.server.service.AccessService;
import com.medbox.server.domain.Medicine;
import com.medbox.server.domain.MedicineStock;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedPlanItem;
import com.medbox.server.dto.PageResult;
import com.medbox.server.dto.plan.NextDoseItemResponse;
import com.medbox.server.dto.plan.NextDoseResponse;
import com.medbox.server.dto.plan.PlanItemRequest;
import com.medbox.server.dto.plan.PlanItemResponse;
import com.medbox.server.dto.plan.PlanListResponse;
import com.medbox.server.dto.plan.PlanResponse;
import com.medbox.server.dto.plan.PlanSaveRequest;
import com.medbox.server.dto.plan.PlanStatusRequest;
import com.medbox.server.mapper.MedPlanItemMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.MedicineMapper;
import com.medbox.server.mapper.MedicineStockMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 服药计划 CRUD + 未来待服时间线（{@code feat/plan-core}，见文档 02 第 6 章、06 的 2.6 / 2.6.1）。
 *
 * <p><b>权限</b>：写（增 / 改 / 启停 / 删）= {@code WRITE_PLAN}（FAMILY + DOCTOR），
 * 读（列表 / 详情 / 时间线）= {@code READ_PLAN}（全角色），老人只读 —— 全部走
 * {@link AccessService#require(String, Permission)}，越权一律 40301（防枚举）。
 *
 * <p><b>时区换算口径（判定型分支的核心）</b>：{@code times} 是设备本地墙钟（如 "08:00"），
 * 不存 UTC；换算时用老人<b>名下 MAIN 主控设备</b>的 {@code device.timezone}，无设备 / 空 / 非法
 * 一律回退 {@code Asia/Shanghai}（用户确认不改表、复用 device.timezone）。
 *
 * <p><b>创建 / 修改的库存校验（06 约束②）</b>：每个 item 的药品必须存在（40401），
 * 且在该老人<b>任一</b>设备上有 {@code quantity > 0} 的库存，否则 40901；老人无任何设备 → 无库存 → 40901。
 *
 * <p><b>本期只实现 DAILY</b>：{@code repeat_rule} 单 VARCHAR 列且 06 未列全，WEEKLY 等后续分支补。
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    private static final Pattern TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final String STATUS_ENABLED = "ENABLED";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String SYNC_PENDING = "PENDING";
    private static final String REPEAT_DAILY = "DAILY";
    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 60;
    private static final int MAX_DOSES = 200;
    private static final DateTimeFormatter LOCAL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final MedPlanMapper medPlanMapper;
    private final MedPlanItemMapper medPlanItemMapper;
    private final MedicineMapper medicineMapper;
    private final MedicineStockMapper medicineStockMapper;
    private final DeviceTimezoneResolver timezoneResolver;
    private final AccessService accessService;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public PlanService(MedPlanMapper medPlanMapper, MedPlanItemMapper medPlanItemMapper,
                       MedicineMapper medicineMapper, MedicineStockMapper medicineStockMapper,
                       DeviceTimezoneResolver timezoneResolver, AccessService accessService,
                       ApplicationEventPublisher eventPublisher) {
        this(medPlanMapper, medPlanItemMapper, medicineMapper, medicineStockMapper,
                timezoneResolver, accessService, Clock.systemUTC(), eventPublisher);
    }

    public PlanService(MedPlanMapper medPlanMapper, MedPlanItemMapper medPlanItemMapper,
                       MedicineMapper medicineMapper, MedicineStockMapper medicineStockMapper,
                       DeviceTimezoneResolver timezoneResolver, AccessService accessService, Clock clock,
                       ApplicationEventPublisher eventPublisher) {
        this.medPlanMapper = medPlanMapper;
        this.medPlanItemMapper = medPlanItemMapper;
        this.medicineMapper = medicineMapper;
        this.medicineStockMapper = medicineStockMapper;
        this.timezoneResolver = timezoneResolver;
        this.accessService = accessService;
        this.clock = clock;
        this.eventPublisher = eventPublisher;
    }

    // ———————————————— 创建 ————————————————

    /** POST /plans：elderId 必填，校验后落库 + 级联落明细。 */
    @Transactional
    public PlanResponse create(PlanSaveRequest request) {
        BizAssert.notBlank(request.elderId(), "elderId 不能为空");
        accessService.require(request.elderId(), Permission.WRITE_PLAN);

        validateContent(request);
        ZoneId zone = resolveTimezone(request.elderId());
        requireStockAvailable(request.elderId(), medicineIdsOf(request.items()), zone);

        MedPlan plan = new MedPlan();
        plan.setPlanId(medPlanMapper.nextPlanId());
        plan.setElderId(request.elderId());
        plan.setName(request.name());
        plan.setTimes(dedupTimes(request.times()));
        plan.setRepeatRule(normalizeRepeat(request.repeatRule()));
        plan.setMissAlarmAfterMin(request.missAlarmAfterMin());
        plan.setAlarmRule(request.alarmRule());
        plan.setStatus(STATUS_ENABLED);
        plan.setSyncState(SYNC_PENDING);
        plan.setCreatedAt(OffsetDateTime.now(clock));
        medPlanMapper.insert(plan);

        List<PlanItemResponse> itemResponses = saveItems(plan.getPlanId(), request.items());
        publishSync(plan.getPlanId(), request.elderId(), PlanSyncEvent.Op.UPSERT);
        return PlanResponse.from(plan, itemResponses);
    }

    // ———————————————— 整体覆盖修改 ————————————————

    /** PUT /plans/{planId}：归属老人与状态不可变，其余整覆盖；明细先删后插。 */
    @Transactional
    public PlanResponse update(String planId, PlanSaveRequest request) {
        MedPlan plan = findForWrite(planId);

        validateContent(request);
        ZoneId zone = resolveTimezone(plan.getElderId());
        requireStockAvailable(plan.getElderId(), medicineIdsOf(request.items()), zone);

        plan.setName(request.name());
        plan.setTimes(dedupTimes(request.times()));
        plan.setRepeatRule(normalizeRepeat(request.repeatRule()));
        plan.setMissAlarmAfterMin(request.missAlarmAfterMin());
        plan.setAlarmRule(request.alarmRule());
        medPlanMapper.updateById(plan);

        medPlanItemMapper.delete(new LambdaQueryWrapper<MedPlanItem>()
                .eq(MedPlanItem::getPlanId, planId));
        List<PlanItemResponse> itemResponses = saveItems(planId, request.items());
        publishSync(planId, plan.getElderId(), PlanSyncEvent.Op.UPSERT);
        return PlanResponse.from(plan, itemResponses);
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
        return PlanResponse.from(plan, itemResponsesOf(plan.getPlanId()));
    }

    // ———————————————— 删除（级联） ————————————————

    /** DELETE /plans/{planId}：先删明细再删计划头（应用层级联，不依赖物理外键）。 */
    @Transactional
    public void delete(String planId) {
        MedPlan plan = findForWrite(planId);
        String elderId = plan.getElderId();
        medPlanItemMapper.delete(new LambdaQueryWrapper<MedPlanItem>()
                .eq(MedPlanItem::getPlanId, planId));
        medPlanMapper.deleteById(plan.getId());
        publishSync(planId, elderId, PlanSyncEvent.Op.DELETE);
    }

    /**
     * 发布计划变更事件，由 {@code MqttPlanScheduleSync} 在事务提交后下行到设备。
     *
     * <p>放在各写方法末尾：事件被 Spring 缓冲到事务提交后才派发（{@code AFTER_COMMIT}），
     * 监听器回查时一定能拿到已提交数据；{@code eventPublisher} 为空（如测试里手动构造的实例）时跳过。
     */
    private void publishSync(String planId, String elderId, PlanSyncEvent.Op op) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new PlanSyncEvent(planId, elderId, op));
        }
    }

    // ———————————————— 查询 ————————————————

    /** GET /plans/{planId}：详情（含明细）。 */
    public PlanResponse detail(String planId) {
        MedPlan plan = findForRead(planId);
        return PlanResponse.from(plan, itemResponsesOf(plan.getPlanId()));
    }

    /** GET /plans?elderId=：分页列表 + 药品概览。 */
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

        Set<String> planIds = plans.stream().map(MedPlan::getPlanId).collect(Collectors.toSet());
        List<MedPlanItem> allItems = medPlanItemMapper.selectList(
                new LambdaQueryWrapper<MedPlanItem>().in(MedPlanItem::getPlanId, planIds));
        Map<String, List<MedPlanItem>> itemsByPlan = allItems.stream()
                .collect(Collectors.groupingBy(MedPlanItem::getPlanId));
        Map<String, String> names = medicineNames(allItems.stream()
                .map(MedPlanItem::getMedicineId).collect(Collectors.toSet()));

        List<PlanListResponse> list = plans.stream().map(p -> {
            List<MedPlanItem> its = itemsByPlan.getOrDefault(p.getPlanId(), List.of());
            List<String> medicineNames = its.stream()
                    .map(i -> names.get(i.getMedicineId()))
                    .filter(Objects::nonNull)
                    .toList();
            return PlanListResponse.from(p, its.size(), medicineNames);
        }).toList();

        return PageResult.of(list, result.getTotal(), safePage, safeSize);
    }

    // ———————————————— 未来待服时间线（仅 DAILY） ————————————————

    /**
     * GET /plans/{planId}/next-doses?days=N：按次展开未来时间线。
     *
     * <p>DISABLED 计划返回空；窗口 {@code days} 默认 7、上限 60；逐日 × 逐 times 组合成
     * "本地日期 + HH:mm" → 按设备时区转 UTC → 仅留 {@code scheduledUtc > now} → 按 UTC 升序 →
     * 绝对上限 200 条截断。因以 {@code now} 为基准实时算，新建 / 修改当天剩余剂量自然可见。
     */
    public List<NextDoseResponse> nextDoses(String planId, Integer days) {
        MedPlan plan = findForRead(planId);
        if (STATUS_DISABLED.equals(plan.getStatus())) {
            return List.of();
        }

        ZoneId zone = resolveTimezone(plan.getElderId());
        int window = clampDays(days);
        OffsetDateTime now = OffsetDateTime.now(clock);
        // 用注入的 clock 取"设备时区下的今日本地日期"，与 now 同源，避免系统时钟漂移导致边界错乱
        LocalDate todayLocal = LocalDate.ofInstant(clock.instant(), zone);

        List<String> sortedTimes = plan.getTimes().stream()
                .sorted(Comparator.naturalOrder())
                .toList();

        List<OffsetDateTime> slots = new ArrayList<>();
        for (int d = 0; d < window; d++) {
            LocalDate date = todayLocal.plusDays(d);
            for (String time : sortedTimes) {
                LocalTime lt = LocalTime.parse(time); // 已在创建/修改时校验过格式
                LocalDateTime localDateTime = LocalDateTime.of(date, lt);
                OffsetDateTime scheduled = ZonedDateTime.of(localDateTime, zone).toOffsetDateTime();
                if (scheduled.isAfter(now)) {
                    slots.add(scheduled);
                }
            }
        }
        slots.sort(Comparator.naturalOrder());

        List<OffsetDateTime> capped = slots.stream().limit(MAX_DOSES).toList();
        List<MedPlanItem> items = itemsOf(plan.getPlanId());
        Map<String, String> names = medicineNames(items.stream()
                .map(MedPlanItem::getMedicineId).collect(Collectors.toSet()));

        List<NextDoseResponse> result = new ArrayList<>(capped.size());
        for (OffsetDateTime slot : capped) {
            List<NextDoseItemResponse> itemResponses = items.stream()
                    .map(it -> NextDoseItemResponse.from(it, names.get(it.getMedicineId())))
                    .toList();
            result.add(new NextDoseResponse(
                    slot.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    slot.format(LOCAL_FORMATTER),
                    itemResponses));
        }
        return result;
    }

    // ———————————————— 内部：权限定位 ————————————————

    /** 读：不存在一律 40301（防枚举），私有再走 READ_PLAN。 */
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

    private void validateContent(PlanSaveRequest request) {
        BizAssert.badRequestIf(request.times() == null || request.times().isEmpty(),
                "times 不能为空");
        for (String time : request.times()) {
            BizAssert.badRequestIf(time == null || !TIME_PATTERN.matcher(time).matches(),
                    "times 格式应为 HH:mm: " + time);
        }
        BizAssert.badRequestIf(request.items() == null || request.items().isEmpty(),
                "items 不能为空");

        Set<String> seen = new HashSet<>();
        for (PlanItemRequest item : request.items()) {
            BizAssert.notBlank(item.medicineId(), "item.medicineId 不能为空");
            BizAssert.badRequestIf(!seen.add(item.medicineId()),
                    "同一计划内药品不可重复: " + item.medicineId());
        }
    }

    private String normalizeRepeat(String repeatRule) {
        if (repeatRule == null || repeatRule.isBlank()) {
            return REPEAT_DAILY;
        }
        if (REPEAT_DAILY.equalsIgnoreCase(repeatRule.trim())) {
            return REPEAT_DAILY;
        }
        BizAssert.badRequestIf(true, "repeatRule 本期仅支持 DAILY");
        return REPEAT_DAILY; // 不可达
    }

    private List<String> dedupTimes(List<String> times) {
        return new ArrayList<>(new LinkedHashSet<>(times));
    }

    private Set<String> medicineIdsOf(List<PlanItemRequest> items) {
        return items.stream().map(PlanItemRequest::medicineId).collect(Collectors.toSet());
    }

    // ———————————————— 内部：库存 / 过期校验 ————————————————

    /**
     * 06 约束②：每个药品必须存在，且在该老人任一设备上有库存（quantity>0）。
     * 无合格库存 → 40901；药品不存在 → 40401。
     */
    private void requireStockAvailable(String elderId, Set<String> medicineIds, ZoneId zone) {
        for (String medicineId : medicineIds) {
            Medicine medicine = medicineMapper.selectOne(
                    new LambdaQueryWrapper<Medicine>().eq(Medicine::getMedicineId, medicineId));
            BizAssert.notFound(medicine, "药品不存在: " + medicineId);
        }

        LocalDate today = LocalDate.now(zone);
        List<MedicineStock> stocks = medicineStockMapper.selectList(
                new LambdaQueryWrapper<MedicineStock>()
                        .eq(MedicineStock::getElderId, elderId)
                        .in(MedicineStock::getMedicineId, medicineIds)
                        .gt(MedicineStock::getQuantity, 0));

        Set<String> satisfied = new HashSet<>();
        for (MedicineStock stock : stocks) {
            if (stock.getQuantity() != null && stock.getQuantity() > 0) {
                satisfied.add(stock.getMedicineId());
            }
        }
        for (String medicineId : medicineIds) {
            BizAssert.conflictIf(!satisfied.contains(medicineId),
                    "药品无合格库存或已过期: " + medicineId);
        }
    }

    // ———————————————— 内部：时区解析 ————————————————

    /** 取老人 MAIN 主控设备的 timezone；无设备 / 空 / 非法 → Asia/Shanghai（委托公共组件）。 */
    private ZoneId resolveTimezone(String elderId) {
        return timezoneResolver.resolve(elderId);
    }

    // ———————————————— 内部：明细读写 ————————————————

    private List<PlanItemResponse> saveItems(String planId, List<PlanItemRequest> items) {
        List<PlanItemResponse> responses = new ArrayList<>(items.size());
        int order = 0;
        for (PlanItemRequest item : items) {
            MedPlanItem entity = new MedPlanItem();
            entity.setItemId(medPlanItemMapper.nextItemId());
            entity.setPlanId(planId);
            entity.setMedicineId(item.medicineId());
            entity.setDose(item.dose());
            entity.setUnit(item.unit());
            entity.setNote(item.note());
            entity.setSortOrder(order++);
            entity.setCreatedAt(OffsetDateTime.now(clock));
            medPlanItemMapper.insert(entity);
            responses.add(PlanItemResponse.from(entity));
        }
        return responses;
    }

    private List<MedPlanItem> itemsOf(String planId) {
        return medPlanItemMapper.selectList(new LambdaQueryWrapper<MedPlanItem>()
                .eq(MedPlanItem::getPlanId, planId)
                .orderByAsc(MedPlanItem::getSortOrder)
                .orderByAsc(MedPlanItem::getCreatedAt));
    }

    private List<PlanItemResponse> itemResponsesOf(String planId) {
        return itemsOf(planId).stream().map(PlanItemResponse::from).toList();
    }

    /** 批量查药名，避免列表 / 时间线逐条查库的 N+1。 */
    private Map<String, String> medicineNames(Collection<String> medicineIds) {
        if (medicineIds.isEmpty()) {
            return Map.of();
        }
        return medicineMapper.selectList(new LambdaQueryWrapper<Medicine>()
                        .in(Medicine::getMedicineId, medicineIds))
                .stream()
                .collect(Collectors.toMap(Medicine::getMedicineId, Medicine::getName));
    }

    private int clampDays(Integer days) {
        int value = days == null ? DEFAULT_DAYS : days;
        if (value < 1) {
            value = DEFAULT_DAYS;
        }
        return Math.min(value, MAX_DAYS);
    }
}
