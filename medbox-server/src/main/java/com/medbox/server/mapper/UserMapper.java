package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

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
}
