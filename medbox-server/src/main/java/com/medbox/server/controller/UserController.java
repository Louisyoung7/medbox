package com.medbox.server.controller;
import org.springframework.web.bind.annotation.PathVariable;
import com.medbox.server.dto.R;
import com.medbox.server.dto.user.DeleteAccountRequest;
import com.medbox.server.dto.user.DeleteAccountResponse;
import com.medbox.server.dto.user.UserProfileResponse;
import com.medbox.server.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口（见文档 02 第 3 章）。
 *
 * <p>这些接口都受 {@code AuthInterceptor} 保护（{@code /api/v1/**} 需带 access token），
 * 无需在本类里再判登录态。
 *
 * <p>监护关系的申请 / 确认 / 解除接口属 P1 {@code feat/guardian-relation}，本分支不实现。
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** 当前用户资料与角色。 */
    @GetMapping("/me")
    public R<UserProfileResponse> me() {
        return R.ok(userService.me());
    }

    /**
     * 注销**当前登录账号**（{@code feat/backend-user-delete}，见文档 02 的 3.1）。
     *
     * <p>动作型子路径用 {@code POST}（与 {@code PATCH /users/guardians/{id}/accept} 同风格），
     * 而不是 {@code DELETE /users/me}：注销需要带请求体（密码二次确认），且要受拦截器鉴权 ——
     * <b>{@code /api/v1/auth/**} 是放行的，注销绝不能挂在那里</b>。
     */
    @PostMapping("/me/delete")
    public R<DeleteAccountResponse> deleteMe(@Valid @RequestBody DeleteAccountRequest request) {
        return R.ok(userService.deleteSelf(request));
    }

     /** 指定老人的基础资料（监护人可见，见文档 02 第 3 章）。 */
     @GetMapping("/{elderId}/profile")
     public R<UserProfileResponse> profile(@PathVariable String elderId){
        return R.ok(userService.profile(elderId));
     }
}
