package com.medbox.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * AI 地基配置验证（CI 可跑，不依赖 Ollama / RapidOCR / PG）。
 *
 * <p>EmbeddingModel 与 PgVectorStore 由 Spring AI 自动装配（initialize-schema 默认 false，缺 PG 也不阻塞启动）；
 * OcrClient 默认走本地实现。
 */
@SpringBootTest
class AiInfraConfigTest {

    @Autowired
    private OcrClient ocrClient;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private VectorStore vectorStore;

    @Test
    void ocrClientDefaultsToLocalImplementation() {
        assertThat(ocrClient).isInstanceOf(LocalOcrClient.class);
    }

    @Test
    void embeddingModelAndVectorStoreBeansExist() {
        assertThat(embeddingModel).isNotNull();
        assertThat(vectorStore).isNotNull();
    }
}
