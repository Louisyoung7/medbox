package com.medbox.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 本地 AI 集成测试（条件启用）。
 *
 * <p>仅当开发者在本地起好 Ollama（:11434，先 {@code ollama pull bge-m3}）与 RapidOCR 侧车（:8000），
 * 且设置环境变量 {@code MEDBOX_AI_LOCAL_TEST=true} 时才运行（CI 默认跳过）。
 *
 * <p>覆盖 feat/backend-ai-infra 的两条验收：① 中文文本拿到 1024 维向量；② 说明书图片 OCR 出文字；
 * 并顺带验证 PgVectorStore 的写入 + 相似召回回环。
 */
@SpringBootTest
@ActiveProfiles("ai-local")
@EnabledIfEnvironmentVariable(named = "MEDBOX_AI_LOCAL_TEST", matches = "true")
class AiLocalIntegrationTest {

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private OcrClient ocrClient;

    @Autowired
    private VectorStore vectorStore;

    /**
     * 覆盖 CI 的 {@code SPRING_FLYWAY_ENABLED=false}，让本测试能跑 Flyway 建表（含 vector 扩展），
     * 并由 ai-local profile 开启 PgVectorStore 的 initialize-schema。
     */
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Test
    void chineseTextEmbedsTo1024Dimensions() {
        float[] vector = embeddingModel.embed("阿莫西林用于敏感菌所致感染");
        assertThat(vector).hasSize(1024);
    }

    @Test
    void instructionImageOcrProducesText() throws IOException {
        byte[] image = renderTextImage("阿莫西林说明书 一次一粒 饭后服用");
        String text = ocrClient.ocr(image, "image/png");
        assertThat(text).isNotBlank();
    }

    @Test
    void vectorStoreWriteAndSimilaritySearchRoundTrip() {
        Document doc = new Document("阿莫西林用于细菌感染", Map.of("medicineId", "m-test"));
        vectorStore.add(List.of(doc));
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder().query("阿莫西林").topK(1).build());
        assertThat(results).isNotEmpty();
    }

    /** 用 JDK 自带的图形接口实时画一张带文字的图，作为 OCR 输入（无需外部测试资源）。 */
    private byte[] renderTextImage(String text) throws IOException {
        BufferedImage img = new BufferedImage(480, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.PLAIN, 28));
        g.drawString(text, 20, 110);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
