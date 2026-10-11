package com.medbox.server.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 本地 RapidOCR 侧车实现：把文件以 multipart/form-data 的 {@code file} 字段 POST 到 {@code endpoint}，
 * 解析返回的 JSON 取文本（兼容 {@code text} / {@code result} / {@code content} / {@code data} 字段，
 * 若为纯文本则原样返回）。
 *
 * <p>侧车约定见文档 05 的 4.1：FastAPI 包一层，{@code POST /ocr} 收图片 / PDF，返回纯文本。
 * 本实现对返回结构做了兼容处理，具体字段名以实际侧车为准。
 */
public class LocalOcrClient implements OcrClient {

    private static final Logger log = LoggerFactory.getLogger(LocalOcrClient.class);

    private final OcrProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public LocalOcrClient(OcrProperties props, RestClient restClient, ObjectMapper objectMapper) {
        this.props = props;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String ocr(byte[] fileBytes, String contentType) {
        ByteArrayResource resource = new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return "file";
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource);

        String response;
        try {
            response = restClient.post()
                    .uri(props.getEndpoint())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new OcrException("调用本地 OCR 侧车失败: " + props.getEndpoint(), e);
        }
        if (response == null || response.isBlank()) {
            return "";
        }
        return extractText(response);
    }

    private String extractText(String response) {
        String trimmed = response.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return trimmed;
        }
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            if (node.isObject()) {
                for (String key : new String[]{"text", "result", "content", "data", "ocr"}) {
                    JsonNode value = node.get(key);
                    if (value != null && value.isTextual() && !value.asText().isBlank()) {
                        return value.asText();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("OCR 响应不是可解析 JSON，按纯文本处理: {}", e.getMessage());
        }
        return trimmed;
    }
}
