package com.medbox.server.domain;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

@Data
@TableName("llm_config")
public class LlmConfig{
    @TableId(type=IdType.AUTO)
    private Long id;
    private String configId;
    private String elderId;
    private String provider;
    private String apiKeyEnc;
    private String model;
    private String baseUrl;
    private Boolean enabled;
    private OffsetDateTime updatedAt;
    private Boolean insightEnabled;
    private String insightCadence;
    private OffsetDateTime lastInsightAt;
}