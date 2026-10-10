package com.medbox.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.common.security.AccessIdentity;
import com.medbox.server.common.security.AuthContext;
import com.medbox.server.common.security.DeviceOwnerResolver;
import com.medbox.server.common.security.Permission;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.RelationRole;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.UserMapper;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 权限与监护关系校验（文档 01 第 3 章、02 的 1.3）—— **后续所有业务分支的唯一调用点**。
 *
 * <p>四步校验顺序（短路，先命中先返回）：
 *
 * <ol>
 *   <li><b>是否登录</b>：取 {@link AuthContext#required()}，未登录 / token 无效一律 <b>40101</b>
 *       （由 {@code AuthInterceptor} 与 {@code JwtProvider} 保证，本类只是再兜一层）；</li>
 *   <li><b>是否有关系</b>：老人本人 → 目标必须是自己；监护人 → 必须存在 {@code guardian_relation}
 *       记录、且目标确实是老人。不满足一律 <b>40301</b>；</li>
 *   <li><b>关系是否生效</b>：状态为 {@code PENDING} → <b>40302</b>（老人端尚未确认）；
 *       {@code REVOKED} 与状态为空（脏数据）→ <b>40301</b>（与"无关系"同码，避免泄漏"曾有关系"）；</li>
 *   <li><b>角色操作权限</b>：查 {@link Permission#allows} 的集中矩阵，不命中 → <b>40301</b>。</li>
 * </ol>
 *
 * <p><b>为什么判定放 Service 而不是拦截器</b>：拦截器拿不到"目标资源归属老人"的语义（要从
 * query / path / body 里解析业务参数），把权限塞进拦截器会逼它理解每一个接口的入参，得不偿失。
 * 因此第 ① 步留在拦截器（现状如此），第 ②③④ 步由业务接口显式调用本类。多一行调用，换来确定性。
 *
 * <p><b>越权一律 40301，不返回 40401</b>：区分"资源不存在"与"无权限"会让攻击者枚举老人 ID /
 * 计划 ID（见 02 的 1.3 第 ② 步）。唯一例外是 {@code GET /users/me} —— 那是当前用户自己的
 * 资源，不存在时返回 40401 不泄漏任何东西。
 *
 * <p><b>非 Web 线程不要用本类</b>：定时任务、MQTT 消费线程没有 {@code AuthContext}，
 * 调用会直接 40101。这些内部场景请直接调 Mapper，不要伪装成某个用户来绕过权限。
 */
@Service
public class AccessService {

    private static final Logger log = LoggerFactory.getLogger(AccessService.class);

    /** 越权的统一提示：不区分"资源不存在"与"无权限"，防资源枚举。 */
    private static final String FORBIDDEN_MESSAGE = "无权限访问该资源";

    private final UserMapper userMapper;
    private final GuardianRelationMapper guardianRelationMapper;
    private final ObjectProvider<DeviceOwnerResolver> deviceOwnerResolver;

    public AccessService(UserMapper userMapper,
                         GuardianRelationMapper guardianRelationMapper,
                         ObjectProvider<DeviceOwnerResolver> deviceOwnerResolver) {
        this.userMapper = userMapper;
        this.guardianRelationMapper = guardianRelationMapper;
        this.deviceOwnerResolver = deviceOwnerResolver;
    }

    /**
     * 权限校验主入口：通过返回访问身份，不通过抛 {@code BizException}。
     *
     * @param elderId    目标资源归属老人的业务 user_id
     * @param permission 本次操作所需的权限
     * @throws BizException 40101 未登录 / 40301 越权或角色无权限 / 40302 监护关系未生效
     */
    public AccessIdentity require(String elderId, Permission permission) {
        BizAssert.notBlank(elderId, "elderId 不能为空");
        BizAssert.badRequestIf(permission == null, "permission 不能为空");

        // ① 是否登录：未登录 / token 无效 → 40101
        AuthContext.CurrentUser current = AuthContext.required();

        // ②③ 关系是否存在且生效
        AccessIdentity identity = resolveIdentity(current, elderId);

        // ④ 该角色是否具备此操作权限
        BizAssert.forbiddenIf(!Permission.allows(identity, permission),
                FORBIDDEN_MESSAGE + "（" + permission.name() + "）");
        return identity;
    }

    /**
     * 设备维度校验：先把 {@code deviceId} 解析成归属老人，再走 {@link #require}。
     *
     * <p><b>依赖 {@code feat/device-core}</b>：需要它提供 {@link DeviceOwnerResolver} 实现
     * （查 {@code device} 表；传感器子设备经 {@code parent_device_id} 反查所属药箱）。
     * 当前未注册实现 → 抛 50000 并在日志提示，接入后无需改动本方法。
     *
     * <p>设备查不到同样返回 <b>40301</b>（不是 40401）：与第 ② 步同一口径，避免枚举设备 ID。
     */
    public AccessIdentity requireDevice(String deviceId, Permission permission) {
        BizAssert.notBlank(deviceId, "deviceId 不能为空");
        BizAssert.badRequestIf(permission == null, "permission 不能为空");

        DeviceOwnerResolver resolver = deviceOwnerResolver.getIfAvailable();
        BizAssert.isTrue(resolver != null, ErrorCode.INTERNAL_ERROR,
                "设备归属解析未接入：等 feat/device-core 引入 DeviceMapper 后补 DeviceOwnerResolver 实现");

        String elderId = resolver.resolveElderId(deviceId);
        BizAssert.forbiddenIf(elderId == null, FORBIDDEN_MESSAGE);
        return require(elderId, permission);
    }

    /**
     * 当前登录用户**可见的老人 ID 集合**：老人本人只有自己；监护人是所有 ACTIVE 关系下的老人。
     *
     * <p>给列表类接口做行级过滤（如 {@code feat/medicine-core} 的"公共药品 + 我监护老人的私有药品"），
     * 用 IN 一次过滤，避免逐条判定带来的 N+1。
     */
    public Set<String> visibleElderIds() {
        AuthContext.CurrentUser current = AuthContext.required();
        UserRole role = UserRole.parse(current.role());
        BizAssert.forbiddenIf(role == null, FORBIDDEN_MESSAGE);

        if (role == UserRole.ELDER) {
            return Set.of(current.userId());
        }
        return guardianRelationMapper.selectList(new LambdaQueryWrapper<GuardianRelation>()
                        .eq(GuardianRelation::getGuardianId, current.userId())
                        .eq(GuardianRelation::getStatus, RelationStatus.ACTIVE))
                .stream()
                .map(GuardianRelation::getElderId)
                .collect(Collectors.toSet());
    }

    /**
     * 指定老人当前的 ACTIVE 监护关系（含关系角色），供 {@code feat/guardian-relation}
     * 的监护列表与告警推送分支复用。内部先校验 {@code READ_GUARDIAN} 权限。
     */
    public List<GuardianRelation> activeRelationsOf(String elderId) {
        require(elderId, Permission.READ_GUARDIAN);
        return guardianRelationMapper.selectList(new LambdaQueryWrapper<GuardianRelation>()
                .eq(GuardianRelation::getElderId, elderId)
                .eq(GuardianRelation::getStatus, RelationStatus.ACTIVE));
    }

    /**
     * 第 ②③ 步：判定访问身份。不通过时抛 40301（无关系 / REVOKED / 目标不是老人）
     * 或 40302（PENDING）。
     */
    private AccessIdentity resolveIdentity(AuthContext.CurrentUser current, String elderId) {
        UserRole role = UserRole.parse(current.role());
        BizAssert.forbiddenIf(role == null, FORBIDDEN_MESSAGE);

        if (role == UserRole.ELDER) {
            // 老人本人：只能访问自己的数据；访问他人 elderId 一律 40301（不区分"不存在"，防枚举）
            BizAssert.forbiddenIf(!current.userId().equals(elderId), FORBIDDEN_MESSAGE);
            return AccessIdentity.self(current.userId());
        }

        // 监护人：目标必须是老人，且与自己存在监护关系
        BizAssert.forbiddenIf(!isElder(elderId), FORBIDDEN_MESSAGE);

        GuardianRelation relation = findRelation(elderId, current.userId());
        BizAssert.forbiddenIf(relation == null, FORBIDDEN_MESSAGE);

        RelationStatus status = relation.getStatus();
        // 无状态（脏数据）与 REVOKED 同码：不回 40302，避免泄漏"曾经有过关系"
        BizAssert.forbiddenIf(status == null || status == RelationStatus.REVOKED, FORBIDDEN_MESSAGE);
        BizAssert.pendingIf(status == RelationStatus.PENDING, "监护关系未生效，请等待老人确认");

        return AccessIdentity.guardian(current.userId(), elderId, relationRoleOf(relation));
    }

    /** 关系角色；列为 NULL 时按 {@link RelationRole#DEFAULT_ROLE} 兜底并打 warn。 */
    private String relationRoleOf(GuardianRelation relation) {
        RelationRole role = relation.getRole();
        if (role == null) {
            log.warn("监护关系 role 为空，按 {} 兜底处理, relationId={}",
                RelationRole.DEFAULT_ROLE, relation.getRelationId());
            return RelationRole.DEFAULT_ROLE.name();
        }
        return role.name();
    }

    private GuardianRelation findRelation(String elderId, String guardianId) {
        return guardianRelationMapper.selectOne(new LambdaQueryWrapper<GuardianRelation>()
                .eq(GuardianRelation::getElderId, elderId)
                .eq(GuardianRelation::getGuardianId, guardianId));
    }

    private boolean isElder(String userId) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUserId, userId));
        return user != null && user.getRole() == UserRole.ELDER;
    }
}
