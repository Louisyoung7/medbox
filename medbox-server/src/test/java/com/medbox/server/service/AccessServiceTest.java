package com.medbox.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.medbox.server.domain.enums.RelationRole;
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
        assertNull(identity.relationRole());
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
        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationRole.FAMILY, RelationStatus.PENDING));

        BizException ex = assertThrows(BizException.class,
                () -> accessService.require(ELDER_ID, Permission.WRITE_PLAN));

        assertEquals(ErrorCode.GUARDIAN_PENDING.code(), ex.code());
    }

    @Test
    @DisplayName("③ 关系为 REVOKED → 40301（与无关系同码，不回 40302）")
    void 关系为_REVOKED_抛_40301() {
        login(GUARDIAN_ID, "GUARDIAN");
        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationRole.FAMILY, RelationStatus.REVOKED));

        BizException ex = assertThrows(BizException.class,
                () -> accessService.require(ELDER_ID, Permission.READ_PLAN));

        assertEquals(ErrorCode.FORBIDDEN.code(), ex.code());
    }

    @Test
    @DisplayName("③ 关系状态为 NULL（脏数据）→ 40301")
    void 关系状态为空_抛_40301() {
        login(GUARDIAN_ID, "GUARDIAN");
        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationRole.FAMILY, null));

        BizException ex = assertThrows(BizException.class,
                () -> accessService.require(ELDER_ID, Permission.READ_PLAN));

        assertEquals(ErrorCode.FORBIDDEN.code(), ex.code());
    }

    // —— 第 ④ 步：角色是否具备此操作权限 —— //

    @Test
    @DisplayName("④ ACTIVE 家属：可读计划、可写计划")
    void 家属可读可写计划() {
        login(GUARDIAN_ID, "GUARDIAN");
        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationRole.FAMILY, RelationStatus.ACTIVE));

        AccessIdentity read = accessService.require(ELDER_ID, Permission.READ_PLAN);
        AccessIdentity write = accessService.require(ELDER_ID, Permission.WRITE_PLAN);

        assertEquals(AccessIdentity.Kind.GUARDIAN, read.kind());
        assertEquals("FAMILY", read.relationRole());
        assertEquals("FAMILY", write.relationRole());
    }

    @Test
    @DisplayName("④ 护理：不可写计划，但可处理告警、可导出记录")
    void 护理可处理告警但不可写计划() {
        login(GUARDIAN_ID, "GUARDIAN");
        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationRole.NURSE, RelationStatus.ACTIVE));

        assertEquals(ErrorCode.FORBIDDEN.code(),
                assertThrows(BizException.class,
                        () -> accessService.require(ELDER_ID, Permission.WRITE_PLAN)).code());

        AccessIdentity handled = accessService.require(ELDER_ID, Permission.HANDLE_ALARM);
        assertEquals("NURSE", handled.relationRole());
        assertEquals(AccessIdentity.Kind.GUARDIAN, accessService.require(ELDER_ID, Permission.EXPORT_RECORD).kind());
    }

    @Test
    @DisplayName("④ 医生：可写计划、可维护禁忌知识库，但不可写药品")
    void 医生可写计划与知识库但不可写药品() {
        login(GUARDIAN_ID, "GUARDIAN");
        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationRole.DOCTOR, RelationStatus.ACTIVE));

        assertEquals("DOCTOR", accessService.require(ELDER_ID, Permission.WRITE_PLAN).relationRole());
        assertEquals("DOCTOR", accessService.require(ELDER_ID, Permission.WRITE_DRUG_MANUAL).relationRole());
        assertEquals(ErrorCode.FORBIDDEN.code(),
                assertThrows(BizException.class,
                        () -> accessService.require(ELDER_ID, Permission.WRITE_MEDICINE)).code());
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
    @DisplayName("④ 抓拍口径更严格：护理 / 医生 READ_CAPTURE → 40301，家属与老人本人放行")
    void 抓拍可见范围更严格() {
        login(GUARDIAN_ID, "GUARDIAN");
        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationRole.NURSE, RelationStatus.ACTIVE));
        assertEquals(ErrorCode.FORBIDDEN.code(),
                assertThrows(BizException.class,
                        () -> accessService.require(ELDER_ID, Permission.READ_CAPTURE)).code());

        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationRole.DOCTOR, RelationStatus.ACTIVE));
        assertEquals(ErrorCode.FORBIDDEN.code(),
                assertThrows(BizException.class,
                        () -> accessService.require(ELDER_ID, Permission.READ_CAPTURE)).code());

        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationRole.FAMILY, RelationStatus.ACTIVE));
        assertEquals("FAMILY", accessService.require(ELDER_ID, Permission.READ_CAPTURE).relationRole());

        login(ELDER_ID, "ELDER");
        assertNull(accessService.require(ELDER_ID, Permission.READ_CAPTURE).relationRole());
    }

    @Test
    @DisplayName("④ 关系 role 为 NULL → 按 FAMILY 兜底放行写计划")
    void 关系角色为空_按家属兜底() {
        login(GUARDIAN_ID, "GUARDIAN");
        givenRelation(relation(ELDER_ID, GUARDIAN_ID, null, RelationStatus.ACTIVE));

        assertEquals("FAMILY", accessService.require(ELDER_ID, Permission.WRITE_PLAN).relationRole());
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
    @DisplayName("④ 所有监护角色都能读计划 / 读记录 / 读设备 / 读环境 / 读依从性")
    void 只读类权限对所有监护角色放行() {
        for (RelationRole role : RelationRole.values()) {
            login(GUARDIAN_ID, "GUARDIAN");
            givenRelation(relation(ELDER_ID, GUARDIAN_ID, role, RelationStatus.ACTIVE));

            for (Permission permission : new Permission[]{Permission.READ_PLAN, Permission.READ_RECORD,
                    Permission.READ_DEVICE, Permission.READ_ENV, Permission.READ_ADHERENCE,
                    Permission.READ_ALARM, Permission.READ_MEDICINE, Permission.READ_GUARDIAN}) {
                assertTrue(Permission.allows(accessService.require(ELDER_ID, permission), permission),
                        role + " 应可 " + permission);
            }
        }
    }

    @Test
    @DisplayName("④ AI 问答：老人本人与家属可问，护理 / 医生不可")
    void AI问答权限() {
        login(ELDER_ID, "ELDER");
        assertEquals(AccessIdentity.Kind.SELF, accessService.require(ELDER_ID, Permission.AI_CHAT).kind());

        login(GUARDIAN_ID, "GUARDIAN");
        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationRole.FAMILY, RelationStatus.ACTIVE));
        assertEquals("FAMILY", accessService.require(ELDER_ID, Permission.AI_CHAT).relationRole());

        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationRole.NURSE, RelationStatus.ACTIVE));
        assertEquals(ErrorCode.FORBIDDEN.code(),
                assertThrows(BizException.class,
                        () -> accessService.require(ELDER_ID, Permission.AI_CHAT)).code());
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
                relation(ELDER_ID, GUARDIAN_ID, RelationRole.FAMILY, RelationStatus.ACTIVE),
                relation(OTHER_ELDER_ID, GUARDIAN_ID, RelationRole.NURSE, RelationStatus.ACTIVE)));

        assertEquals(Set.of(ELDER_ID, OTHER_ELDER_ID), accessService.visibleElderIds());
    }

    @Test
    @DisplayName("ACTIVE 关系列表：按老人查监护关系")
    void 查指定老人的生效监护关系() {
        login(GUARDIAN_ID, "GUARDIAN");
        givenRelation(relation(ELDER_ID, GUARDIAN_ID, RelationRole.FAMILY, RelationStatus.ACTIVE));
        when(relationMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<GuardianRelation>>any()))
                .thenReturn(List.of(relation(ELDER_ID, GUARDIAN_ID, RelationRole.FAMILY, RelationStatus.ACTIVE)));

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

    private static GuardianRelation relation(String elderId, String guardianId,
                                             RelationRole role, RelationStatus status) {
        GuardianRelation relation = new GuardianRelation();
        relation.setRelationId("r-1001");
        relation.setElderId(elderId);
        relation.setGuardianId(guardianId);
        relation.setRole(role);
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
