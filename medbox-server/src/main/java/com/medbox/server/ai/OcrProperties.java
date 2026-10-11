package com.medbox.server.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定 {@code medbox.ocr.*}（见文档 05 的 4.1）：本地 RapidOCR 侧车地址与实现切换。
 */
@ConfigurationProperties(prefix = "medbox.ocr")
public class OcrProperties {

    /** local=本机 RapidOCR 侧车；cloud=云端 OCR API（需 Key，本期骨架未实装）。 */
    private String provider = "local";

    /** RapidOCR 侧车地址，默认 {@code http://localhost:8000/ocr}。 */
    private String endpoint = "http://localhost:8000/ocr";

    /** 调用超时（毫秒），默认 30000。 */
    private long timeoutMillis = 30_000;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }
}
