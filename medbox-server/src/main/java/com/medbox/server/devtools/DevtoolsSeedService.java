package com.medbox.server.devtools;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.PasswordHasher;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.devtools.dto.SeedResult;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.Medicine;
import com.medbox.server.domain.MedicineStock;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.MedPlanItem;
import com.medbox.server.domain.PlanReminder;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.domain.enums.UserStatus;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.MedicineMapper;
import com.medbox.server.mapper.MedicineStockMapper;
import com.medbox.server.mapper.MedPlanItemMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.PlanReminderMapper;
import com.medbox.server.mapper.UserMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 种子数据编排：以"系统身份"直接经各 {@code Mapper} 直写最小数据集，绕开服务层的 AuthContext / 权限校验。
 *
 * <p><b>为什么不走业务 Service</b>：{@code DeviceService.register} / {@code GuardianRelationService.apply|accept}
 * 等强耦合登录态与监护关系校验，强行注入 AuthContext 易碎。种子数据完全由我们掌控，直写更稳、也便于
 * 业务 ID 里会与真实数据相撞的三个加 {@code debug} 段（{@code u-debug-guardian} / {@code BOXA-DEBUG-01} /
 * {@code s-debug-001}）；其余 {@code e-1001} / {@code m-205} / {@code p-3301} / {@code pi-9001} 不在业务序列
 * 生成值域内，保持短 ID。详见 {@link DevtoolsProperties}。
 *
 * <p><b>幂等</b>：监护人已存在则整批跳过（直接返回已有 ID + 重新签发 token）；{@code reset=true}
 * 先按外键逆序清掉种子数据再重种。监护人缺失但其它种子实体残留（例如上次播种后单独删了用户）时，
 * 非 reset 分支也会先按固定 ID 清残留再插入，可自愈、不会因部分残留而报 DuplicateKey。
 *
 * <p><b>不触发 plan-sync</b>：计划经 Mapper 直写，不会发 {@code PlanSyncEvent}，避免无设备时产生 SYNC_FAILED 噪音。
 */
@Service
@ConditionalOnProperty(prefix = "medbox.devtools", name = "enabled", havingValue = "true")
public class DevtoolsSeedService {

    /** 公共药品业务 ID（业务序列从 {@code m-1001} 起，{@code m-205} 不在生成值域内，天然不撞）。 */
    private static final String MED_PUBLIC = "m-205";

    /** 老人私有药品业务 ID。 */
    private static final String MED_PRIVATE = "m-206";

    /** 库存业务 ID（业务序列从 {@code s-1001} 起，旧值会撞第一个真实库存，故加 {@code debug} 段）。 */
    private static final String STOCK_ID = "s-debug-001";

    /** 计划业务 ID（业务序列从 {@code p-1001} 起，{@code p-3301} 不在生成值域内）。 */
    private static final String PLAN_ID = "p-3301";

    /** 计划项业务 ID。 */
    private static final String PLAN_ITEM_ID = "pi-9001";

    /** 监护关系业务 ID（种子专用，固定值）。 */
    private static final String RELATION_ID = "r-debug-001";

    private final UserMapper userMapper;
    private final GuardianRelationMapper guardianRelationMapper;
    private final DeviceMapper deviceMapper;
    private final MedicineMapper medicineMapper;
    private final MedicineStockMapper medicineStockMapper;
    private final MedPlanMapper medPlanMapper;
    private final MedPlanItemMapper medPlanItemMapper;
    private final PlanReminderMapper planReminderMapper;
    private final PasswordHasher passwordHasher;
    private final JwtProvider jwtProvider;
    private final DevtoolsProperties props;

    public DevtoolsSeedService(UserMapper userMapper, GuardianRelationMapper guardianRelationMapper,
            DeviceMapper deviceMapper, MedicineMapper medicineMapper,
            MedicineStockMapper medicineStockMapper, MedPlanMapper medPlanMapper,
            MedPlanItemMapper medPlanItemMapper, PlanReminderMapper planReminderMapper,
            PasswordHasher passwordHasher, JwtProvider jwtProvider, DevtoolsProperties props) {
        this.userMapper = userMapper;
        this.guardianRelationMapper = guardianRelationMapper;
        this.deviceMapper = deviceMapper;
        this.medicineMapper = medicineMapper;
        this.medicineStockMapper = medicineStockMapper;
        this.medPlanMapper = medPlanMapper;
        this.medPlanItemMapper = medPlanItemMapper;
        this.planReminderMapper = planReminderMapper;
        this.passwordHasher = passwordHasher;
        this.jwtProvider = jwtProvider;
        this.props = props;
    }

    @Transactional
    public SeedResult seed(boolean reset) {
        DevtoolsProperties.Seed s = props.getSeed();
        List<String> medicineIds = List.of(MED_PUBLIC, MED_PRIVATE);
        if (reset) {
            clearSeed(s);
            insertAll(s);
            return result(true, s, medicineIds);
        }
        if (existsGuardian(s.getGuardianUserId())) {
            return result(false, s, medicineIds);
        }
        // 监护人不在：可能是首次播种，也可能是上次播种后用户被单独删除导致残留（设备/计划/药品还在）。
        // 先按固定 ID 清掉任何残留的种子数据，再整批插入——保证可重复、可自愈，不再因部分残留而 DuplicateKey。
        clearSeed(s);
        insertAll(s);
        return result(true, s, medicineIds);
    }

    private void insertAll(DevtoolsProperties.Seed s) {
        insertGuardianAndElder(s);
        insertDevices(s);
        insertMedicines(s);
        insertStock(s);
        insertPlanAndItem(s);
    }

    /**
     * 种子监护人是否已落库。
     *
     * <p><b>必须同时校验角色</b>：只看 {@code user_id} 时，任何占用该 ID 的账号（例如用户手动注册的
     * 老人）都会被误判为"种子已存在"，{@link #seed(boolean)} 随即直接返回、整批种子静默不写库——
     * 前端却拿到配置里的固定账号和新签 token，看起来"播种成功"，实际库里只有那个占位账号。
     */
    private boolean existsGuardian(String guardianUserId) {
        return userMapper.selectCount(new QueryWrapper<User>()
                .eq("user_id", guardianUserId)
                .eq("role", UserRole.GUARDIAN.name())) > 0;
    }

    private void clearSeed(DevtoolsProperties.Seed s) {
        String deviceId = s.getDeviceId();
        planReminderMapper.delete(new QueryWrapper<PlanReminder>().eq("plan_id", PLAN_ID));
        medPlanItemMapper.delete(new QueryWrapper<MedPlanItem>().eq("plan_id", PLAN_ID));
        medPlanMapper.delete(new QueryWrapper<MedPlan>().eq("plan_id", PLAN_ID));
        medicineStockMapper.delete(new QueryWrapper<MedicineStock>().eq("device_id", deviceId));
        deviceMapper.delete(new QueryWrapper<Device>().eq("device_id", deviceId));
        deviceMapper.delete(new QueryWrapper<Device>().likeRight("device_id", deviceId + "-S-"));
        medicineMapper.delete(new QueryWrapper<Medicine>().in("medicine_id", MED_PUBLIC, MED_PRIVATE));
        guardianRelationMapper.delete(new QueryWrapper<GuardianRelation>()
                .eq("elder_id", s.getElderUserId()).eq("guardian_id", s.getGuardianUserId()));
        userMapper.delete(new QueryWrapper<User>().in("user_id", s.getGuardianUserId(), s.getElderUserId()));
    }

    private void insertGuardianAndElder(DevtoolsProperties.Seed s) {
        User guardian = new User();
        guardian.setUserId(s.getGuardianUserId());
        guardian.setPhone(s.getGuardianPhone());
        guardian.setUsername("debug_guardian");
        guardian.setName(s.getGuardianName());
        guardian.setRole(UserRole.GUARDIAN);
        guardian.setPasswordHash(passwordHasher.hash(s.getGuardianPassword()));
        guardian.setStatus(UserStatus.ACTIVE);
        guardian.setCreatedAt(OffsetDateTime.now());
        userMapper.insert(guardian);

        User elder = new User();
        elder.setUserId(s.getElderUserId());
        elder.setPhone(s.getElderPhone());
        elder.setName(s.getElderName());
        elder.setRole(UserRole.ELDER);
        elder.setPasswordHash(passwordHasher.hash(s.getElderPassword()));
        elder.setStatus(UserStatus.ACTIVE);
        elder.setCreatedAt(OffsetDateTime.now());
        userMapper.insert(elder);

        GuardianRelation relation = new GuardianRelation();
        relation.setRelationId(RELATION_ID);
        relation.setElderId(s.getElderUserId());
        relation.setGuardianId(s.getGuardianUserId());
        relation.setStatus(RelationStatus.ACTIVE);
        relation.setCreatedAt(OffsetDateTime.now());
        guardianRelationMapper.insert(relation);
    }

    private void insertDevices(DevtoolsProperties.Seed s) {
        Device main = new Device();
        main.setDeviceId(s.getDeviceId());
        main.setDeviceType(DeviceType.MAIN);
        main.setParentDeviceId(null);
        main.setProductKey(s.getProductKey());
        main.setElderId(s.getElderUserId());
        main.setTimezone(s.getTimezone());
        main.setOnlineState(OnlineState.OFFLINE);
        main.setCreatedAt(OffsetDateTime.now());
        deviceMapper.insert(main);

        insertSensor(s, s.getDeviceId() + "-S-TH01", DeviceType.TEMP_HUMI);
        insertSensor(s, s.getDeviceId() + "-S-LX01", DeviceType.LIGHT);
        insertSensor(s, s.getDeviceId() + "-S-CAM01", DeviceType.CAMERA);
    }

    private void insertSensor(DevtoolsProperties.Seed s, String sensorId, DeviceType type) {
        Device sensor = new Device();
        sensor.setDeviceId(sensorId);
        sensor.setDeviceType(type);
        sensor.setParentDeviceId(s.getDeviceId());
        sensor.setProductKey(s.getProductKey());
        sensor.setElderId(s.getElderUserId());
        sensor.setTimezone(s.getTimezone());
        sensor.setOnlineState(OnlineState.OFFLINE);
        sensor.setCreatedAt(OffsetDateTime.now());
        deviceMapper.insert(sensor);
    }

    private void insertMedicines(DevtoolsProperties.Seed s) {
        Medicine publicMed = new Medicine();
        publicMed.setMedicineId(MED_PUBLIC);
        publicMed.setOwnerElderId(null);
        publicMed.setName("阿司匹林");
        publicMed.setSpec("100mg*30片");
        publicMed.setDosePerTime("1");
        publicMed.setUnit("片");
        publicMed.setContraindication("胃溃疡慎用");
        publicMed.setStorageCond("阴凉干燥处");
        publicMed.setCreatedAt(OffsetDateTime.now());
        medicineMapper.insert(publicMed);

        Medicine privateMed = new Medicine();
        privateMed.setMedicineId(MED_PRIVATE);
        privateMed.setOwnerElderId(s.getElderUserId());
        privateMed.setName("甲硝唑");
        privateMed.setSpec("0.2g*21片");
        privateMed.setDosePerTime("1");
        privateMed.setUnit("片");
        privateMed.setCreatedAt(OffsetDateTime.now());
        medicineMapper.insert(privateMed);
    }

    private void insertStock(DevtoolsProperties.Seed s) {
        MedicineStock stock = new MedicineStock();
        stock.setStockId(STOCK_ID);
        stock.setDeviceId(s.getDeviceId());
        stock.setElderId(s.getElderUserId());
        stock.setMedicineId(MED_PUBLIC);
        stock.setQuantity(30);
        stock.setProductionDate(LocalDate.of(2026, 1, 1));
        stock.setExpiryDate(LocalDate.of(2027, 12, 31));
        stock.setUpdatedAt(OffsetDateTime.now());
        medicineStockMapper.insert(stock);
    }

    private void insertPlanAndItem(DevtoolsProperties.Seed s) {
        // 计划服药时刻设为"设备本地当前时刻 +2 分钟"，使手动触发端点 / 定时扫描能立即命中，
        // 免去联调时手算 now 的摩擦（见 feat/plan-reminder-testability）
        ZoneId zone = ZoneId.of(s.getTimezone());
        String nearNow = LocalTime.now(zone).plusMinutes(2).format(DateTimeFormatter.ofPattern("HH:mm"));
        MedPlan plan = new MedPlan();
        plan.setPlanId(PLAN_ID);
        plan.setElderId(s.getElderUserId());
        plan.setName("调试临近提醒");
        plan.setTimes(List.of(nearNow));
        plan.setRepeatRule("DAILY");
        plan.setMissAlarmAfterMin(null);
        plan.setAlarmRule(Map.of("miss", true));
        plan.setStatus("ENABLED");
        plan.setSyncState("PENDING");
        plan.setCreatedAt(OffsetDateTime.now());
        medPlanMapper.insert(plan);

        MedPlanItem item = new MedPlanItem();
        item.setItemId(PLAN_ITEM_ID);
        item.setPlanId(PLAN_ID);
        item.setMedicineId(MED_PUBLIC);
        item.setDose("1");
        item.setUnit("片");
        item.setNote("餐后");
        item.setSortOrder(1);
        item.setCreatedAt(OffsetDateTime.now());
        medPlanItemMapper.insert(item);
    }

    private SeedResult result(boolean created, DevtoolsProperties.Seed s, List<String> medicineIds) {
        String guardianToken = jwtProvider.issue(s.getGuardianUserId(), UserRole.GUARDIAN.name(), TokenType.ACCESS);
        String elderToken = jwtProvider.issue(s.getElderUserId(), UserRole.ELDER.name(), TokenType.ACCESS);
        return new SeedResult(created, s.getGuardianUserId(), s.getGuardianPhone(), guardianToken,
                s.getElderUserId(), s.getElderPhone(), elderToken, s.getDeviceId(),
                medicineIds, STOCK_ID, PLAN_ID, PLAN_ITEM_ID);
    }
}
