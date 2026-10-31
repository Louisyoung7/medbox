-- V18 · 下线 AI RAG 说明书表与 vector 扩展（refactor/ai-insight-layer）
-- 依据：medbox-spec/05 方向重定位（AI 重定位为"洞察层"，OCR / Embedding / pgvector 不再需要）；
--       07 清单第六章 refactor/ai-insight-layer。
--
-- V1 曾建 drug_manual / drug_manual_chunk（含 vector(1024) 列）与 `CREATE EXTENSION vector`，
-- V2 在其上建 HNSW 索引。V1 / V2 已落地历史、Flyway validate-on-migrate=true 不可改，
-- 故此处新增迁移下线：
--   1) 先删子表 drug_manual_chunk（表删除会级联移除 V2 的 HNSW 索引，vector 列随之消失）；
--   2) 再删父表 drug_manual；
--   3) 最后删 vector 扩展（此时已无任何对象依赖它）。
-- 新建库执行顺序 V1…V18：V2 在 V18 之前建好索引，V18 再整体 drop，无冲突。

DROP TABLE IF EXISTS drug_manual_chunk;
DROP TABLE IF EXISTS drug_manual;
DROP EXTENSION IF EXISTS vector;
