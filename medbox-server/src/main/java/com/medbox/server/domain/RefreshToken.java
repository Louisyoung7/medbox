package com.medbox.server.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.medbox.server.domain.enums.RefreshTokenStatus;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * refresh token 轮换记录，对应 {@code refresh_token} 表（见文档 06 的 2.13）。
 *
 * <p>库里只存 token 的 **SHA-256 哈希**（{@code tokenHash}），不存明文 —— 拖库也换不来可用凭据。
 * {@code tokenId} 与 refresh JWT 的 {@code jti} 一致，是轮换与重放检测的唯一抓手。
 */
@Data
@TableName("refresh_token")
public class RefreshToken {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 与 refresh JWT 的 jti 一致。 */
    private String tokenId;

    /** -> user.user_id。 */
    private String userId;

    /** refresh token 的 SHA-256（十六进制），不存明文。 */
    private String tokenHash;

    /** ACTIVE / ROTATED / REVOKED。 */
    private RefreshTokenStatus status;

    /** 轮换后接替它的 token_id（REVOKED 时为空）。 */
    private String replacedBy;

    /** 与 JWT 的 exp 一致。 */
    private OffsetDateTime expiresAt;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
