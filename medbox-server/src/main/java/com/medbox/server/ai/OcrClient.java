package com.medbox.server.ai;

/**
 * OCR 抽象：把"图片 / PDF → 纯文本"的能力与具体实现（本地 RapidOCR 侧车 / 云端 API）解耦。
 *
 * <p>实现按 {@code medbox.ocr.provider} 切换（见 {@link AiConfig}）。本分支实装本地侧车，
 * 云端实现仅留骨架（见文档 05 的 4.1）。上层 {@code feat/manual-ingest} 直接注入本接口使用。
 */
public interface OcrClient {

    /**
     * 对图片 / PDF 做 OCR，返回按行 / 段落组织的纯文本。
     *
     * @param fileBytes   文件二进制（图片或 PDF）
     * @param contentType 内容类型，如 {@code image/jpeg} / {@code application/pdf}
     * @return 识别出的文本（不为 {@code null}；识别为空返回空串而非抛异常）
     * @throws OcrException 调用侧车 / 云端失败
     */
    String ocr(byte[] fileBytes, String contentType);
}
