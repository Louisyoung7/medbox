package com.medbox.server.dto.guardian;
import com.medbox.server.domain.GuardianRelation;
import com.medbox.server.domain.User;
import java.time.OffsetDateTime;
/**
 * {@code GET /users/guardians/pending}
的列表项：除关系本身外，附上发起申请的监护人的
 * 姓名与手机号（从 {@code user} 表 join
而来，供老人端展示"谁想绑定我"）。
 */

public record GuardianPendingResponse(
    String relationId,
    String elderId,
    String guardianId,
    String guardianName,
    String guardianPhone,
    OffsetDateTime createdAt
){
    public static GuardianPendingResponse from(GuardianRelation relation,User guardian){
        return new GuardianPendingResponse(
            relation.getRelationId(),
            relation.getElderId(),
            relation.getGuardianId(),
            guardian==null?null:guardian.getName(),
            guardian==null?null:guardian.getPhone(),
            relation.getCreatedAt()
        );
    }
}