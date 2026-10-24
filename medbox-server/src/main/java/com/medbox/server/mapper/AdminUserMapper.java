package com.medbox.server.mapper;

import com.medbox.server.domain.User;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 管理后台用户列表查询（见文档 02 第 10.1）。
 *
 * <p>复用业务表 {@code "user"}，仅做「列出 + 筛选」；硬删与级联清理走 {@link AdminCleanupMapper}，本 Mapper 不动其他业务 Mapper。
 * 软删账号的 {@code phone} / {@code username} 为 NULL，LIKE 对 NULL 不命中，故不会在 keyword 筛选下出现。
 */
@Mapper
public interface AdminUserMapper {

    /** 按筛选条件统计总数（status / role 精确，keyword 模糊匹配 phone|username 的非空列）。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM "user"
            <where>
                <if test="status != null">AND status = #{status}</if>
                <if test="role != null">AND role = #{role}</if>
                <if test="keyword != null and keyword != ''">AND (phone LIKE CONCAT('%', #{keyword}, '%') OR username LIKE CONCAT('%', #{keyword}, '%'))</if>
            </where>
            </script>
            """)
    long countByFilter(@Param("status") String status, @Param("role") String role, @Param("keyword") String keyword);

    /** 按筛选条件分页列出（按 created_at 倒序）。 */
    @Select("""
            <script>
            SELECT user_id, role, name, phone, username, status, created_at
            FROM "user"
            <where>
                <if test="status != null">AND status = #{status}</if>
                <if test="role != null">AND role = #{role}</if>
                <if test="keyword != null and keyword != ''">AND (phone LIKE CONCAT('%', #{keyword}, '%') OR username LIKE CONCAT('%', #{keyword}, '%'))</if>
            </where>
            ORDER BY created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<User> selectByFilter(@Param("status") String status, @Param("role") String role,
                              @Param("keyword") String keyword, @Param("limit") int limit,
                              @Param("offset") int offset);
}
