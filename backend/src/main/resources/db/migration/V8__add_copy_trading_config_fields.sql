-- ============================================
-- V8: 添加跟单配置新字段
-- 1. config_name: 配置名（可选）
-- 2. push_failed_orders: 推送失败订单（默认关闭）
-- 3. auto_redeem: 自动赎回（默认开启）
-- ============================================

-- 添加配置名字段
ALTER TABLE copy_trading 
ADD COLUMN config_name VARCHAR(255) NULL COMMENT '配置名（可选）';

-- 添加推送失败订单字段（默认关闭）
ALTER TABLE copy_trading 
ADD COLUMN push_failed_orders BOOLEAN NOT NULL DEFAULT FALSE COMMENT '推送失败订单（默认关闭）';

-- 添加自动赎回字段（默认开启）
ALTER TABLE copy_trading 
ADD COLUMN auto_redeem BOOLEAN NOT NULL DEFAULT TRUE COMMENT '自动赎回（默认开启）';

