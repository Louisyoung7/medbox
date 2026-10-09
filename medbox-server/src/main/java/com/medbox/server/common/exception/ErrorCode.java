package com.medbox.server.common.exception;

import java.util.Arrays;
import org.springframework.http.HttpStatus;

/**
 * 统一错误码：业务码 + 对应 HTTP 状态 + 默认提示（见文档 02 的 1.2）。
 *
 * <p>响应体里的 {@code code} 用本枚举的 {@link #code()}；HTTP 状态码用 {@link #httpStatus()}，
 * 二者一一对应，小程序既可按 {@code code} 细分处理，也可按 HTTP 状态快速分流。
 *
 * <p>新增错误码必须同步改文档 02 的 1.2，否则文档会落后于代码。
 */
public enum ErrorCode {

    /** 成功（code=0）。 */
    SUCCESS(0, HttpStatus.OK, "success"),

    /** 参数校验失败。 */
    BAD_REQUEST(40001, HttpStatus.BAD_REQUEST, "参数校验失败"),

    /** 未登录 / Token 过期。 */
    UNAUTHORIZED(40101, HttpStatus.UNAUTHORIZED, "未登录或 Token 已过期"),

    /** 账号或密码错误（登录失败不区分"账号不存在 / 密码错误"）。 */
    BAD_CREDENTIALS(40102, HttpStatus.UNAUTHORIZED, "账号或密码错误"),

    /** 无权限（越权访问他人数据 / 设备）。 */
    FORBIDDEN(40301, HttpStatus.FORBIDDEN, "无权限访问该资源"),

    /** 监护关系未生效（关系仍为 PENDING）。 */
    GUARDIAN_PENDING(40302, HttpStatus.FORBIDDEN, "监护关系未生效，请等待老人确认"),

    /** 资源不存在。 */
    NOT_FOUND(40401, HttpStatus.NOT_FOUND, "资源不存在"),

    /** 状态冲突（如设备离线无法下发命令、药品仍被库存引用无法删除）。 */
    CONFLICT(40901, HttpStatus.CONFLICT, "状态冲突"),

    /** 触发限流。 */
    TOO_MANY_REQUESTS(42901, HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试"),

    /** 服务端内部错误（未捕获异常的统一出口）。 */
    INTERNAL_ERROR(50000, HttpStatus.INTERNAL_SERVER_ERROR, "服务端内部错误"),

    /** AI 大模型服务暂不可用 / API Key 无效。 */
    AI_UNAVAILABLE(50310, HttpStatus.SERVICE_UNAVAILABLE, "AI 服务暂不可用");

    private final int code;
    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(int code, HttpStatus httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }

    /** 按业务码反查枚举；未知码一律按 50000 处理（避免把未知码透出给客户端）。 */
    public static ErrorCode of(int code) {
        return Arrays.stream(values())
                .filter(item -> item.code == code)
                .findFirst()
                .orElse(INTERNAL_ERROR);
    }

    /** 按 HTTP 状态取最接近的错误码，用于兜底 Spring 自带的带状态异常。 */
    public static ErrorCode of(HttpStatus httpStatus) {
        if (httpStatus == null) {
            return INTERNAL_ERROR;
        }
        return switch (httpStatus) {
            case BAD_REQUEST -> BAD_REQUEST;
            case UNAUTHORIZED -> UNAUTHORIZED;
            case FORBIDDEN -> FORBIDDEN;
            case NOT_FOUND -> NOT_FOUND;
            case CONFLICT -> CONFLICT;
            case TOO_MANY_REQUESTS -> TOO_MANY_REQUESTS;
            case SERVICE_UNAVAILABLE -> AI_UNAVAILABLE;
            default -> INTERNAL_ERROR;
        };
    }
}
