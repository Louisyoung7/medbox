-- V26 · 药品档案：「禁忌」升级为「药品说明」
-- 依据：medbox-spec/06 §2.4、05 §1.1（AI 用药安全洞察只依据监护人手动填写的说明）
-- 说明：字段语义从"禁忌"扩展为"药品说明"（成分 / 适应症 / 禁忌 / 注意事项），
--       作为 AI 判断的唯一事实来源；历史数据保留原文本，不丢。
ALTER TABLE medicine RENAME COLUMN contraindication TO description;
