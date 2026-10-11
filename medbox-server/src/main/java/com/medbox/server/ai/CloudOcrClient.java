package com.medbox.server.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 云端 OCR 骨架（{@code medbox.ocr.provider=cloud} 时启用，见文档 05 的 4.1）。
 *
 * <p>本期不实现：本地优先是项目既定方案，云端 API 需实名认证、后端放行出网、且说明书属健康数据会出网，
 * 故 {@code feat/backend-ai-infra} 只预留切换点，实装留给后续按需分支。
 */
public class CloudOcrClient implements OcrClient {

    private static final Logger log = LoggerFactory.getLogger(CloudOcrClient.class);

    private final OcrProperties props;

    public CloudOcrClient(OcrProperties props) {
        this.props = props;
    }

    @Override
    public String ocr(byte[] fileBytes, String contentType) {
        log.warn("云端 OCR 尚未实现（provider=cloud），请切换到 local 或等待后续分支实装");
        throw new OcrException("云端 OCR 尚未实现");
    }
}
