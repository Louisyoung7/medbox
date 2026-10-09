package com.medbox.server.support;

import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.dto.PageResult;
import com.medbox.server.dto.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅测试源集使用的控制器：造出各类成功 / 异常场景，供 {@code GlobalExceptionHandlerTest} 断言统一响应。
 *
 * <p>放在 test 源集，不会打进生产包；生产代码不要引用它。
 */
@RestController
@RequestMapping("/api/v1/_test")
public class TestErrorController {

    @GetMapping("/ok")
    public R<String> ok() {
        return R.ok("pong");
    }

    @GetMapping("/page")
    public R<PageResult<String>> page() {
        return R.ok(PageResult.of(List.of("a", "b"), 2, 1, 20));
    }

    @PostMapping("/validate")
    public R<Void> validate(@Valid @RequestBody TestRequest request) {
        return R.ok();
    }

    @GetMapping("/biz")
    public R<Void> biz() {
        throw BizException.of(ErrorCode.NOT_FOUND, "计划 p-3301 不存在");
    }

    @GetMapping("/boom")
    public R<Void> boom() {
        throw new IllegalStateException("boom");
    }

    @GetMapping("/param")
    public R<String> param(@RequestParam("name") String name) {
        return R.ok(name);
    }

    /** 测试用的请求体（写成静态内部类，避免 Jackson / Bean Validation 对 record 的支持差异）。 */
    public static class TestRequest {

        @NotBlank(message = "name 不能为空")
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
