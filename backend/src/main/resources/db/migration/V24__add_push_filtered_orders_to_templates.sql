-- ============================================
-- V24: 添加推送已过滤订单字段到模板表和跟单配置表
-- 用于配置是否推送被过滤的订单通知，默认关闭
-- ============================================

-- 添加推送已过滤订单字段到模板表
ALTER TABLE copy_trading_templates
ADD COLUMN push_filtered_orders BOOLEAN NOT NULL DEFAULT FALSE COMMENT '推送已过滤订单（默认关闭）';

-- 添加推送已过滤订单字段到跟单配置表
ALTER TABLE copy_trading
ADD COLUMN push_filtered_orders BOOLEAN NOT NULL DEFAULT FALSE COMMENT '推送已过滤订单（默认关闭）';

