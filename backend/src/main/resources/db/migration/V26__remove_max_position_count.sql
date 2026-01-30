-- ============================================
-- V26: 移除最大仓位数量配置字段
-- 从 copy_trading 表中删除 max_position_count 字段
-- ============================================

ALTER TABLE copy_trading
DROP COLUMN max_position_count;

