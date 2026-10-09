package com.medbox.server.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.medbox.server.common.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RTest {

    @Test
    @DisplayName("成功响应：code=0 / message=success / data 原样")
    void ok() {
        R<String> r = R.ok("pong");

        assertThat(r.getCode()).isEqualTo(0);
        assertThat(r.getMessage()).isEqualTo("success");
        assertThat(r.getData()).isEqualTo("pong");
        assertThat(r.successful()).isTrue();
    }

    @Test
    @DisplayName("timestamp 为 epoch 秒")
    void timestampInSeconds() {
        long before = System.currentTimeMillis() / 1000;
        R<String> r = R.ok("pong");
        long after = System.currentTimeMillis() / 1000;

        assertThat(r.getTimestamp()).isBetween(before, after);
    }

    @Test
    @DisplayName("失败响应：错误码与 HTTP 状态按文档 02 的 1.2 一一对应")
    void fail() {
        R<Void> r = R.fail(ErrorCode.GUARDIAN_PENDING);

        assertThat(r.getCode()).isEqualTo(40302);
        assertThat(r.getMessage()).isEqualTo(ErrorCode.GUARDIAN_PENDING.message());
        assertThat(r.getData()).isNull();
        assertThat(r.successful()).isFalse();
        assertThat(ErrorCode.GUARDIAN_PENDING.httpStatus().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("错误码枚举覆盖文档 1.2 的全部码值")
    void errorCodesMatchSpec() {
        assertThat(ErrorCode.of(40001)).isEqualTo(ErrorCode.BAD_REQUEST);
        assertThat(ErrorCode.of(40101)).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThat(ErrorCode.of(40102)).isEqualTo(ErrorCode.BAD_CREDENTIALS);
        assertThat(ErrorCode.of(40301)).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(ErrorCode.of(40302)).isEqualTo(ErrorCode.GUARDIAN_PENDING);
        assertThat(ErrorCode.of(40401)).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ErrorCode.of(40901)).isEqualTo(ErrorCode.CONFLICT);
        assertThat(ErrorCode.of(42901)).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        assertThat(ErrorCode.of(50000)).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(ErrorCode.of(50310)).isEqualTo(ErrorCode.AI_UNAVAILABLE);
        // 未知码不往外透
        assertThat(ErrorCode.of(99999)).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("分页包装：page / size / total / list")
    void pageResult() {
        PageResult<String> page = PageResult.of(List.of("a", "b"), 137, 2, 20);

        assertThat(page.getPage()).isEqualTo(2);
        assertThat(page.getSize()).isEqualTo(20);
        assertThat(page.getTotal()).isEqualTo(137);
        assertThat(page.getList()).containsExactly("a", "b");
        assertThat(PageResult.<String>empty(1, 20).getList()).isEmpty();
    }
}
