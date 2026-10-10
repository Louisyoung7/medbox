package com.medbox.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 幂等记录，对应 {@code idempotency_record} 表（见文档 06 的 2.14）。
 *
 * <p>键是 {@code (request_id, endpoint)}：客户端用同一个 {@code X-Request-Id} 重发同一接口时，
 * 回放首次响应的 {@code data} 快照（{@code responseSnapshot}），保证"重复提交只生效一次"。
 */
@Data
@TableName("idempotency_record")
public class IdempotencyRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 客户端 X-Request-Id（经 {@code TraceContext.sanitize} 校验）。 */
    private String requestId;

    /** 幂等作用域，如 {@code AUTH_REGISTER}（同一 requestId 在不同接口间互不干扰）。 */
    private String endpoint;

    /** 请求体指纹（不含密码）；同号不同内容按 40001 处理。 */
    private String requestHash;

    /** 首次响应的 data 快照（JSON 字符串）。 */
    private String responseSnapshot;

    /** 过期后不再回放。 */
    private OffsetDateTime expiresAt;

    private OffsetDateTime createdAt;
}
