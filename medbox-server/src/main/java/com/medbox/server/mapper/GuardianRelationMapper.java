package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.enums.RelationStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code guardian_relation} 表的 Mapper（见文档 06 的 2.2）。
 *
 * <p>权限判定的正向查询（{@code where elder_id = ? and guardian_id = ?}）走
 * {@code UNIQUE(elder_id, guardian_id)}；反向查询（我监护了哪些老人）走
 * {@code idx_guardian_relation_guardian}（均由 {@code V4__authz.sql} 建）。
 */
@Mapper
public interface GuardianRelationMapper extends BaseMapper<GuardianRelation> {

    /**
     * 取下一个业务 relation_id（{@code r-1001}、{@code r-1002} ...）。
     *
     * <p><b>序列名叫 {@code guardian_relation_biz_id_seq} 而不是 {@code guardian_relation_id_seq}</b>：
     * 后者是本表 {@code id BIGSERIAL} 自动创建的序列，复用它会拿到自增主键的值（首条关系变成 {@code r-1}）。
     */
    @Select("SELECT 'r-' || nextval('guardian_relation_biz_id_seq')")
    String nextRelationId();

    /**
     * 账号注销时**双向**解除监护关系（见文档 02 的 3.1 / 06 的 2.2）。
     *
     * <p>该用户作为 {@code elder_id}（我是被监护的老人）或 {@code guardian_id}（我是监护人）
     * 的关系都要解除 —— 否则"老人注销了，监护人的列表里还挂着这个老人"，且权限判定会因为它
     * 仍是 {@code ACTIVE} 而放行（{@code visibleElderIds} 就是按 ACTIVE 关系算的）。
     *
     * <p>已是 {@code REVOKED} 的行跳过：避免把 {@code updated_at} 刷成新时间，也少写无用行。
     *
     * @param status {@link RelationStatus#REVOKED}
     * @return 被解除的关系条数
     */
    @Update("""
            UPDATE guardian_relation
               SET status = #{status}
             WHERE (elder_id = #{userId} OR guardian_id = #{userId})
               AND status <> #{status}
            """)
    int revokeByUserId(@Param("userId") String userId, @Param("status") RelationStatus status);
}
