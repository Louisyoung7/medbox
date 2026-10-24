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
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.domain.enums.RelationRole;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.domain.enums.UserStatus;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.MedicineMapper;
import com.medbox.server.mapper.MedicineStockMapper;
import com.medbox.server.mapper.MedPlanItemMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.UserMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * 用文档示例里的固定业务 ID（{@code u-1001} / {@code e-1001} / {@code BOXA1001} / {@code m-205} /
 * {@code p-3301} / {@code pi-9001}），让 02 §1.4、03 §5 的示例直接可跑。
 *
 * <p><b>幂等</b>：监护人已存在则整批跳过（直接返回已有 ID + 重新签发 token）；{@code reset=true}
 * 先按外键逆序清掉种子数据再重种。
 *
 * <p><b>不触发 plan-sync</b>：计划经 Mapper 直写，不会发 {@code PlanSyncEvent}，避免无设备时产生 SYNC_FAILED 噪音。
 */
@Service
@ConditionalOnProperty(prefix = "medbox.devtools", name = "enabled", havingValue = "true")
public class DevtoolsSeedService {

    /** 公共药品业务 ID（与文档示例对齐）。 */
    private static final String MED_PUBLIC = "m-205";

    /** 老人私有药品业务 ID。 */
    private static final String MED_PRIVATE = "m-206";

    /** 库存业务 ID。 */
    private static final String STOCK_ID = "s-1001";

    /** 计划业务 ID（与文档示例对齐）。 */
    private static final String PLAN_ID = "p-3301";

    /** 计划项业务 ID（与文档示例对齐）。 */
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
    private final PasswordHasher passwordHasher;
    private final JwtProvider jwtProvider;
    private final DevtoolsProperties props;

    public DevtoolsSeedService(UserMapper userMapper, GuardianRelationMapper guardianRelationMapper,
            DeviceMapper deviceMapper, MedicineMapper medicineMapper,
            MedicineStockMapper medicineStockMapper, MedPlanMapper medPlanMapper,
            MedPlanItemMapper medPlanItemMapper, PasswordHasher passwordHasher,
            JwtProvider jwtProvider, DevtoolsProperties props) {
        this.userMapper = userMapper;
        this.guardianRelationMapper = guardianRelationMapper;
        this.deviceMapper = deviceMapper;
        this.medicineMapper = medicineMapper;
        this.medicineStockMapper = medicineStockMapper;
        this.medPlanMapper = medPlanMapper;
        this.medPlanItemMapper = medPlanItemMapper;
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

    private boolean existsGuardian(String guardianUserId) {
        return userMapper.selectCount(new QueryWrapper<User>().eq("user_id", guardianUserId)) > 0;
    }

    private void clearSeed(DevtoolsProperties.Seed s) {
        String deviceId = s.getDeviceId();
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
        elder.setName(s.getElderName());
        elder.setRole(UserRole.ELDER);
        elder.setStatus(UserStatus.ACTIVE);
        elder.setCreatedAt(OffsetDateTime.now());
        userMapper.insert(elder);

        GuardianRelation relation = new GuardianRelation();
        relation.setRelationId(RELATION_ID);
        relation.setElderId(s.getElderUserId());
        relation.setGuardianId(s.getGuardianUserId());
        relation.setRole(RelationRole.FAMILY);
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
        MedPlan plan = new MedPlan();
        plan.setPlanId(PLAN_ID);
        plan.setElderId(s.getElderUserId());
        plan.setName("早餐后");
        plan.setTimes(List.of("08:00"));
        plan.setRepeatRule("DAILY");
        plan.setMissAlarmAfterMin(null);
        plan.setAlarmRule(Map.of("miss", true, "wrongDrug", true));
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
        String token = jwtProvider.issue(s.getGuardianUserId(), UserRole.GUARDIAN.name(), TokenType.ACCESS);
        return new SeedResult(created, s.getGuardianUserId(), s.getGuardianPhone(), token,
                s.getElderUserId(), s.getDeviceId(), medicineIds, STOCK_ID, PLAN_ID, PLAN_ITEM_ID);
    }
}
