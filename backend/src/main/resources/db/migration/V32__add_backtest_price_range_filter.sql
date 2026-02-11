-- ============================================
-- V32: 添加回测价格区间过滤字段
-- 用于配置价格区间，仅在指定价格区间内的订单才会跟单
-- ============================================

-- 添加价格区间字段到回测任务表
ALTER TABLE backtest_task
ADD COLUMN min_price DECIMAL(20, 8) NULL COMMENT '最低价格（可选），NULL表示不限制最低价',
ADD COLUMN max_price DECIMAL(20, 8) NULL COMMENT '最高价格（可选），NULL表示不限制最高价';
