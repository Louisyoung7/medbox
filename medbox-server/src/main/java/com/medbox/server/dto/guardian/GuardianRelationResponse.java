package com.medbox.server.dto.guardian;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.enums.RelationStatus;
import java.time.OffsetDateTime;

  /**
   * 监护关系的响应体：{@code POST .../guardians} 与 {@code PATCH
   .../accept} 保存后的回显。
   * 监护人统一为单一角色，响应不含关系角色字段。
   */

public record GuardianRelationResponse(
    String relationId,
    String elderId,
    String guardianId,
    RelationStatus status,
    OffsetDateTime createdAt
){
    public static GuardianRelationResponse from(GuardianRelation relation){
        return new GuardianRelationResponse(
            relation.getRelationId(),
            relation.getElderId(),
            relation.getGuardianId(),
            relation.getStatus(),
            relation.getCreatedAt()
        );
    }
}
