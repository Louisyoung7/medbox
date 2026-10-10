package com.medbox.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.medbox.server.domain.enums.RelationRole;
import com.medbox.server.domain.enums.RelationStatus;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 老人↔监护人绑定关系，对应 {@code guardian_relation} 表（见文档 06 的 2.2）。
 *
 * <p>表已由 Flyway {@code V1__init_schema.sql} 建好；{@code relation_id} 业务号列由
 * {@code V4__authz.sql} 新增（与 {@code user.user_id} 等业务号同风格，供 P1
 * {@code feat/guardian-relation} 的 {@code PATCH /users/guardians/{relationId}/accept} 直接使用）。
 *
 * <p><b>两个坑</b>（与 {@link User} 同类）：① 主键是 BIGSERIAL，必须显式 {@code IdType.AUTO}
 * —— MyBatis-Plus 默认是雪花 ID（ASSIGN_ID），会往自增列里塞一个巨大的值；
 * ② {@code role} 列无 NOT NULL，实体用 {@link RelationRole} 枚举接收，为 NULL 时取到 {@code null}，
 * 由 {@code AccessService} 按 {@link RelationRole#DEFAULT_ROLE} 兜底。
 *
 * <p><b>注意</b>：本实体只用于**读取与校验**（本分支不实现申请 / 确认接口）；
 * 关系的创建与状态流转属 P1 {@code feat/guardian-relation}。
 */
@Data
@TableName("guardian_relation")
public class GuardianRelation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务 ID，如 {@code r-1001}（V4 新增列）。 */
    private String relationId;

    /** 老人 -> user.user_id。 */
    private String elderId;

    /** 监护人 -> user.user_id。 */
    private String guardianId;

    /** FAMILY / DOCTOR / NURSE（可空，兜底 FAMILY）。 */
    private RelationRole role;

    /** PENDING / ACTIVE / REVOKED。 */
    private RelationStatus status;

    private OffsetDateTime createdAt;
}
