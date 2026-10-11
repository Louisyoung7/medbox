package com.medbox.server.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.medbox.server.common.security.AuthContext;
import com.medbox.server.common.security.DeviceOwnerResolver;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.enums.RelationRole;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.service.AccessService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * 握手鉴权的集成测试：**真实 {@link JwtProvider} + 真实 {@link AccessService} + 打桩的 Mapper**，
 * 走完整条 {@code beforeHandshake} 链路（与 {@code AccessWebIntegrationTest} 同一套路）。
 *
 * <p>token 由真实签发，第 ① 步不绕过；不连数据库。重点是"越权主题被拒"（07 清单验收点）。
 */
class WsHandshakeAuthIntegrationTest {

    private static final String SECRET = "medbox-ws-test-secret-medbox-ws-test-secret";
    private static final String ELDER = "e-1001";
    private static final String GUARDIAN = "u-2001";
    private static final String STRANGER = "u-3001";

    private JwtProvider jwtProvider;
    private GuardianRelationMapper guardianRelationMapper;
    private WsHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider(SECRET, 7200L, 604800L);
        guardianRelationMapper = mock(GuardianRelationMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        AccessService accessService = new AccessService(userMapper, guardianRelationMapper, emptyResolver());
        interceptor = new WsHandshakeInterceptor(jwtProvider, accessService, JsonMapper.builder().build());
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    @DisplayName("① 未带 token → 401 / 40101")
    void missingTokenIsRejected() throws Exception {
        HandshakeResult result = handshake(null, "elder:" + ELDER, null);

        assertThat(result.allowed).isFalse();
        assertThat(result.status).isEqualTo(401);
        assertThat(result.body).contains("40101");
    }

    @Test
    @DisplayName("① token 无效 / 过期 → 401 / 40101")
    void invalidTokenIsRejected() throws Exception {
        assertThat(handshake("not-a-jwt", "elder:" + ELDER, null).status).isEqualTo(401);
        // refresh token 不能当 access 用（typ 校验）
        assertThat(handshake(jwtProvider.issue(ELDER, "ELDER", TokenType.REFRESH), "elder:" + ELDER, null).status)
                .isEqualTo(401);
    }

    @Test
    @DisplayName("② 缺少 topics → 400 / 40001")
    void missingTopicsIsRejected() throws Exception {
        HandshakeResult result = handshake(bearer(ELDER, "ELDER"), null, null);

        assertThat(result.status).isEqualTo(400);
        assertThat(result.body).contains("40001");
    }

    @Test
    @DisplayName("② device: 主题 → 400 / 40001，并说明设备维度暂不支持")
    void deviceTopicIsRejected() throws Exception {
        HandshakeResult result = handshake(bearer(ELDER, "ELDER"), "device:BOXA1001", null);

        assertThat(result.status).isEqualTo(400);
        assertThat(result.body).contains("device");
    }

    @Test
    @DisplayName("②③ 老人订阅自己 → 放行，attributes 带上订阅信息")
    void elderSubscribesSelf() throws Exception {
        HandshakeResult result = handshake(bearer(ELDER, "ELDER"), "elder:" + ELDER, "evt-9a1c");

        assertThat(result.allowed).isTrue();
        WsSubscription subscription = subscriptionOf(result);
        assertThat(subscription.userId()).isEqualTo(ELDER);
        assertThat(subscription.topics()).containsExactly("elder:" + ELDER);
        assertThat(subscription.lastEventSeq()).isEqualTo(0x9a1cL);
        assertThat(subscription.needReplay()).isTrue();
    }

    @Test
    @DisplayName("②③ 老人订阅他人 → 403 / 40301（越权主题被拒，07 清单验收点）")
    void elderSubscribingOtherIsRejected() throws Exception {
        HandshakeResult result = handshake(bearer(STRANGER, "ELDER"), "elder:" + ELDER, null);

        assertThat(result.allowed).isFalse();
        assertThat(result.status).isEqualTo(403);
        assertThat(result.body).contains("40301");
    }

    @Test
    @DisplayName("③ PENDING 关系 → 403（可见老人集合里没有，不泄漏'曾有关系'）")
    void pendingRelationIsRejected() throws Exception {
        // 真实查询带 status=ACTIVE 条件，PENDING 行根本不会被返回 —— mock 直接模拟其效果（空结果）
        givenRelations(List.of());

        HandshakeResult result = handshake(bearer(GUARDIAN, "GUARDIAN"), "elder:" + ELDER, null);

        assertThat(result.allowed).isFalse();
        assertThat(result.status).isEqualTo(403);
    }

    @Test
    @DisplayName("②③ ACTIVE 监护人订阅被监护老人 → 放行")
    void activeGuardianIsAllowed() throws Exception {
        givenRelations(List.of(
                relation(ELDER, GUARDIAN, RelationRole.FAMILY, RelationStatus.ACTIVE),
                relation("e-1002", GUARDIAN, RelationRole.NURSE, RelationStatus.ACTIVE)));

        // 多主题：全部落在可见老人才放行（任一越权即拒绝整个握手）
        HandshakeResult result = handshake(bearer(GUARDIAN, "GUARDIAN"),
                "elder:" + ELDER + ",elder:e-1002", null);

        assertThat(result.allowed).isTrue();
        assertThat(subscriptionOf(result).topics()).containsExactly("elder:" + ELDER, "elder:e-1002");
    }

    @Test
    @DisplayName("AuthContext 必须被清掉：握手成功后线程上不留身份")
    void authContextIsClearedAfterHandshake() throws Exception {
        handshake(bearer(ELDER, "ELDER"), "elder:" + ELDER, null);

        assertThat(AuthContext.current()).isNull();
    }

    // —— 辅助 —— //

    private String bearer(String userId, String role) {
        return jwtProvider.issue(userId, role, TokenType.ACCESS);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<DeviceOwnerResolver> emptyResolver() {
        ObjectProvider<DeviceOwnerResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private void givenRelations(List<GuardianRelation> relations) {
        when(guardianRelationMapper.selectList(any())).thenReturn(relations);
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

    private HandshakeResult handshake(String token, String topics, String lastEventId) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (token != null) {
            request.setParameter("token", token);
        }
        if (topics != null) {
            request.setParameter("topics", topics);
        }
        if (lastEventId != null) {
            request.setParameter("lastEventId", lastEventId);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        Map<String, Object> attributes = new HashMap<>();
        boolean allowed = interceptor.beforeHandshake(
                new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(response),
                mock(WebSocketHandler.class),
                attributes);
        return new HandshakeResult(allowed, response.getStatus(), response.getContentAsString(), attributes);
    }

    private static WsSubscription subscriptionOf(HandshakeResult result) {
        return (WsSubscription) result.attributes.get(WsHandshakeInterceptor.ATTR_SUBSCRIPTION);
    }

    private record HandshakeResult(boolean allowed, int status, String body, Map<String, Object> attributes) {
    }
}
