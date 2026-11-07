-- feat/backend-capture-taken-result：取药结论字段 faceResult → takenResult（文档 11 §1 / 02b §1.1）
-- 仅重命名列；confidence 列保留（已废弃、不再写入），不改。

ALTER TABLE capture RENAME COLUMN face_result TO taken_result;
COMMENT ON COLUMN capture.taken_result IS '取药结论 TAKEN/UNTAKEN（可空，留证用）';

ALTER TABLE med_record RENAME COLUMN face_result TO taken_result;
COMMENT ON COLUMN med_record.taken_result IS '取药结论 TAKEN/UNTAKEN（可空，监护人校准 / 补录后改写）';
