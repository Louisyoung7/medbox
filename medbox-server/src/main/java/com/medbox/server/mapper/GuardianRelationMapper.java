package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.GuardianRelation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

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
}
