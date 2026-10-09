package com.medbox.server.common.web;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.medbox.server.common.TraceContext;
import com.medbox.server.support.TestErrorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 验收 07 清单第 2 项：任意异常都返回统一结构，且错误码与文档 02 的 1.2 一致。
 */
@WebMvcTest(controllers = TestErrorController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("成功：code=0，data 原样返回，带 traceId 与秒级 timestamp")
    void success() throws Exception {
        mockMvc.perform(get("/api/v1/_test/ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").value("pong"))
                .andExpect(jsonPath("$.traceId", notNullValue()))
                .andExpect(jsonPath("$.timestamp").isNumber())
                // 只输出文档 1.1 定义的五个字段，不夹带 success 之类多余属性
                .andExpect(jsonPath("$.success").doesNotExist())
                .andExpect(header().string(TraceContext.TRACE_ID_HEADER, notNullValue()));
    }

    @Test
    @DisplayName("分页：data 为 page / size / total / list")
    void page() throws Exception {
        mockMvc.perform(get("/api/v1/_test/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list.length()").value(2));
    }

    @Test
    @DisplayName("traceId：客户端带 X-Request-Id 时原样透传")
    void traceIdFromRequestHeader() throws Exception {
        mockMvc.perform(get("/api/v1/_test/ok").header(TraceContext.REQUEST_ID_HEADER, "req-123456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("req-123456"))
                .andExpect(header().string(TraceContext.TRACE_ID_HEADER, "req-123456"));
    }

    @Test
    @DisplayName("参数校验失败 → 40001 / HTTP 400，提示取第一条字段错误")
    void validationFailed() throws Exception {
        mockMvc.perform(post("/api/v1/_test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("name 不能为空"))
                .andExpect(jsonPath("$.traceId", notNullValue()));
    }

    @Test
    @DisplayName("业务异常 → 用异常自带错误码（40401 / HTTP 404）")
    void bizException() throws Exception {
        mockMvc.perform(get("/api/v1/_test/biz"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401))
                .andExpect(jsonPath("$.message").value("计划 p-3301 不存在"));
    }

    @Test
    @DisplayName("缺参数 → 40001 / HTTP 400")
    void missingParameter() throws Exception {
        mockMvc.perform(get("/api/v1/_test/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("缺少参数 name"));
    }

    @Test
    @DisplayName("请求体 JSON 非法 → 40001，且不回显原始报文")
    void unreadableBody() throws Exception {
        mockMvc.perform(post("/api/v1/_test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-a-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("请求体格式错误或字段类型不匹配"));
    }

    @Test
    @DisplayName("未捕获异常 → 50000 / HTTP 500，且不泄漏堆栈")
    void unhandledException() throws Exception {
        mockMvc.perform(get("/api/v1/_test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50000))
                .andExpect(jsonPath("$.message").value("服务端内部错误"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("未知路径 → 40401 / HTTP 404（不是 Spring 默认的错误页）")
    void unknownPath() throws Exception {
        mockMvc.perform(get("/api/v1/_test/not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401))
                .andExpect(jsonPath("$.message").value("资源不存在"))
                .andExpect(jsonPath("$.traceId", notNullValue()));
    }

    @Test
    @DisplayName("请求方法不支持 → 40401（文档无 405 错误码，按资源不存在处理）")
    void methodNotSupported() throws Exception {
        mockMvc.perform(post("/api/v1/_test/ok"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }
}
