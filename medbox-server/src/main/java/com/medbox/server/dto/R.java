package com.medbox.server.dto;

import com.medbox.server.common.TraceContext;
import com.medbox.server.common.exception.ErrorCode;

/**
 * 统一响应包装（见文档 02 的 1.1）。
 *
 * <pre>{@code
 * {
 *   "code": 0,
 *   "message": "success",
 *   "data": { },
 *   "traceId": "9f2c...",
 *   "timestamp": 1759474487
 * }
 * }</pre>
 *
 * <p>所有 Controller 一律返回 {@code R<T>}：成功走 {@link #ok(Object)}，失败由业务抛出
 * {@code BizException}、由 {@code GlobalExceptionHandler} 转成 {@link #fail(ErrorCode, String)}，
 * 不要在 Controller 里自己拼错误响应。
 *
 * <p>{@code data} 为 null 时受全局 {@code spring.jackson.default-property-inclusion: non_null}
 * 影响不出现在 JSON 里（文档允许"失败时可为 null"）。
 */
public final class R<T> {

    /** 0=成功，非 0=业务错误码（见 {@link ErrorCode}）。 */
    private final int code;

    /** 成功为 "success"，失败为可读的中文提示。 */
    private final String message;

    /** 业务数据；失败时为 null。 */
    private final T data;

    /** 链路追踪 ID，取自 {@link TraceContext}。 */
    private final String traceId;

    /** 响应时间戳，epoch 秒。 */
    private final long timestamp;

    private R(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = TraceContext.currentTraceId();
        this.timestamp = System.currentTimeMillis() / 1000;
    }

    public static <T> R<T> ok() {
        return new R<>(ErrorCode.SUCCESS.code(), ErrorCode.SUCCESS.message(), null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(ErrorCode.SUCCESS.code(), ErrorCode.SUCCESS.message(), data);
    }

    public static <T> R<T> fail(ErrorCode errorCode) {
        return new R<>(errorCode.code(), errorCode.message(), null);
    }

    /** 用自定义文案覆盖默认提示，错误码保持不变。 */
    public static <T> R<T> fail(ErrorCode errorCode, String message) {
        return new R<>(errorCode.code(),
                (message == null || message.isBlank()) ? errorCode.message() : message, null);
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public String getTraceId() {
        return traceId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /**
     * 是否成功。方法名刻意不写成 {@code isSuccess()}：那会被 Jackson 当成一个叫
     * {@code success} 的属性序列化出去，多出文档里没有的字段。
     */
    public boolean successful() {
        return code == ErrorCode.SUCCESS.code();
    }
}
