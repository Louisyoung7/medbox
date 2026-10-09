package com.medbox.server.common.exception;

/**
 * 业务异常：携带 {@link ErrorCode}，由 {@code GlobalExceptionHandler} 翻译成统一响应。
 *
 * <p>业务分支一律用它（或 {@link BizAssert}）抛出可预期的失败，由全局异常处理统一出口；
 * 不要自己 catch 后拼错误响应，避免错误码散落各处。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    private BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message == null || message.isBlank() ? errorCode.message() : message, cause);
        this.errorCode = errorCode;
    }

    public static BizException of(ErrorCode errorCode) {
        return new BizException(errorCode, errorCode.message(), null);
    }

    /** 用自定义文案覆盖默认提示（如"计划 p-3301 不存在"），错误码不变。 */
    public static BizException of(ErrorCode errorCode, String message) {
        return new BizException(errorCode, message, null);
    }

    public static BizException of(ErrorCode errorCode, Throwable cause) {
        return new BizException(errorCode, errorCode.message(), cause);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public int code() {
        return errorCode.code();
    }

    /**
     * 业务异常是流程控制，不需要堆栈：跳过填充可省掉每次抛出时的栈采集开销。
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
