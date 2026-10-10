package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.RefreshToken;
import com.medbox.server.domain.enums.RefreshTokenStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * {@code refresh_token} 表的 Mapper（见文档 06 的 2.13）。
 */
@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshToken> {

    /**
     * 撤销该用户**仍在生效**的全部 refresh token（登出 / 重放保护）。
     *
     * <p>写在这里而不是用 Wrapper：一条 SQL 更直白，也避开"实体为 null 的 update"这种 MP 细节。
     *
     * @return 被撤销的行数
     */
    @Update("""
            UPDATE refresh_token
               SET status = #{status}, updated_at = NOW()
             WHERE user_id = #{userId}
               AND status = 'ACTIVE'
            """)
    int revokeActiveByUserId(@Param("userId") String userId, @Param("status") RefreshTokenStatus status);
}
