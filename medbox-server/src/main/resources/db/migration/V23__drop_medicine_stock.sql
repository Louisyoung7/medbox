-- V23: 下线 medicine_stock（库存台账）——药品降级为纯档案记录，数量/生产日期/有效期不再维护
-- 见文档 06 的 2.4（药品表保留）与 git 历史（原 2.5 库存表定义见 V1 / V8）

DROP TABLE IF EXISTS medicine_stock;

-- V8 建的业务号生成器（'s-' || nextval('medicine_stock_biz_id_seq')），随表一并下线
DROP SEQUENCE IF EXISTS medicine_stock_biz_id_seq;
