-- V24: 下线药品规格字段 spec —— 小程序已不再收集/展示规格，纯档案记录只保留
-- 名称 / 默认单次剂量 / 单位 / 禁忌 / 储存条件（见文档 06 的 2.4）

ALTER TABLE medicine DROP COLUMN spec;
