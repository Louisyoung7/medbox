package com.medbox.server.ai;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 相关 bean 的条件化装配（feat/backend-ai-infra）。
 *
 * <p>OCR：按 {@code medbox.ocr.provider} 在 {@link LocalOcrClient} / {@link CloudOcrClient} 间切换，默认 local。
 * Embedding（Ollama bge-m3）与 PgVectorStore 由 Spring AI 自动装配（见 application.yml 的 spring.ai.*），本类不重复声明。
 */
@Configuration
@EnableConfigurationProperties(OcrProperties.class)
public class AiConfig {

    private final ObjectMapper objectMapper;

    @Autowired
    public AiConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    @ConditionalOnProperty(prefix = "medbox.ocr", name = "provider", havingValue = "local", matchIfMissing = true)
    public OcrClient localOcrClient(OcrProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.getTimeoutMillis()));
        factory.setReadTimeout(Duration.ofMillis(props.getTimeoutMillis()));
        RestClient restClient = RestClient.builder().requestFactory(factory).build();
        return new LocalOcrClient(props, restClient, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "medbox.ocr", name = "provider", havingValue = "cloud")
    public OcrClient cloudOcrClient(OcrProperties props) {
        return new CloudOcrClient(props);
    }
}
