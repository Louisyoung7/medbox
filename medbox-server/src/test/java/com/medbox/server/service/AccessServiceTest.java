package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.common.security.AccessIdentity;
import com.medbox.server.common.security.AuthContext;
import com.medbox.server.common.security.DeviceOwnerResolver;
import com.medbox.server.common.security.Permission;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.UserMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 权限判定规则表的全矩阵测试（07 清单 {@code feat/backend-authz} 的 🧭 规则表）。
 * 监护人已统一为单一 GUARDIAN 角色（文档 10 §1.2），故矩阵只有「老人本人 / 监护人」两行。
 *
 * <p>Mapper 全部用 Mockito 打桩 —— **不启容器、不连库**（CI 里没有 PostgreSQL）。
 * 每个用例对应规则表里的一格或一条边界口径。
 */
class AccessServiceTest {

    private static final String ELDER_ID = "u-1001";
    private static final String OTHER_ELDER_ID = "u-1002";
    private static final String GUARDIAN_ID = "u-2001";

    private final UserMapper userMapper = mock(UserMapper.class);
    private final GuardianRelationMapper relationMapper = mock(GuardianRelationMapper.class);

    private AccessService accessService;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<DeviceOwnerResolver> provider = mock(ObjectProvider.class);
        // 未注册 DeviceOwnerResolver 实现（feat/device-core 尚未接入）
        when(provider.getIfAvailable()).thenReturn(null);
        accessService = new AccessService(userMapper, relationMapper, provider);
        // 默认场景：目标是一位老人、且当前用户与他没有任何监护关系
        when(userMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<User>>any())).thenReturn(elder(ELDER_ID));
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    // —— 第 ① 步：是否登录 —— //

    @Test
    @DisplayName("① 未登录（无 AuthContext）→ 40101")
    void 未登录_抛_40101() {
        BizException ex = assertThrows(BizException.class,
                () -> accessService.require(ELDER_ID, Permission.READ_PLAN));
        assertEquals(ErrorCode.UNAUTHORIZED.code(), ex.code());
    }

    // —— 第 ② 步：是否有关系 —— //

    @Test
    @DisplayName("② 老人访问自己的数据 → 放行，身份 SELF")
    void 老人访问自己的数据_身份为_SELF() {
        login(ELDER_ID, "ELDER");
        AccessIdentity identity = accessService.require(ELDER_ID, Permission.READ_PLAN);
        assertEquals(AccessIdentity.Kind.SELF, identity.kind());
        assertEquals(ELDER_ID, identity.userId());
        assertEquals(ELDER_ID, identity.elderId());
        assertTrue(identity.isSelf());
    }

    @Test
    @DisplayName("② 老人访问他人数据 → 40301")
    void 老人访问他人数据_抛_40301() {
        login(ELDER_ID, "ELDER");
        BizException ex = assertThrows(BizException.class,
                () -> accessService.require(OTHER_ELDER_ID, Permission.READ_PLAN));
        assertEquals(ErrorCode.FORBIDDEN.code(), ex.code());
    }

    @Test
    @DisplayName("② 监护人与目标老人无任何关系 → 40301")
    void 监护人无关系_抛_40301() {
        login(GUARDIAN_ID, "GUARDIAN");
        BizException ex = assertThrows(BizException.class,
                () -> accessService.require(ELDER_ID, Permission.READ_PLAN));
        assertEquals(ErrorCode.FORBIDDEN.code(), ex.code());
    }

    @Test
    @DisplayName("② 目标 elderId 不是老人（是监护人账号 / 不存在）→ 40301")
    void 目标不是老人_抛_40301() {
        login(GUARDIAN_ID, "GUARDIAN");
        when(userMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<User>>any())).thenReturn(null);
        BizException ex = assertThrows(BizException.class,
                () -> accessService.require(OTHER_ELDER_ID, Permission.READ_PLAN));
        assertEquals(ErrorCode.FORBIDDEN.code(), ex.code());
    }

    // —— 第 ③ 步：关系是否生效 —— //

    @Test
    @DisplayName("③ 关系为 PENDING → 40302（即使该权限本该放行）")
    void 关系为_PENDING_抛_40302() {
        login(GUARDIAN_ID, "GUARDIAN");
        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationStatus.PENDING));
        BizException ex = assertThrows(BizException.class,
                () -> accessService.require(ELDER_ID, Permission.WRITE_PLAN));
        assertEquals(ErrorCode.GUARDIAN_PENDING.code(), ex.code());
    }

    @Test
    @DisplayName("③ 关系为 REVOKED → 40301（与无关系同码，不回 40302）")
    void 关系为_REVOKED_抛_40301() {
        login(GUARDIAN_ID, "GUARDIAN");
        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationStatus.REVOKED));
        BizException ex = assertThrows(BizException.class,
                () -> accessService.require(ELDER_ID, Permission.READ_PLAN));
        assertEquals(ErrorCode.FORBIDDEN.code(), ex.code());
    }

    @Test
    @DisplayName("③ 关系状态为 NULL（脏数据）→ 40301")
    void 关系状态为空_抛_40301() {
        login(GUARDIAN_ID, "GUARDIAN");
        givenRelation(relation(ELDER_ID, GUARDIAN_ID, null));
        BizException ex = assertThrows(BizException.class,
                () -> accessService.require(ELDER_ID, Permission.READ_PLAN));
        assertEquals(ErrorCode.FORBIDDEN.code(), ex.code());
    }

    // —— 第 ④ 步：角色是否具备此操作权限 —— //

    @Test
    @DisplayName("④ ACTIVE 监护人：可读计划、可写计划（统一角色，无子角色差异）")
    void 监护人可读可写计划() {
        login(GUARDIAN_ID, "GUARDIAN");
        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationStatus.ACTIVE));
        assertEquals(AccessIdentity.Kind.GUARDIAN, accessService.require(ELDER_ID, Permission.READ_PLAN).kind());
        assertEquals(AccessIdentity.Kind.GUARDIAN, accessService.require(ELDER_ID, Permission.WRITE_PLAN).kind());
    }

    @Test
    @DisplayName("④ 监护人：可处理告警、可导出记录、可写药品、可删抓拍（统一放行）")
    void 监护人统一放行写操作() {
        login(GUARDIAN_ID, "GUARDIAN");
        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationStatus.ACTIVE));
        assertEquals(AccessIdentity.Kind.GUARDIAN, accessService.require(ELDER_ID, Permission.HANDLE_ALARM).kind());
        assertEquals(AccessIdentity.Kind.GUARDIAN, accessService.require(ELDER_ID, Permission.EXPORT_RECORD).kind());
        assertEquals(AccessIdentity.Kind.GUARDIAN, accessService.require(ELDER_ID, Permission.WRITE_MEDICINE).kind());
        assertEquals(AccessIdentity.Kind.GUARDIAN, accessService.require(ELDER_ID, Permission.WRITE_CAPTURE).kind());
    }

    @Test
    @DisplayName("④ 老人只读：READ_PLAN 放行，WRITE_PLAN → 40301")
    void 老人只读_写计划抛_40301() {
        login(ELDER_ID, "ELDER");
        assertEquals(AccessIdentity.Kind.SELF, accessService.require(ELDER_ID, Permission.READ_PLAN).kind());
        assertEquals(ErrorCode.FORBIDDEN.code(),
                assertThrows(BizException.class,
                        () -> accessService.require(ELDER_ID, Permission.WRITE_PLAN)).code());
    }

    @Test
    @DisplayName("④ 抓拍：监护人 / 老人本人可 READ_CAPTURE，老人写 → 40301")
    void 抓拍可见范围() {
        login(GUARDIAN_ID, "GUARDIAN");
        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationStatus.ACTIVE));
        assertEquals(AccessIdentity.Kind.GUARDIAN, accessService.require(ELDER_ID, Permission.READ_CAPTURE).kind());

        login(ELDER_ID, "ELDER");
        assertEquals(AccessIdentity.Kind.SELF, accessService.require(ELDER_ID, Permission.READ_CAPTURE).kind());
        assertEquals(ErrorCode.FORBIDDEN.code(),
                assertThrows(BizException.class,
                        () -> accessService.require(ELDER_ID, Permission.WRITE_CAPTURE)).code());
    }

    @Test
    @DisplayName("④ 账号角色不在枚举内 → 40301")
    void 账号角色非法_抛_40301() {
        login(GUARDIAN_ID, "ADMIN");
        BizException ex = assertThrows(BizException.class,
                () -> accessService.require(ELDER_ID, Permission.READ_PLAN));
        assertEquals(ErrorCode.FORBIDDEN.code(), ex.code());
    }

    @Test
    @DisplayName("④ 监护人（统一角色）可读计划 / 记录 / 设备 / 环境 / 依从性 / 告警 / 药品 / 监护关系")
    void 只读类权限对监护人放行() {
        login(GUARDIAN_ID, "GUARDIAN");
        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationStatus.ACTIVE));
        for (Permission permission : new Permission[]{Permission.READ_PLAN, Permission.READ_RECORD,
                Permission.READ_DEVICE, Permission.READ_ENV, Permission.READ_ADHERENCE,
                Permission.READ_ALARM, Permission.READ_MEDICINE, Permission.READ_GUARDIAN}) {
            assertTrue(Permission.allows(accessService.require(ELDER_ID, permission), permission),
                    "监护人应可 " + permission);
        }
    }

    @Test
    @DisplayName("④ AI 洞察：老人本人与监护人都可问")
    void AI洞察权限() {
        login(ELDER_ID, "ELDER");
        assertEquals(AccessIdentity.Kind.SELF, accessService.require(ELDER_ID, Permission.AI_INSIGHT).kind());

        login(GUARDIAN_ID, "GUARDIAN");
        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationStatus.ACTIVE));
        assertEquals(AccessIdentity.Kind.GUARDIAN, accessService.require(ELDER_ID, Permission.AI_INSIGHT).kind());

        // 无监护关系 → 40301（重打桩：抹掉前面 givenRelation 留下的 ACTIVE 桩）
        login(GUARDIAN_ID, "GUARDIAN");
        when(relationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any())).thenReturn(null);
        assertEquals(ErrorCode.FORBIDDEN.code(),
                assertThrows(BizException.class,
                        () -> accessService.require(ELDER_ID, Permission.AI_INSIGHT)).code());
    }

    // —— 可见范围（列表接口行级过滤） —— //

    @Test
    @DisplayName("可见老人集合：老人只有自己")
    void 可见老人集合_老人只有自己() {
        login(ELDER_ID, "ELDER");
        assertEquals(Set.of(ELDER_ID), accessService.visibleElderIds());
    }

    @Test
    @DisplayName("可见老人集合：监护人是全部 ACTIVE 关系下的老人")
    void 可见老人集合_监护人取所有生效关系() {
        login(GUARDIAN_ID, "GUARDIAN");
        when(relationMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any())).thenReturn(List.of(
                relation(ELDER_ID, GUARDIAN_ID, RelationStatus.ACTIVE),
                relation(OTHER_ELDER_ID, GUARDIAN_ID, RelationStatus.ACTIVE)));
        assertEquals(Set.of(ELDER_ID, OTHER_ELDER_ID), accessService.visibleElderIds());
    }

    @Test
    @DisplayName("ACTIVE 关系列表：按老人查监护关系")
    void 查指定老人的生效监护关系() {
        login(GUARDIAN_ID, "GUARDIAN");
        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationStatus.ACTIVE));
        when(relationMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(List.of(relation(ELDER_ID, GUARDIAN_ID, RelationStatus.ACTIVE)));
        assertEquals(1, accessService.activeRelationsOf(ELDER_ID).size());
    }

    // —— 设备维度（扩展点，未接入） —— //

    @Test
    @DisplayName("设备维度：DeviceOwnerResolver 未接入 → 50000")
    void 设备归属解析未接入_抛_50000() {
        login(ELDER_ID, "ELDER");
        BizException ex = assertThrows(BizException.class,
                () -> accessService.requireDevice("BOXA1001", Permission.READ_DEVICE));
        assertEquals(ErrorCode.INTERNAL_ERROR.code(), ex.code());
    }

    // —— 参数校验 —— //

    @Test
    @DisplayName("elderId 为空 → 40001")
    void elderId为空_抛_40001() {
        login(ELDER_ID, "ELDER");
        BizException ex = assertThrows(BizException.class,
                () -> accessService.require("  ", Permission.READ_PLAN));
        assertEquals(ErrorCode.BAD_REQUEST.code(), ex.code());
    }

    @Test
    @DisplayName("permission 为 null → 40001")
    void permission为空_抛_40001() {
        login(ELDER_ID, "ELDER");
        BizException ex = assertThrows(BizException.class, () -> accessService.require(ELDER_ID, null));
        assertEquals(ErrorCode.BAD_REQUEST.code(), ex.code());
    }

    // —— 辅助方法 —— //

    private static void login(String userId, String role) {
        AuthContext.set(new AuthContext.CurrentUser(userId, role));
    }

    private void givenRelation(GuardianRelation relation) {
        when(relationMapper.selectOne(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any())).thenReturn(relation);
    }

    private static GuardianRelation relation(String elderId, String guardianId, RelationStatus status) {
        GuardianRelation relation = new GuardianRelation();
        relation.setRelationId("r-1001");
        relation.setElderId(elderId);
        relation.setGuardianId(guardianId);
        relation.setStatus(status);
        return relation;
    }

    private static User elder(String userId) {
        User user = new User();
        user.setUserId(userId);
        user.setRole(UserRole.ELDER);
        user.setName("张三");
        user.setPhone("13800001234");
        user.setUsername("zhangsan");
        return user;
    }
}
