-- V28 · 彻底删除传感器子设备建模（只保留药箱主控）
--
-- 背景：设备自生成主控 ID、监护人扫码 / 手填注册；环境遥测直接挂主控 device_id，
-- 摄像头抓拍也用主控 ID。传感器 / 摄像头不再有独立 device 记录、device_id 或父子关系。
-- V1~V27 已发布且 validate-on-migrate=true，一律不改历史脚本，本迁移做一次性数据折算与删列。

-- ① 设备事件：子设备编号折算成父主控（在删子设备行之前做）
UPDATE device_event e
SET device_id = p.parent_device_id
FROM device p
WHERE e.device_id = p.device_id
  AND p.parent_device_id IS NOT NULL;

-- ② 环境遥测：gateway_device_id 本来就是上报主控，直接合回 device_id
UPDATE env_sample
SET device_id = gateway_device_id
WHERE gateway_device_id IS NOT NULL;

-- 没有 gateway_device_id 的孤立子设备读数无法折算，直接丢弃
DELETE FROM env_sample s
USING device d
WHERE s.device_id = d.device_id
  AND d.parent_device_id IS NOT NULL
  AND s.gateway_device_id IS NULL;

-- ③ 告警：历史子设备编号折算成父主控（dedup_key 属历史去重痕迹，不改写）
UPDATE alarm a
SET device_id = p.parent_device_id
FROM device p
WHERE a.device_id = p.device_id
  AND p.parent_device_id IS NOT NULL;

-- ④ 删除子设备行（必须最后做，前面都还依赖 parent_device_id）
DELETE FROM device WHERE parent_device_id IS NOT NULL;

-- ⑤ 删列与索引，收敛表结构
DROP INDEX IF EXISTS idx_device_parent;
ALTER TABLE device DROP COLUMN device_type;
ALTER TABLE device DROP COLUMN parent_device_id;
ALTER TABLE env_sample DROP COLUMN gateway_device_id;

COMMENT ON TABLE device IS '药箱主控（设备自生成 device_id，监护人注册）；不再建模传感器子设备';
COMMENT ON TABLE env_sample IS '环境遥测（主控直接上报，device_id 即主控）';
COMMENT ON COLUMN device_event.device_id IS '-> device.device_id（药箱主控）';
