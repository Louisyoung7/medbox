package com.medbox.server.ai;

/**
 * OCR 调用失败异常（内部异常）。
 *
 * <p>本分支不把 OCR 暴露为 REST 接口（AI 接口属 {@code feat/ai-chat}），故直接继承
 * {@link RuntimeException}，不引入新错误码；后续若需经 HTTP 透出，再包成 {@code BizException}。
 */
public class OcrException extends RuntimeException {

    public OcrException(String message) {
        super(message);
    }

    public OcrException(String message, Throwable cause) {
        super(message, cause);
    }
}
