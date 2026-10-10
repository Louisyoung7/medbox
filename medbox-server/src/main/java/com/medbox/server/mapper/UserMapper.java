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
     * <p>用独立的 {@code user_id_seq} 序列而不是插入后回填：省掉一次 UPDATE，也避免并发下
     * 先插入后改名导致的短暂空窗。
     */
    @Select("SELECT 'u-' || nextval('user_id_seq')")
    String nextUserId();
}
