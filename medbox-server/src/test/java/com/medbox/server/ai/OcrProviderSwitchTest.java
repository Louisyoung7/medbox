package com.medbox.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 验证 {@code medbox.ocr.provider=cloud} 时切到云端 OCR 实现（CI 可跑，不依赖外部服务）。
 */
@SpringBootTest(properties = "medbox.ocr.provider=cloud")
class OcrProviderSwitchTest {

    @Autowired
    private OcrClient ocrClient;

    @Test
    void ocrClientSwitchesToCloudImplementation() {
        assertThat(ocrClient).isInstanceOf(CloudOcrClient.class);
    }
}
