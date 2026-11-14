package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.security.Permission;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedRecord;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.dto.PageResult;
import com.medbox.server.dto.record.RecordConfirmRequest;
import com.medbox.server.dto.record.RecordResponse;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.MedRecordMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 服药记录查询与手工补录（见文档 02e / 07 §1）。
 *
 * <p><b>权限</b>：读（列表 / 详情）= {@code READ_RECORD}；写（补录 / 纠错）= {@code WRITE_RECORD}。
 *
 * <p><b>单接口双语义</b>：{@code confirm(recordId, req)} 命中已有记录 → 纠错（仅覆盖非空字段 +
 * {@code source=MANUAL}）；未命中 → 新建（补录，必填 {@code elderId}/{@code planId}/{@code cellNo}）。
 * 新建时路径 {@code {recordId}} 被忽略，由 {@code med_record_biz_id_seq} 生成新 {@code mr-} 号。
 *
 * <p><b>planTime 重算</b>：新建补录按该格 {@code slots} + {@code actualTime} 在老人 MAIN 设备时区
 * 就近槽重算；该格无排程 / 算不出槽则置 NULL。
 */
@Service
public class RecordService {

    private static final String SOURCE_MANUAL = "MANUAL";

    private final MedRecordMapper medRecordMapper;
    private final MedPlanMapper medPlanMapper;
    private final DeviceMapper deviceMapper;
    private final DeviceTimezoneResolver timezoneResolver;
    private final PlanSlotResolver slotResolver;
    private final AccessService accessService;
    private final Clock clock;

    /** 测试 / 非 Spring 场景：可注入 {@link Clock} 以固定时间。 */
    public RecordService(MedRecordMapper medRecordMapper, MedPlanMapper medPlanMapper,
                         DeviceMapper deviceMapper, DeviceTimezoneResolver timezoneResolver,
                         PlanSlotResolver slotResolver, AccessService accessService, Clock clock) {
        this.medRecordMapper = medRecordMapper;
        this.medPlanMapper = medPlanMapper;
        this.deviceMapper = deviceMapper;
        this.timezoneResolver = timezoneResolver;
        this.slotResolver = slotResolver;
        this.accessService = accessService;
        this.clock = clock;
    }

    /** Spring 入口：{@code Clock} 无 Bean，默认 {@code systemUTC()}。 */
    @Autowired
    public RecordService(MedRecordMapper medRecordMapper, MedPlanMapper medPlanMapper,
                         DeviceMapper deviceMapper, DeviceTimezoneResolver timezoneResolver,
                         PlanSlotResolver slotResolver, AccessService accessService) {
        this(medRecordMapper, medPlanMapper, deviceMapper, timezoneResolver,
                slotResolver, accessService, Clock.systemUTC());
    }

    // ———————————————— 列表 ————————————————

    /** GET /records?elderId=&from=&to=：按老人分页，按实际服药时间倒序；可加 from/to 时间窗筛选。 */
    public PageResult<RecordResponse> list(String elderId, OffsetDateTime from, OffsetDateTime to,
                                          Integer page, Integer size) {
        accessService.require(elderId, Permission.READ_RECORD);

        int safePage = page == null ? 1 : page;
        int safeSize = size == null ? 20 : size;
        BizAssert.badRequestIf(safePage < 1, "page 需从 1 开始");
        BizAssert.badRequestIf(safeSize < 1, "size 需大于 0");
        safeSize = Math.min(safeSize, 100);

        LambdaQueryWrapper<MedRecord> wrapper = new LambdaQueryWrapper<MedRecord>()
                .eq(MedRecord::getElderId, elderId)
                .orderByDesc(MedRecord::getActualTime);
        if (from != null) {
            wrapper.ge(MedRecord::getActualTime, from);
        }
        if (to != null) {
            wrapper.le(MedRecord::getActualTime, to);
        }

        Page<MedRecord> result = medRecordMapper.selectPage(new Page<>((long) safePage, safeSize), wrapper);
        List<MedRecord> records = result.getRecords();
        if (records.isEmpty()) {
            return PageResult.empty(safePage, safeSize);
        }
        return PageResult.of(toResponses(records), result.getTotal(), safePage, safeSize);
    }

    // ———————————————— 导出 ————————————————

    /** GET /records/export：按老人 + 时间窗导出全部服药记录（不翻页），join 计划名后返回。 */
    public List<RecordResponse> exportRecords(String elderId, OffsetDateTime from, OffsetDateTime to) {
        accessService.require(elderId, Permission.EXPORT_RECORD);

        LambdaQueryWrapper<MedRecord> wrapper = new LambdaQueryWrapper<MedRecord>()
                .eq(MedRecord::getElderId, elderId)
                .orderByDesc(MedRecord::getActualTime);
        if (from != null) {
            wrapper.ge(MedRecord::getActualTime, from);
        }
        if (to != null) {
            wrapper.le(MedRecord::getActualTime, to);
        }
        return toResponses(medRecordMapper.selectList(wrapper));
    }

    /** 批量把 {@link MedRecord} 转 {@link RecordResponse} 并 join 计划名（避免 N+1）；不再 join 药品。 */
    private List<RecordResponse> toResponses(List<MedRecord> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        Set<String> planIds = records.stream().map(MedRecord::getPlanId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, String> planNames = planNames(planIds);
        return records.stream()
                .map(r -> RecordResponse.from(r, planNames.get(r.getPlanId())))
                .toList();
    }

    // ———————————————— 详情 ————————————————

    /** GET /records/{recordId}：返回单条记录全字段（含格号），关联计划名。 */
    public RecordResponse detail(String recordId) {
        MedRecord record = medRecordMapper.selectOne(
                new LambdaQueryWrapper<MedRecord>().eq(MedRecord::getRecordId, recordId));
        BizAssert.notFound(record, "记录不存在");
        accessService.require(record.getElderId(), Permission.READ_RECORD);
        return RecordResponse.from(record,
                planNames(Set.of(record.getPlanId())).get(record.getPlanId()));
    }

    // ———————————————— 补录 / 纠错 ————————————————

    /** POST /records/{recordId}/confirm：命中已有记录 → 纠错；未命中 → 新建（补录）。 */
    @Transactional
    public RecordResponse confirm(String recordId, RecordConfirmRequest request) {
        MedRecord existing = medRecordMapper.selectOne(
                new LambdaQueryWrapper<MedRecord>().eq(MedRecord::getRecordId, recordId));
        if (existing == null) {
            return createManual(request);
        }

        accessService.require(existing.getElderId(), Permission.WRITE_RECORD);
        if (request.actualTime() != null) {
            existing.setActualTime(request.actualTime());
        }
        if (request.onTime() != null) {
            existing.setOnTime(request.onTime());
        }
        if (request.takenResult() != null) {
            existing.setTakenResult(normalizeTaken(request.takenResult()));
        }
        existing.setSource(SOURCE_MANUAL);
        medRecordMapper.updateById(existing);
        return detail(existing.getRecordId());
    }

    /** 补录新建：必填 elderId / planId / cellNo；source=MANUAL，按该格就近槽重算 planTime。 */
    private RecordResponse createManual(RecordConfirmRequest request) {
        BizAssert.notBlank(request.elderId(), "elderId 不能为空（补录必填）");
        BizAssert.notBlank(request.planId(), "planId 不能为空（补录必填）");
        BizAssert.isTrue(PlanSlotResolver.isValidCellNo(request.cellNo()),
                com.medbox.server.common.exception.ErrorCode.BAD_REQUEST, "cellNo 必填且只能为 1/2/3");

        accessService.require(request.elderId(), Permission.WRITE_RECORD);

        MedPlan plan = medPlanMapper.selectOne(
                new LambdaQueryWrapper<MedPlan>().eq(MedPlan::getPlanId, request.planId()));
        BizAssert.notFound(plan, "计划不存在: " + request.planId());

        MedRecord record = new MedRecord();
        record.setRecordId(medRecordMapper.nextRecordId());
        record.setElderId(request.elderId());
        record.setPlanId(request.planId());
        record.setCellNo(request.cellNo());
        record.setActualTime(request.actualTime());
        record.setOnTime(request.onTime());
        record.setTakenResult(normalizeTaken(request.takenResult()));
        record.setSource(SOURCE_MANUAL);
        record.setPlanTime(recomputePlanTime(request.elderId(), plan, request.actualTime(), request.cellNo()));
        record.setDeviceId(resolveMainDeviceId(request.elderId()));
        record.setCreatedAt(OffsetDateTime.now(clock));
        medRecordMapper.insert(record);
        return detail(record.getRecordId());
    }

    // ———————————————— 内部：planTime 按格就近槽重算 ————————————————

    /** 该格无排程 / 无实际时间 → NULL，避免漏服判定因补录记录被误触发。 */
    private OffsetDateTime recomputePlanTime(String elderId, MedPlan plan,
                                             OffsetDateTime actualTime, Integer cellNo) {
        if (plan == null || actualTime == null || !PlanSlotResolver.isValidCellNo(cellNo)) {
            return null;
        }
        ZoneId zone = timezoneResolver.resolve(elderId);
        return slotResolver.nearestSlot(actualTime.toInstant(), zone, plan.getSlots(), cellNo);
    }

    private String resolveMainDeviceId(String elderId) {
        Device device = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                .eq(Device::getElderId, elderId)
                .eq(Device::getDeviceType, DeviceType.MAIN));
        return device == null ? null : device.getDeviceId();
    }

    private static String normalizeTaken(String taken) {
        if (taken == null || taken.isBlank()) {
            return "UNTAKEN";
        }
        return "TAKEN".equals(taken.trim().toUpperCase(Locale.ROOT)) ? "TAKEN" : "UNTAKEN";
    }

    // ———————————————— 内部：批量查计划名（避免 N+1） ————————————————

    private Map<String, String> planNames(Collection<String> planIds) {
        if (planIds == null || planIds.isEmpty() || planIds.stream().allMatch(Objects::isNull)) {
            return Map.of();
        }
        return medPlanMapper.selectList(new LambdaQueryWrapper<MedPlan>()
                        .in(MedPlan::getPlanId, planIds)).stream()
                .collect(Collectors.toMap(MedPlan::getPlanId, MedPlan::getName));
    }
}
