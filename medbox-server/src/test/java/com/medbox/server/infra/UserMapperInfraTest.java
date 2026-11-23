package com.medbox.server.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.domain.enums.UserStatus;
import com.medbox.server.mapper.UserMapper;
import com.medbox.server.testsupport.PostgresInfraTestBase;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link UserMapper} 的真实 SQL 验证：业务号序列 {@code user_biz_id_seq} 与软删除 SQL。
 *
 * <p>这两个坑单测（Mock Mapper）覆盖不到：① 序列名若撞上 {@code "user" 表 id BIGSERIAL}
 * 自动序列，会取到自增主键值；② 注销必须把 {@code phone} / {@code username} 置 NULL
 * （MyBatis-Plus 默认忽略 null 字段，只有显式 SQL 才能释放唯一键）。
 */
@Tag("infra")
@DisplayName("UserMapper：真实 PostgreSQL 序列与软删除")
class UserMapperInfraTest extends PostgresInfraTestBase {

    @Autowired
    private UserMapper userMapper;

    @Test
    @DisplayName("nextUserId 返回 u- 业务号且递增")
    void nextUserIdReturnsIncreasingBizId() {
        String first = userMapper.nextUserId();
        String second = userMapper.nextUserId();

        assertTrue(first.matches("u-\\d+"), "业务号格式不对：" + first);
        assertTrue(bizNo(first) < bizNo(second), "业务号应递增：" + first + " -> " + second);
    }

    @Test
    @DisplayName("注销释放 phone / username 并保留 user_id 行")
    void markDeletedReleasesLoginIdentity() {
        String userId = userMapper.nextUserId();
        long seq = bizNo(userId);

        User user = new User();
        user.setUserId(userId);
        user.setUsername("infra-" + seq);
        user.setPasswordHash("$2a$10$infraTestHashOnlyForSqlVerification000000000000000000000");
        user.setName("infra 测试用户");
        user.setPhone("139" + String.format("%08d", seq));
        user.setRole(UserRole.ELDER);
        user.setStatus(UserStatus.ACTIVE);

        assertEquals(1, userMapper.insert(user));
        assertNotNull(user.getId(), "自增主键应回填");

        OffsetDateTime deletedAt = OffsetDateTime.now(ZoneOffset.UTC);
        assertEquals(1, userMapper.markDeleted(userId, UserStatus.DELETED, deletedAt));

        User after = userMapper.selectById(user.getId());
        assertNotNull(after, "软删除不应删行");
        assertEquals(userId, after.getUserId());
        assertEquals(UserStatus.DELETED, after.getStatus());
        assertNotNull(after.getDeletedAt(), "deleted_at 应写入");
        assertNull(after.getPhone(), "phone 应被置 NULL 以释放唯一键");
        assertNull(after.getUsername(), "username 应被置 NULL 以释放唯一键");
    }

    private static long bizNo(String userId) {
        return Long.parseLong(userId.substring("u-".length()));
    }
}
