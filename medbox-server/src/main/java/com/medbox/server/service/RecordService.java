package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.security.Permission;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedRecord;
import com.medbox.server.domain.Medicine;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.dto.PageResult;
import com.medbox.server.dto.record.RecordConfirmRequest;
import com.medbox.server.dto.record.RecordResponse;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.MedRecordMapper;
import com.medbox.server.mapper.MedicineMapper;
import com.medbox.server.mapper.DeviceMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 服药记录查询与手工补录（{@code feat/record-core}，见文档 02 第 7 章、07）。
 *
 * <p><b>权限</b>：读（列表 / 详情）= {@code READ_RECORD}（全角色，老人仅本人），写（补录 / 校准）=
 * {@code WRITE_RECORD}（仅 FAMILY）；越权 / PENDING / 老人写一律 40301 / 40302，全部走
 * {@link AccessService#require(String, Permission)}。
 *
 * <p><b>单接口双语义</b>：{@code confirm(recordId, req)} 先按 {@code recordId} 查库，命中则校准（覆盖非空
 * 字段 + {@code source=MANUAL}），未命中则新建（补录=创建，必填 {@code elderId}/{@code planId}/
 * {@code planItemId}/{@code medicineId}）。新建时路径 {@code {recordId}} 被忽略，由
 * {@code med_record_biz_id_seq} 服务端生成新 {@code mr-} 号并在响应返回，避免破坏序列约定。
 *
 * <p><b>planTime 重算</b>：新建补录时优先用 {@link DeviceTimezoneResolver} + {@link PlanSlotResolver}
 * 由 {@code planId} 的 {@code times} + {@code actualTime} 在老人 MAIN 设备时区就近槽重算 {@code planTime}，
 * 使漏服判定（{@code miss-alarm}）不被误触发；无 {@code planId} / 算不出槽则置 NULL。
 */
@Service
public class RecordService {

    private static final String SOURCE_MANUAL = "MANUAL";

    private final MedRecordMapper medRecordMapper;
    private final MedPlanMapper medPlanMapper;
    private final MedicineMapper medicineMapper;
    private final DeviceMapper deviceMapper;
    private final DeviceTimezoneResolver timezoneResolver;
    private final PlanSlotResolver slotResolver;
    private final AccessService accessService;
    private final Clock clock;

    /** 测试 / 非 Spring 场景：可注入 {@link Clock} 以固定时间。 */
    public RecordService(MedRecordMapper medRecordMapper, MedPlanMapper medPlanMapper,
                         MedicineMapper medicineMapper, DeviceMapper deviceMapper,
                         DeviceTimezoneResolver timezoneResolver, PlanSlotResolver slotResolver,
                         AccessService accessService, Clock clock) {
        this.medRecordMapper = medRecordMapper;
        this.medPlanMapper = medPlanMapper;
        this.medicineMapper = medicineMapper;
        this.deviceMapper = deviceMapper;
        this.timezoneResolver = timezoneResolver;
        this.slotResolver = slotResolver;
        this.accessService = accessService;
        this.clock = clock;
    }

    /** Spring 入口：{@code Clock} 无 Bean，默认 {@code systemUTC()}（与 {@code PlanService} 同约定）。 */
    @Autowired
    public RecordService(MedRecordMapper medRecordMapper, MedPlanMapper medPlanMapper,
                         MedicineMapper medicineMapper, DeviceMapper deviceMapper,
                         DeviceTimezoneResolver timezoneResolver, PlanSlotResolver slotResolver,
                         AccessService accessService) {
        this(medRecordMapper, medPlanMapper, medicineMapper, deviceMapper,
                timezoneResolver, slotResolver, accessService, Clock.systemUTC());
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

        Page<MedRecord> result = medRecordMapper.selectPage(
                new Page<>((long) safePage, safeSize), wrapper);
        List<MedRecord> records = result.getRecords();
        if (records.isEmpty()) {
            return PageResult.empty(safePage, safeSize);
        }

        Set<String> planIds = records.stream().map(MedRecord::getPlanId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> medicineIds = records.stream().map(MedRecord::getMedicineId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, String> planNames = planNames(planIds);
        Map<String, String> medicineNames = medicineNames(medicineIds);

        List<RecordResponse> list = records.stream()
                .map(r -> RecordResponse.from(r, planNames.get(r.getPlanId()),
                        medicineNames.get(r.getMedicineId())))
                .toList();
        return PageResult.of(list, result.getTotal(), safePage, safeSize);
    }

    // ———————————————— 详情 ————————————————

    /** GET /records/{recordId}：返回单条记录全字段，关联计划名 / 药名 / 抓拍 ID。 */
    public RecordResponse detail(String recordId) {
        MedRecord record = medRecordMapper.selectOne(
                new LambdaQueryWrapper<MedRecord>().eq(MedRecord::getRecordId, recordId));
        BizAssert.notFound(record, "记录不存在");
        accessService.require(record.getElderId(), Permission.READ_RECORD);
        return RecordResponse.from(record,
                planNames(Set.of(record.getPlanId())).get(record.getPlanId()),
                medicineNames(Set.of(record.getMedicineId())).get(record.getMedicineId()));
    }

    // ———————————————— 补录 / 校准 ————————————————

    /**
     * POST /records/{recordId}/confirm：命中已有记录 → 校准；未命中 → 新建（补录=创建）。
     *
     * <p>无论哪条分支，{@code source} 恒为 {@code MANUAL}；校准仅覆盖请求体中非空字段。
     */
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
        if (request.actualDose() != null) {
            existing.setActualDose(request.actualDose());
        }
        if (request.onTime() != null) {
            existing.setOnTime(request.onTime());
        }
        existing.setSource(SOURCE_MANUAL);
        medRecordMapper.updateById(existing);
        return detail(existing.getRecordId());
    }

    /** 补录新建：必填四项，校验计划与药品存在，source=MANUAL，就近槽重算 planTime。 */
    private RecordResponse createManual(RecordConfirmRequest request) {
        BizAssert.notBlank(request.elderId(), "elderId 不能为空（补录必填）");
        BizAssert.notBlank(request.planId(), "planId 不能为空（补录必填）");
        BizAssert.notBlank(request.planItemId(), "planItemId 不能为空（补录必填）");
        BizAssert.notBlank(request.medicineId(), "medicineId 不能为空（补录必填）");

        accessService.require(request.elderId(), Permission.WRITE_RECORD);

        MedPlan plan = medPlanMapper.selectOne(
                new LambdaQueryWrapper<MedPlan>().eq(MedPlan::getPlanId, request.planId()));
        BizAssert.notFound(plan, "计划不存在: " + request.planId());
        Medicine medicine = medicineMapper.selectOne(
                new LambdaQueryWrapper<Medicine>().eq(Medicine::getMedicineId, request.medicineId()));
        BizAssert.notFound(medicine, "药品不存在: " + request.medicineId());

        MedRecord record = new MedRecord();
        record.setRecordId(medRecordMapper.nextRecordId());
        record.setElderId(request.elderId());
        record.setPlanId(request.planId());
        record.setPlanItemId(request.planItemId());
        record.setMedicineId(request.medicineId());
        record.setActualTime(request.actualTime());
        record.setActualDose(request.actualDose());
        record.setOnTime(request.onTime());
        record.setSource(SOURCE_MANUAL);
        record.setPlanTime(recomputePlanTime(request.elderId(), request.planId(), request.actualTime()));
        record.setDeviceId(resolveMainDeviceId(request.elderId()));
        record.setCreatedAt(OffsetDateTime.now(clock));
        medRecordMapper.insert(record);
        return detail(record.getRecordId());
    }

    // ———————————————— 内部：planTime 就近槽重算 ————————————————

    /** 由 planId 的 times + actualTime 在老人设备时区就近槽重算 planTime；缺参数或算不出则 NULL。 */
    private OffsetDateTime recomputePlanTime(String elderId, String planId, OffsetDateTime actualTime) {
        if (planId == null || actualTime == null) {
            return null;
        }
        MedPlan plan = medPlanMapper.selectOne(
                new LambdaQueryWrapper<MedPlan>().eq(MedPlan::getPlanId, planId));
        if (plan == null || plan.getTimes() == null || plan.getTimes().isEmpty()) {
            return null;
        }
        ZoneId zone = timezoneResolver.resolve(elderId);
        List<OffsetDateTime> slots = slotResolver.expandSlots(
                actualTime.minusDays(1).toInstant(),
                actualTime.plusDays(1).toInstant(),
                zone, plan.getTimes());
        if (slots.isEmpty()) {
            return null;
        }
        Instant target = actualTime.toInstant();
        OffsetDateTime best = null;
        long bestAbs = Long.MAX_VALUE;
        for (OffsetDateTime slot : slots) {
            long distance = Math.abs(slot.toInstant().toEpochMilli() - target.toEpochMilli());
            if (distance < bestAbs) {
                bestAbs = distance;
                best = slot;
            }
        }
        return best;
    }

    private String resolveMainDeviceId(String elderId) {
        Device device = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                .eq(Device::getElderId, elderId)
                .eq(Device::getDeviceType, DeviceType.MAIN));
        return device == null ? null : device.getDeviceId();
    }

    // ———————————————— 内部：批量查名（避免 N+1） ————————————————

    private Map<String, String> planNames(Collection<String> planIds) {
        if (planIds == null || planIds.isEmpty()
                || planIds.stream().allMatch(Objects::isNull)) {
            return Map.of();
        }
        return medPlanMapper.selectList(new LambdaQueryWrapper<MedPlan>()
                        .in(MedPlan::getPlanId, planIds)).stream()
                .collect(Collectors.toMap(MedPlan::getPlanId, MedPlan::getName));
    }

    private Map<String, String> medicineNames(Collection<String> medicineIds) {
        if (medicineIds == null || medicineIds.isEmpty()
                || medicineIds.stream().allMatch(Objects::isNull)) {
            return Map.of();
        }
        return medicineMapper.selectList(new LambdaQueryWrapper<Medicine>()
                        .in(Medicine::getMedicineId, medicineIds)).stream()
                .collect(Collectors.toMap(Medicine::getMedicineId, Medicine::getName));
    }
}
