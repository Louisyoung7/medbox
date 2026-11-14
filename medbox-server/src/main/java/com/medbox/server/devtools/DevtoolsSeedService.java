package com.medbox.server.devtools;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.PasswordHasher;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.devtools.dto.SeedResult;
import com.medbox.server.domain.Device;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.Medicine;
import com.medbox.server.domain.MedPlan;
import com.medbox.server.domain.PlanReminder;
import com.medbox.server.domain.PlanSlot;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.DeviceType;
import com.medbox.server.domain.enums.OnlineState;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.domain.enums.UserStatus;
import com.medbox.server.mapper.DeviceMapper;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.MedicineMapper;
import com.medbox.server.mapper.MedPlanMapper;
import com.medbox.server.mapper.PlanReminderMapper;
import com.medbox.server.mapper.UserMapper;
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
 * <p><b>计划按格排程</b>：种子计划只写 {@code slots}（格号 → 开格时间），不再写计划药品明细；
 * 种子计划不触发 {@code PlanSyncEvent}（无设备时不产生 SYNC_FAILED 噪音）。
 *
 * <p><b>幂等</b>：监护人已存在则整批跳过；{@code reset=true} 先按外键逆序清掉种子数据再重种。
 */
@Service
@ConditionalOnProperty(prefix = "medbox.devtools", name = "enabled", havingValue = "true")
public class DevtoolsSeedService {

    private static final String MED_SEED = "m-206";
    private static final String PLAN_ID = "p-3301";
    private static final String RELATION_ID = "r-debug-001";

    private final UserMapper userMapper;
    private final GuardianRelationMapper guardianRelationMapper;
    private final DeviceMapper deviceMapper;
    private final MedicineMapper medicineMapper;
    private final MedPlanMapper medPlanMapper;
    private final PlanReminderMapper planReminderMapper;
    private final PasswordHasher passwordHasher;
    private final JwtProvider jwtProvider;
    private final DevtoolsProperties props;

    public DevtoolsSeedService(UserMapper userMapper, GuardianRelationMapper guardianRelationMapper,
            DeviceMapper deviceMapper, MedicineMapper medicineMapper,
            MedPlanMapper medPlanMapper,
            PlanReminderMapper planReminderMapper,
            PasswordHasher passwordHasher, JwtProvider jwtProvider, DevtoolsProperties props) {
        this.userMapper = userMapper;
        this.guardianRelationMapper = guardianRelationMapper;
        this.deviceMapper = deviceMapper;
        this.medicineMapper = medicineMapper;
        this.medPlanMapper = medPlanMapper;
        this.planReminderMapper = planReminderMapper;
        this.passwordHasher = passwordHasher;
        this.jwtProvider = jwtProvider;
        this.props = props;
    }

    @Transactional
    public SeedResult seed(boolean reset) {
        DevtoolsProperties.Seed s = props.getSeed();
        List<String> medicineIds = List.of(MED_SEED);
        if (reset) {
            clearSeed(s);
            insertAll(s);
            return result(true, s, medicineIds);
        }
        if (existsGuardian(s.getGuardianUserId())) {
            return result(false, s, medicineIds);
        }
        clearSeed(s);
        insertAll(s);
        return result(true, s, medicineIds);
    }

    private void insertAll(DevtoolsProperties.Seed s) {
        insertGuardianAndElder(s);
        insertDevices(s);
        insertMedicines(s);
        insertPlan(s);
    }

    /** 必须同时校验角色，避免任何占用该 ID 的账号被误判为"种子已存在"。 */
    private boolean existsGuardian(String guardianUserId) {
        return userMapper.selectCount(new QueryWrapper<User>()
                .eq("user_id", guardianUserId)
                .eq("role", UserRole.GUARDIAN.name())) > 0;
    }

    private void clearSeed(DevtoolsProperties.Seed s) {
        String deviceId = s.getDeviceId();
        planReminderMapper.delete(new QueryWrapper<PlanReminder>().eq("plan_id", PLAN_ID));
        medPlanMapper.delete(new QueryWrapper<MedPlan>().eq("plan_id", PLAN_ID));
        deviceMapper.delete(new QueryWrapper<Device>().eq("device_id", deviceId));
        deviceMapper.delete(new QueryWrapper<Device>().likeRight("device_id", deviceId + "-S-"));
        medicineMapper.delete(new QueryWrapper<Medicine>().in("medicine_id", MED_SEED));
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
        Medicine med = new Medicine();
        med.setMedicineId(MED_SEED);
        med.setOwnerElderId(s.getElderUserId());
        med.setName("甲硝唑");
        med.setDosePerTime("1");
        med.setUnit("片");
        med.setCreatedAt(OffsetDateTime.now());
        medicineMapper.insert(med);
    }

    /** 只排 1 号格，时间为设备本地当前时刻 +2 分钟，便于提醒扫描立即命中；不含药品明细。 */
    private void insertPlan(DevtoolsProperties.Seed s) {
        ZoneId zone = ZoneId.of(s.getTimezone());
        String nearNow = LocalTime.now(zone).plusMinutes(2).format(DateTimeFormatter.ofPattern("HH:mm"));
        PlanSlot slot = new PlanSlot();
        slot.setCellNo(1);
        slot.setTime(nearNow);

        MedPlan plan = new MedPlan();
        plan.setPlanId(PLAN_ID);
        plan.setElderId(s.getElderUserId());
        plan.setName("调试临近提醒");
        plan.setSlots(List.of(slot));
        plan.setRepeatRule("DAILY");
        plan.setMissAlarmAfterMin(null);
        plan.setAlarmRule(Map.of("miss", true));
        plan.setStatus("ENABLED");
        plan.setSyncState("PENDING");
        plan.setCreatedAt(OffsetDateTime.now());
        medPlanMapper.insert(plan);
    }

    private SeedResult result(boolean created, DevtoolsProperties.Seed s, List<String> medicineIds) {
        String guardianToken = jwtProvider.issue(s.getGuardianUserId(), UserRole.GUARDIAN.name(), TokenType.ACCESS);
        String elderToken = jwtProvider.issue(s.getElderUserId(), UserRole.ELDER.name(), TokenType.ACCESS);
        return new SeedResult(created, s.getGuardianUserId(), s.getGuardianPhone(), guardianToken,
                s.getElderUserId(), s.getElderPhone(), elderToken, s.getDeviceId(),
                medicineIds, PLAN_ID);
    }
}
