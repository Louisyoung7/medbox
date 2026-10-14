package com.medbox.server.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.medbox.server.common.security.AuthContext;
import com.medbox.server.common.security.JwtProvider;
import com.medbox.server.common.security.PasswordHasher;
import com.medbox.server.common.security.TokenType;
import com.medbox.server.domain.RefreshToken;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.RefreshTokenStatus;
import com.medbox.server.domain.enums.RelationStatus;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.domain.enums.UserStatus;
import com.medbox.server.mapper.GuardianRelationMapper;
import com.medbox.server.mapper.RefreshTokenMapper;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.service.RefreshTokenService;
import com.medbox.server.support.TableInfoWarmup;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 账号注销的**完整链路集成测试**（07 清单 {@code feat/backend-user-delete}，见文档 02 的 3.1）。
 *
 * <p>打的是 MockMvc → {@code UserController} → 真实 {@code UserService} → 真实
 * {@code RefreshTokenService} / {@code PasswordHasher} → 打桩的 Mapper；
 * 登录 / 刷新两处 {@code DELETED} 判定也走真实 {@code AuthService}。
 *
 * <p><b>不连真实数据库</b>：CI 无 PostgreSQL，且 {@code deleteSelf} 是 {@code @Transactional}
 * （进入方法要开 JDBC 连接），故用 {@link H2DataSourceConfig} 把 DataSource 换成嵌入式 H2。
 * Mapper 全部打桩、不跑真实 SQL（SQL 正确性留给真机联调）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserDeleteWebIntegrationTest {

    @TestConfiguration
    static class H2DataSourceConfig {
        @Bean
        @Primary
        public EmbeddedDatabase dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("medbox-user-delete-test")
                    .build();
        }
    }

    private static final String USER_ID = "u-1001";
    private static final String PHONE = "13800001234";
    private static final String PASSWORD = "123456";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private PasswordHasher passwordHasher;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private GuardianRelationMapper guardianRelationMapper;

    @MockitoBean
    private RefreshTokenMapper refreshTokenMapper;

    private String passwordHash;

    @BeforeEach
    void setUp() {
        // Mapper 被打桩后 MyBatis 不会为实体建表信息，Lambda Wrapper 解析列名时会炸 —— 先预热
        TableInfoWarmup.init(User.class, RefreshToken.class);
        passwordHash = passwordHasher.hash(PASSWORD);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    @DisplayName("未带 access token → 40101")
    void 未登录返回_40101() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @DisplayName("password 为空串 → 40001（@NotBlank）")
    void 密码为空返回_40001() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/delete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TokenType.ACCESS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("password 不能为空"));
    }

    @Test
    @DisplayName("密码错误 → 40102，且不软删、不撤销任何凭据")
    void 密码错误返回_40102() throws Exception {
        when(userMapper.selectOne(any())).thenReturn(user(UserStatus.ACTIVE));

        mockMvc.perform(post("/api/v1/users/me/delete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TokenType.ACCESS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40102));

        verify(userMapper, never()).markDeleted(anyString(), any(), any());
        verify(refreshTokenMapper, never()).revokeActiveByUserId(anyString(), any());
        verify(guardianRelationMapper, never()).revokeByUserId(anyString(), any());
    }

    @Test
    @DisplayName("账号已注销 → 40101（重复提交 / 换设备再点一次）")
    void 已注销再注销返回_40101() throws Exception {
        when(userMapper.selectOne(any())).thenReturn(user(UserStatus.DELETED));

        mockMvc.perform(post("/api/v1/users/me/delete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TokenType.ACCESS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));

        verify(userMapper, never()).markDeleted(anyString(), any(), any());
    }

    @Test
    @DisplayName("注销成功：软删 + 撤销全部 refresh + 双向解除监护关系，返回 userId / deletedAt")
    void 注销成功() throws Exception {
        when(userMapper.selectOne(any())).thenReturn(user(UserStatus.ACTIVE));
        when(userMapper.markDeleted(anyString(), any(), any())).thenReturn(1);

        mockMvc.perform(post("/api/v1/users/me/delete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TokenType.ACCESS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(USER_ID))
                .andExpect(jsonPath("$.data.deletedAt").exists());

        verify(userMapper).markDeleted(eq(USER_ID), eq(UserStatus.DELETED), any());
        verify(refreshTokenMapper).revokeActiveByUserId(USER_ID, RefreshTokenStatus.REVOKED);
        verify(guardianRelationMapper).revokeByUserId(USER_ID, RelationStatus.REVOKED);
    }

    @Test
    @DisplayName("注销后用旧密码登录 → 40102")
    void 注销后登录返回_40102() throws Exception {
        when(userMapper.selectOne(any())).thenReturn(user(UserStatus.DELETED));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"" + PHONE + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40102));
    }

    @Test
    @DisplayName("注销后拿有效 refresh token 刷新 → 40101（不再签发新令牌）")
    void 注销后刷新返回_40101() throws Exception {
        String refreshToken = jwtProvider.issue(USER_ID, "GUARDIAN", TokenType.REFRESH);
        String jti = jwtProvider.parse(refreshToken, TokenType.REFRESH).jti();
        when(refreshTokenMapper.selectOne(any())).thenReturn(activeRefreshRow(jti, refreshToken));
        when(userMapper.selectOne(any())).thenReturn(user(UserStatus.DELETED));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));

        verify(refreshTokenMapper, never()).insert(any(RefreshToken.class));
    }

    private String bearer(TokenType type) {
        return "Bearer " + jwtProvider.issue(USER_ID, "GUARDIAN", type);
    }

    /** 用 setUp 里算出的真实 BCrypt 哈希 —— 注销要真的比对密码，不能打桩 PasswordHasher。 */
    private User user(UserStatus status) {
        User user = new User();
        user.setId(1L);
        user.setUserId(USER_ID);
        user.setPhone(PHONE);
        user.setRole(UserRole.GUARDIAN);
        user.setPasswordHash(passwordHash);
        user.setStatus(status);
        return user;
    }

    private static RefreshToken activeRefreshRow(String jti, String rawToken) {
        RefreshToken row = new RefreshToken();
        row.setId(1L);
        row.setTokenId(jti);
        row.setUserId(USER_ID);
        row.setTokenHash(RefreshTokenService.sha256Hex(rawToken));
        row.setStatus(RefreshTokenStatus.ACTIVE);
        row.setExpiresAt(OffsetDateTime.ofInstant(Instant.now().plus(7, ChronoUnit.DAYS), ZoneOffset.UTC));
        return row;
    }
}
