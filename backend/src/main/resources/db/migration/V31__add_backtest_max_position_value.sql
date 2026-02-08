-- 添加最大仓位金额配置到回测任务表
ALTER TABLE backtest_task
ADD COLUMN max_position_value DECIMAL(20, 8) COMMENT '最大仓位金额（USDC），NULL表示不启用';
