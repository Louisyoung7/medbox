  package com.medbox.server.service;

  import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
  import com.medbox.server.common.exception.BizAssert;
  import com.medbox.server.common.security.AuthContext;
  import com.medbox.server.domain.GuardianRelation;
  import com.medbox.server.domain.User;
  import com.medbox.server.domain.enums.RelationRole;
  import com.medbox.server.domain.enums.RelationStatus;
  import com.medbox.server.domain.enums.UserRole;
  import com.medbox.server.dto.guardian.GuardianAcceptRequest;
  import com.medbox.server.dto.guardian.GuardianPendingResponse;
  import com.medbox.server.dto.guardian.GuardianRelationResponse;
  import com.medbox.server.mapper.GuardianRelationMapper;
  import com.medbox.server.mapper.UserMapper;
  import java.time.Clock;
  import java.time.OffsetDateTime;
  import java.util.List;
  import java.util.Map;
  import java.util.Set;
  import java.util.stream.Collectors;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.stereotype.Service;
  import org.springframework.transaction.annotation.Transactional;
  /**
   * 监护关系写操作：申请 / 待确认列表 / 确认 / 解除（见文档 02 第 3
  章）。
   * 判定型写操作无对应 Permission 位，按「账号角色 + 身份」手动校验。
   */

  @Service
  public class GuardianRelationService {
    private static final String FORBIDDEN_MESSAGE="无权限访问该资源";
    private final GuardianRelationMapper guardianRelationMapper;
    private final AccessService accessService;
    private final UserMapper userMapper;
    private final Clock clock;
    @Autowired
    public GuardianRelationService(GuardianRelationMapper guardianRelationMapper,AccessService accessService,UserMapper userMapper){
        this(guardianRelationMapper,accessService,userMapper,Clock.systemUTC());
    }
    
    public GuardianRelationService(GuardianRelationMapper guardianRelationMapper,AccessService accessService,UserMapper userMapper,Clock clock){
        this.guardianRelationMapper=guardianRelationMapper;
        this.accessService=accessService;
        this.userMapper=userMapper;
        this.clock=clock;
    }
    /** POST /users/{elderId}/guardians：监护人发起申请，创建 PENDING
  关系。 */
    @Transactional
    public GuardianRelationResponse apply(String elderId){
        AuthContext.CurrentUser current=AuthContext.required();
        BizAssert.forbiddenIf(UserRole.parse(current.role())!=UserRole.GUARDIAN,FORBIDDEN_MESSAGE);
        accessService.requireElder(elderId);
        
    GuardianRelation existing = findRelation(elderId,current.userId());
    if (existing == null) {
        GuardianRelation relation = new GuardianRelation();

        relation.setRelationId(guardianRelationMapper.nextRelationId());
        relation.setElderId(elderId);
        relation.setGuardianId(current.userId());
        relation.setStatus(RelationStatus.PENDING);
        relation.setCreatedAt(OffsetDateTime.now(clock));
        guardianRelationMapper.insert(relation);
        return GuardianRelationResponse.from(relation);
    }


    BizAssert.conflictIf(existing.getStatus()==RelationStatus.PENDING||existing.getStatus()==RelationStatus.ACTIVE,"监护关系已存在或待确认，请勿重复申请");
    // REVOKED → 重新申请：改回 PENDING 并清空 role（updateById会忽略 null，走显式 SQL）
    existing.setStatus(RelationStatus.PENDING);
    existing.setRole(null);
    guardianRelationMapper.reactivatePending(existing.getId(),RelationStatus.PENDING);
    return GuardianRelationResponse.from(existing);
    
 }
/** GET
  /users/guardians/pending：老人待确认的监护请求列表（带监护人姓名 +
  手机）。 */
    public List<GuardianPendingResponse> pendingList(){
        AuthContext.CurrentUser current=AuthContext.required();
        BizAssert.forbiddenIf(UserRole.parse(current.role())!=UserRole.ELDER,FORBIDDEN_MESSAGE);
        List<GuardianRelation> relations=guardianRelationMapper.selectList(new LambdaQueryWrapper<GuardianRelation>().eq(GuardianRelation::getElderId,current.userId()).eq(GuardianRelation::getStatus,RelationStatus.PENDING).orderByAsc(GuardianRelation::getCreatedAt));
        Set<String> guardianIds=relations.stream().map(GuardianRelation::getGuardianId).collect(Collectors.toSet());
        
        Map<String,User>guardians=guardianIds.isEmpty()?Map.of():userMapper.selectList(new LambdaQueryWrapper<User>().in(User::getUserId,guardianIds)).stream().collect(Collectors.toMap(User::getUserId,user->user));
        return relations.stream().map(relation->GuardianPendingResponse.from(relation,guardians.get(relation.getGuardianId()))).toList();
    }

    /** PATCH /users/guardians/{relationId}/accept：老人确认，置
  ACTIVE 并指定角色。 */
       @Transactional
      public GuardianRelationResponse accept(String relationId, GuardianAcceptRequest request) {
          AuthContext.CurrentUser current = AuthContext.required();
          BizAssert.forbiddenIf(UserRole.parse(current.role()) !=UserRole.ELDER, FORBIDDEN_MESSAGE);

          RelationRole role = RelationRole.parse(request.role());
          BizAssert.badRequestIf(role == null, "role 必须是 FAMILY /DOCTOR / NURSE 之一");

          GuardianRelation relation = findById(relationId);
          BizAssert.forbiddenIf(!current.userId().equals(relation.getElderId()), FORBIDDEN_MESSAGE);
          BizAssert.conflictIf(relation.getStatus() !=RelationStatus.PENDING, "该监护关系不处于待确认状态");

          relation.setRole(role);
          relation.setStatus(RelationStatus.ACTIVE);
          guardianRelationMapper.updateById(relation);
          return GuardianRelationResponse.from(relation);
      }
    /** DELETE /users/guardians/{relationId}：任一方解除，软删置REVOKED。 */
    @Transactional
      public void revoke(String relationId) {
        AuthContext.CurrentUser current = AuthContext.required();

          GuardianRelation relation = findById(relationId);
          boolean elder =current.userId().equals(relation.getElderId());
          boolean guardian =current.userId().equals(relation.getGuardianId());
          BizAssert.forbiddenIf(!elder && !guardian,FORBIDDEN_MESSAGE);

          if (relation.getStatus() != RelationStatus.REVOKED) {
              relation.setStatus(RelationStatus.REVOKED);
              guardianRelationMapper.updateById(relation);
          }
      }
     /** 按业务号查关系；查不到一律 40301（不是 40401），防枚举。 */
      private GuardianRelation findById(String relationId) {
          BizAssert.notBlank(relationId, "relationId 不能为空");
          GuardianRelation relation = guardianRelationMapper.selectOne(
                  new LambdaQueryWrapper<GuardianRelation>()
                          .eq(GuardianRelation::getRelationId,relationId));
          BizAssert.forbiddenIf(relation == null, FORBIDDEN_MESSAGE);
          return relation;
      }

      private GuardianRelation findRelation(String elderId, String guardianId) {
          return guardianRelationMapper.selectOne(new LambdaQueryWrapper<GuardianRelation>()
                  .eq(GuardianRelation::getElderId, elderId)
                  .eq(GuardianRelation::getGuardianId, guardianId));
      }
}