package com.medbox.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.medbox.server.domain.enums.UserRole;
import com.medbox.server.domain.enums.UserStatus;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 用户（老人 + 监护人），对应 {@code "user"} 表（见文档 06 的 2.1）。
 *
 * <p><b>两个坑</b>：① {@code user} 是 PostgreSQL 保留字，表名必须写成带双引号的 {@code "user"}，
 * 否则生成的 SQL 会报语法错误；② 主键是 BIGSERIAL，必须显式 {@code IdType.AUTO} —— MyBatis-Plus
 * 默认是雪花 ID（ASSIGN_ID），会往自增列里塞一个巨大的值。
 *
 * <p>业务 ID {@code userId}（如 {@code u-1001}）不由数据库生成，走 {@code user_id_seq} 序列，
 * 见 {@link com.medbox.server.mapper.UserMapper#nextUserId()}。
 */
@Data
@TableName("\"user\"")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务 ID，如 {@code u-1001}。 */
    private String userId;

    /** 登录账号（可空），与 phone 二选一登录。 */
    private String username;

    /** BCrypt 单向哈希，**永不存明文**。 */
    private String passwordHash;

    /** 展示名。 */
    private String name;

    /** 登录账号（手机号），唯一。 */
    private String phone;

    /** ELDER / GUARDIAN。 */
    private UserRole role;

    /**
     * 账号状态：ACTIVE 正常 / DELETED **已注销**（V7 新增，见 02 的 3.1）。
     *
     * <p>注销是软删除：本行保留，只把登录标识（{@code phone} / {@code username}）置空释放唯一键。
     * 判定 {@code DELETED} 时一律按"未登录 / 账号不可用"处理（登录 40102、刷新与被访问 40101）。
     */
    private UserStatus status;

    /** 注销时间（UTC），{@code status = DELETED} 时非空。 */
    private OffsetDateTime deletedAt;

    private OffsetDateTime createdAt;
}
