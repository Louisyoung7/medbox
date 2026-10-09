package com.medbox.server.common.exception;

/**
 * 业务断言：校验不通过时抛 {@link BizException}，替代满屏的 {@code if (...) throw}。
 *
 * <pre>{@code
 * BizAssert.notFound(plan != null, "计划不存在");
 * BizAssert.forbiddenIf(!isGuardian, "非监护人不可修改计划");
 * }</pre>
 */
public final class BizAssert {

    private BizAssert() {
    }

    /** 条件为真时抛错：{@code isTrue(plan != null, ...)} 这种"必须成立"的写法。 */
    public static void isTrue(boolean expression, ErrorCode errorCode) {
        isTrue(expression, errorCode, null);
    }

    public static void isTrue(boolean expression, ErrorCode errorCode, String message) {
        if (!expression) {
            throw BizException.of(errorCode, message);
        }
    }

    /** 条件为真（异常）时抛错，语义与 {@link #isTrue} 相反，读起来更直观。 */
    public static void badRequestIf(boolean condition, String message) {
        failIf(condition, ErrorCode.BAD_REQUEST, message);
    }

    public static void unauthorizedIf(boolean condition, String message) {
        failIf(condition, ErrorCode.UNAUTHORIZED, message);
    }

    public static void forbiddenIf(boolean condition, String message) {
        failIf(condition, ErrorCode.FORBIDDEN, message);
    }

    /** 监护关系仍为 PENDING（40302）。 */
    public static void pendingIf(boolean condition, String message) {
        failIf(condition, ErrorCode.GUARDIAN_PENDING, message);
    }

    public static void notFoundIf(boolean condition, String message) {
        failIf(condition, ErrorCode.NOT_FOUND, message);
    }

    public static void conflictIf(boolean condition, String message) {
        failIf(condition, ErrorCode.CONFLICT, message);
    }

    /** 查不到实体直接 40401（等价 {@code notFoundIf(value == null, message)}）。 */
    public static void notFound(Object value, String message) {
        failIf(value == null, ErrorCode.NOT_FOUND, message);
    }

    public static void notBlank(String value, String message) {
        failIf(value == null || value.isBlank(), ErrorCode.BAD_REQUEST, message);
    }

    private static void failIf(boolean condition, ErrorCode errorCode, String message) {
        if (condition) {
            throw BizException.of(errorCode, message);
        }
    }
}
