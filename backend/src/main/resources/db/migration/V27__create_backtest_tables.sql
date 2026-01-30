-- ============================================
-- 回测功能表创建
-- ============================================

-- ============================================
-- 2. 创建回测任务表
-- ============================================
CREATE TABLE IF NOT EXISTS backtest_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '回测任务ID',
    task_name VARCHAR(100) NOT NULL COMMENT '回测任务名称',
    leader_id BIGINT NOT NULL COMMENT 'Leader ID',
    initial_balance DECIMAL(20, 8) NOT NULL COMMENT '初始资金',
    final_balance DECIMAL(20, 8) DEFAULT NULL COMMENT '最终资金',
    profit_amount DECIMAL(20, 8) DEFAULT NULL COMMENT '收益金额',
    profit_rate DECIMAL(10, 4) DEFAULT NULL COMMENT '收益率(%)',
    backtest_days INT NOT NULL COMMENT '回测天数',
    start_time BIGINT NOT NULL COMMENT '回测开始时间(历史时间)',
    end_time BIGINT DEFAULT NULL COMMENT '回测结束时间(历史时间)',

    -- 跟单配置 (复制CopyTrading表结构)
    copy_mode VARCHAR(10) NOT NULL DEFAULT 'RATIO' COMMENT '跟单模式: RATIO/FIXED',
    copy_ratio DECIMAL(20, 8) NOT NULL DEFAULT 1.0 COMMENT '跟单比例',
    fixed_amount DECIMAL(20, 8) DEFAULT NULL COMMENT '固定金额',
    max_order_size DECIMAL(20, 8) NOT NULL DEFAULT 1000.0 COMMENT '最大单笔订单',
    min_order_size DECIMAL(20, 8) NOT NULL DEFAULT 1.0 COMMENT '最小单笔订单',
    max_daily_loss DECIMAL(20, 8) NOT NULL DEFAULT 10000.0 COMMENT '最大每日亏损',
    max_daily_orders INT NOT NULL DEFAULT 100 COMMENT '最大每日订单数',
    price_tolerance DECIMAL(5, 2) NOT NULL DEFAULT 5.0 COMMENT '价格容忍度(%)',
    delay_seconds INT NOT NULL DEFAULT 0 COMMENT '延迟秒数',
    support_sell BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否支持卖出',
    min_order_depth DECIMAL(20, 8) DEFAULT NULL COMMENT '最小订单深度',
    max_spread DECIMAL(20, 8) DEFAULT NULL COMMENT '最大价差',
    min_price DECIMAL(20, 8) DEFAULT NULL COMMENT '最低价格',
    max_price DECIMAL(20, 8) DEFAULT NULL COMMENT '最高价格',
    max_position_value DECIMAL(20, 8) DEFAULT NULL COMMENT '最大仓位金额',
    keyword_filter_mode VARCHAR(20) NOT NULL DEFAULT 'DISABLED' COMMENT '关键字过滤模式',
    keywords JSON DEFAULT NULL COMMENT '关键字列表',
    max_market_end_date BIGINT DEFAULT NULL COMMENT '市场截止时间限制',

    -- 统计字段
    avg_holding_time BIGINT DEFAULT NULL COMMENT '平均持仓时间(毫秒)',
    data_source VARCHAR(50) DEFAULT 'MIXED' COMMENT '数据源: INTERNAL/API/MIXED',

    -- 执行状态
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/RUNNING/COMPLETED/STOPPED/FAILED',
    progress INT DEFAULT 0 COMMENT '执行进度(0-100)',
    total_trades INT DEFAULT 0 COMMENT '总交易笔数',
    buy_trades INT DEFAULT 0 COMMENT '买入笔数',
    sell_trades INT DEFAULT 0 COMMENT '卖出笔数',
    win_trades INT DEFAULT 0 COMMENT '盈利交易笔数',
    loss_trades INT DEFAULT 0 COMMENT '亏损交易笔数',
    win_rate DECIMAL(5, 2) DEFAULT NULL COMMENT '胜率(%)',
    max_profit DECIMAL(20, 8) DEFAULT NULL COMMENT '最大单笔盈利',
    max_loss DECIMAL(20, 8) DEFAULT NULL COMMENT '最大单笔亏损',
    max_drawdown DECIMAL(20, 8) DEFAULT NULL COMMENT '最大回撤',
    error_message TEXT DEFAULT NULL COMMENT '错误信息',

    created_at BIGINT NOT NULL COMMENT '创建时间',
    execution_started_at BIGINT DEFAULT NULL COMMENT '执行开始时间(系统时间)',
    execution_finished_at BIGINT DEFAULT NULL COMMENT '执行完成时间(系统时间)',
    updated_at BIGINT NOT NULL COMMENT '更新时间',

    INDEX idx_leader_id (leader_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at),
    INDEX idx_leader_profit (leader_id, profit_rate DESC),
    INDEX idx_status_created (status, created_at DESC),
    FOREIGN KEY (leader_id) REFERENCES copy_trading_leaders(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回测任务表';

-- ============================================
-- 3. 创建回测交易记录表
-- ============================================
CREATE TABLE IF NOT EXISTS backtest_trade (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '交易记录ID',
    backtest_task_id BIGINT NOT NULL COMMENT '回测任务ID',
    trade_time BIGINT NOT NULL COMMENT '交易时间',
    market_id VARCHAR(100) NOT NULL COMMENT '市场ID',
    market_title VARCHAR(500) DEFAULT NULL COMMENT '市场标题',
    side VARCHAR(20) NOT NULL COMMENT '方向: BUY/SELL/SETTLEMENT',
    outcome VARCHAR(50) NOT NULL COMMENT '结果: YES/NO或outcomeIndex',
    outcome_index INT DEFAULT NULL COMMENT '结果索引（0, 1, 2, ...），支持多元市场',
    quantity DECIMAL(20, 8) NOT NULL COMMENT '数量',
    price DECIMAL(20, 8) NOT NULL COMMENT '价格',
    amount DECIMAL(20, 8) NOT NULL COMMENT '金额',
    fee DECIMAL(20, 8) NOT NULL DEFAULT 0.0 COMMENT '手续费',
    profit_loss DECIMAL(20, 8) DEFAULT NULL COMMENT '盈亏(仅卖出时)',
    balance_after DECIMAL(20, 8) NOT NULL COMMENT '交易后余额',
    leader_trade_id VARCHAR(100) DEFAULT NULL COMMENT 'Leader原始交易ID',

    created_at BIGINT NOT NULL COMMENT '创建时间',

    INDEX idx_backtest_task_id (backtest_task_id),
    INDEX idx_trade_time (trade_time),
    FOREIGN KEY (backtest_task_id) REFERENCES backtest_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回测交易记录表';

