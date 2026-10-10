package com.medbox.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.medbox.server.domain.IdempotencyRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code idempotency_record} 表的 Mapper（见文档 06 的 2.14）。
 */
@Mapper
public interface IdempotencyRecordMapper extends BaseMapper<IdempotencyRecord> {
}
