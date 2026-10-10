-- V2 · 说明书分块的向量近邻索引（HNSW + 余弦距离）
-- 依据：medbox-spec/06_数据库设计.md 3.2 节；验收项"drug_manual_chunk 的 HNSW 索引建立"
-- 换 embedding 模型（维度变化）时：先删本索引、改列类型、重建全部分块向量后再执行本语句

CREATE INDEX IF NOT EXISTS idx_drug_manual_chunk_embedding
    ON drug_manual_chunk USING hnsw (embedding vector_cosine_ops);
