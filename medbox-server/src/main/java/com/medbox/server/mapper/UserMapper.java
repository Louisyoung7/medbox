package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.User;
import com.medbox.server.domain.enums.UserStatus;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code "user"} 表的 Mapper（见文档 06 的 2.1）。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 取下一个业务 user_id（{@code u-1001}、{@code u-1002} ...）。
     *
     * <p>用独立序列而不是插入后回填：省掉一次 UPDATE，也避免并发下先插入后改名的短暂空窗。
     *
     * <p><b>序列名叫 {@code user_biz_id_seq} 而不是 {@code user_id_seq}</b>：后者是 `"user" 表
     * {@code id BIGSERIAL} 自动创建的序列，复用它拿到的会是自增主键的值（首个用户变成 {@code u-1}）。
     */
    @Select("SELECT 'u-' || nextval('user_biz_id_seq')")
    String nextUserId();

    /**
     * 注销账号：**软删除** —— 置 {@code DELETED} 并清空登录标识（见文档 02 的 3.1 / 06 的 2.1）。
     *
     * <p><b>写成 SQL 而不是 {@code updateById}</b>：MyBatis-Plus 默认忽略 {@code null} 字段，
     * {@code setPhone(null)} 根本不会进 UPDATE 语句，注销会**静默失败**（手机号永远释放不掉）。
     * 同理也不用 {@code LambdaUpdateWrapper}：它在 Mapper 打桩的测试里会立即解析列名而抛异常。
     *
     * <p>{@code phone} / {@code username} 置 NULL 是注销的关键：**NULL 不参与唯一约束**，
     * 所以同一个手机号可以重新注册（拿到新的 {@code user_id}，旧数据不继承）。
     *
     * @param status    {@link UserStatus#DELETED}
     * @param deletedAt 注销时间（UTC）
     * @return 被更新的行数（0 表示账号不存在）
     */
    @Update("""
            UPDATE "user"
               SET status = #{status}, deleted_at = #{deletedAt},
                   phone = NULL, username = NULL
             WHERE user_id = #{userId}
            """)
    int markDeleted(@Param("userId") String userId,
                    @Param("status") UserStatus status,
                    @Param("deletedAt") OffsetDateTime deletedAt);
}
