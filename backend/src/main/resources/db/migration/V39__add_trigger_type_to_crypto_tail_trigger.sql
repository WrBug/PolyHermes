-- 添加触发类型字段到加密价差策略触发记录表
-- AUTO: 自动下单触发
-- MANUAL: 手动下单触发
ALTER TABLE crypto_tail_strategy_trigger 
ADD COLUMN trigger_type VARCHAR(20) DEFAULT 'AUTO' COMMENT '触发类型：AUTO（自动）或 MANUAL（手动）';
