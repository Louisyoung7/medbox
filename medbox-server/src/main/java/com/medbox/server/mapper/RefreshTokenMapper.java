package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.RefreshToken;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code refresh_token} 表的 Mapper（见文档 06 的 2.13）。
 */
@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshToken> {
}
