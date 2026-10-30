-- feat/backend-capture-event（07 清单 P2 改造项）：
-- 摄像头抓拍由 MQTT up/event/dispense 改为经 capture-core HTTP 上传上报，
-- 判定核心复用 DispenseConsumeService，需为 capture / med_record 补 faceResult / confidence 列。
-- 只新增列，不改动 V1~V16（validate-on-migrate=true，改了会启动失败）。
-- 注意：编号取仓库当前最大 V{n}+1（合并时若已存在更大版本，按 max+1 重排）。

-- 2.12 capture 表：补摄像头三态结论列（TAKEN/ABSENT/UNCERTAIN，可空）。
ALTER TABLE capture ADD COLUMN IF NOT EXISTS face_result VARCHAR(16);
COMMENT ON COLUMN capture.face_result IS '摄像头三态结论 TAKEN/ABSENT/UNCERTAIN（可空，留证用）';

-- 2.7 med_record 表：补服药结论 + 识别置信度，供监护人看图复核（11 §1 规则5）。
ALTER TABLE med_record ADD COLUMN IF NOT EXISTS face_result VARCHAR(16);
COMMENT ON COLUMN med_record.face_result IS '服药结论 TAKEN/ABSENT/UNCERTAIN（可空）';

ALTER TABLE med_record ADD COLUMN IF NOT EXISTS confidence NUMERIC(4, 3);
COMMENT ON COLUMN med_record.confidence IS '摄像头识别置信度 0~1（可空）';
