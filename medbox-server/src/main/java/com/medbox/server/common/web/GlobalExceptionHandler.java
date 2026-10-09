package com.medbox.server.common.web;

import com.medbox.server.common.TraceContext;
import com.medbox.server.common.exception.BizException;
import com.medbox.server.common.exception.ErrorCode;
import com.medbox.server.dto.R;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：把一切异常翻译成 {@link R}（见文档 02 的 1.1 / 1.2）。
 *
 * <p>异常 → 错误码的映射（与文档 1.2 保持一致）：
 *
 * <ul>
 *   <li>{@link BizException}：用异常自带的错误码（40101 / 40301 / 40302 / 40401 / 40901 ...）</li>
 *   <li>参数 / 请求体校验失败、缺参、类型不匹配、JSON 解析失败：40001</li>
 *   <li>路径或方法无匹配（含静态资源未命中）：40401</li>
 *   <li>Spring 自带的带状态异常 {@link ResponseStatusException}：按 HTTP 状态取最接近的错误码</li>
 *   <li>其余任何 {@link Throwable}：50000，error 日志留痕，不回显堆栈</li>
 * </ul>
 *
 * <p>4xx 属客户端问题，不打 error（避免日志被刷屏）；只有 5xx 记 error 并带上 traceId。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBizException(BizException ex) {
        log.debug("biz exception, code={}, message={}", ex.code(), ex.getMessage());
        return failure(ex.errorCode(), ex.getMessage());
    }

    /** {@code @Valid @RequestBody} 校验失败：取第一条字段错误作为提示。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getAllErrors().stream()
                .findFirst()
                .map(ObjectError::getDefaultMessage)
                .orElse(ErrorCode.BAD_REQUEST.message());
        return failure(ErrorCode.BAD_REQUEST, message);
    }

    /** 方法参数上的约束（如 {@code @RequestParam @Min}）校验失败。 */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<R<Void>> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        String message = ex.getAllErrors().stream()
                .findFirst()
                .map(MessageSourceResolvable::getDefaultMessage)
                .orElse(ErrorCode.BAD_REQUEST.message());
        return failure(ErrorCode.BAD_REQUEST, message);
    }

    /** {@code @Validated} 作用在 Service / 参数上的校验失败。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<R<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse(ErrorCode.BAD_REQUEST.message());
        return failure(ErrorCode.BAD_REQUEST, message);
    }

    /** 请求体缺失 / JSON 格式错误：不回显原始报文，避免信息泄漏。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        log.debug("unreadable request body: {}", ex.getMessage());
        return failure(ErrorCode.BAD_REQUEST, "请求体格式错误或字段类型不匹配");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<R<Void>> handleMissingParameter(MissingServletRequestParameterException ex) {
        return failure(ErrorCode.BAD_REQUEST, "缺少参数 " + ex.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<R<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return failure(ErrorCode.BAD_REQUEST, "参数 " + ex.getName() + " 类型不正确");
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<R<Void>> handleServletRequestBinding(ServletRequestBindingException ex) {
        return failure(ErrorCode.BAD_REQUEST, ex.getMessage());
    }

    /**
     * 无匹配的处理器 / 静态资源 / 请求方法（404 / 405）。
     *
     * <p>文档 1.2 没有 405 对应的错误码，这里统一按"资源不存在"返回 40401 + HTTP 404，
     * 保证错误码集合封闭（见 07 清单 {@code feat/backend-response}）。
     */
    @ExceptionHandler({
            NoHandlerFoundException.class,
            NoResourceFoundException.class,
            HttpRequestMethodNotSupportedException.class
    })
    public ResponseEntity<R<Void>> handleNotFound(Exception ex) {
        return failure(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.message());
    }

    /** Spring 自带的带状态异常（如 ResponseStatusException）：按状态取最接近的业务码。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<R<Void>> handleResponseStatus(ResponseStatusException ex) {
        ErrorCode errorCode = ErrorCode.of(HttpStatus.resolve(ex.getStatusCode().value()));
        return failure(errorCode, errorCode.message());
    }

    /** 兜底：任何未捕获异常都返回 50000，堆栈只进日志。 */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<R<Void>> handleThrowable(Throwable ex) {
        log.error("unhandled exception, traceId={}", TraceContext.currentTraceId(), ex);
        return failure(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message());
    }

    private static ResponseEntity<R<Void>> failure(ErrorCode errorCode, String message) {
        return ResponseEntity.status(errorCode.httpStatus()).body(R.fail(errorCode, message));
    }
}
