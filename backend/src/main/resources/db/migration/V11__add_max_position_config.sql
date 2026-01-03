-- ============================================
-- V11: 添加最大仓位配置字段
-- 在 copy_trading 表中添加最大仓位金额和最大仓位数量配置
-- ============================================

ALTER TABLE copy_trading
ADD COLUMN max_position_value DECIMAL(20, 8) NULL COMMENT '最大仓位金额（USDC），NULL表示不启用' AFTER max_price,
ADD COLUMN max_position_count INT NULL COMMENT '最大仓位数量，NULL表示不启用' AFTER max_position_value;

