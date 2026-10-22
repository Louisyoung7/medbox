  package com.medbox.server.controller;

  import com.medbox.server.dto.R;
  import com.medbox.server.dto.guardian.GuardianAcceptRequest;
  import com.medbox.server.dto.guardian.GuardianPendingResponse;
  import com.medbox.server.dto.guardian.GuardianRelationResponse;
  import com.medbox.server.service.GuardianRelationService;
  import jakarta.validation.Valid;
  import java.util.List;
  import org.springframework.web.bind.annotation.DeleteMapping;
  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.PatchMapping;
  import org.springframework.web.bind.annotation.PathVariable;
  import org.springframework.web.bind.annotation.PostMapping;
  import org.springframework.web.bind.annotation.RequestBody;
  import org.springframework.web.bind.annotation.RequestMapping;
  import org.springframework.web.bind.annotation.RestController;

  /**
   * 监护关系写接口（见文档 02 第 3 章）。只做参数绑定与 {@code R<T>}
  包装，
   * 权限判定都在 {@link GuardianRelationService} 里；未登录由
  AuthInterceptor 兜 40101。
   */
  @RestController
  @RequestMapping("/api/v1/users")
  public class GuardianRelationController {

      private final GuardianRelationService guardianRelationService;

      public GuardianRelationController(GuardianRelationService guardianRelationService) {
          this.guardianRelationService = guardianRelationService;
      }

      /** 监护人发起申请，创建 PENDING 关系。 */
      @PostMapping("/{elderId}/guardians")
      public R<GuardianRelationResponse>apply(@PathVariable("elderId") String elderId) {
          return R.ok(guardianRelationService.apply(elderId));
      }

      /** 老人待确认的监护请求列表。 */
      @GetMapping("/guardians/pending")
      public R<List<GuardianPendingResponse>> pending() {
          return R.ok(guardianRelationService.pendingList());
      }
        

      /** 老人确认监护请求，置 ACTIVE。 */
      @PatchMapping("/guardians/{relationId}/accept")
      public R<GuardianRelationResponse> accept(@PathVariable("relationId") String relationId,
                                                @Valid @RequestBody GuardianAcceptRequest request) {
          return R.ok(guardianRelationService.accept(relationId, request));
      }

      /** 任一方拒绝 / 解除监护关系。 */
      @DeleteMapping("/guardians/{relationId}")
      public R<Void> revoke(@PathVariable("relationId") String relationId) {
          guardianRelationService.revoke(relationId);
          return R.ok();
      }
  }
