-- ============================================
-- V34: 加密市场尾盘策略表
-- ============================================
CREATE TABLE IF NOT EXISTS crypto_tail_strategy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '策略ID',
    account_id BIGINT NOT NULL COMMENT '钱包账户ID',
    name VARCHAR(255) DEFAULT NULL COMMENT '策略名称（可选，用于列表展示）',
    market_slug_prefix VARCHAR(64) NOT NULL COMMENT '市场 slug 前缀，如 btc-updown-5m、btc-updown-15m',
    interval_seconds INT NOT NULL COMMENT '周期长度秒数：300(5分钟) 或 900(15分钟)',
    window_start_seconds INT NOT NULL COMMENT '时间窗口开始秒数（相对周期起点）',
    window_end_seconds INT NOT NULL COMMENT '时间窗口结束秒数（相对周期起点）',
    min_price DECIMAL(20, 8) NOT NULL COMMENT '最低触发价格 0~1',
    max_price DECIMAL(20, 8) NOT NULL DEFAULT 1 COMMENT '最高触发价格 0~1，默认1',
    amount_mode VARCHAR(10) NOT NULL DEFAULT 'RATIO' COMMENT '投入方式: RATIO=按比例, FIXED=固定金额',
    amount_value DECIMAL(20, 8) NOT NULL COMMENT '比例(0~100)或固定USDC金额',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用: 0=停用, 1=启用',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    INDEX idx_account_id (account_id),
    INDEX idx_enabled (enabled),
    FOREIGN KEY (account_id) REFERENCES wallet_accounts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='加密市场尾盘策略表';

-- ============================================
-- 触发记录表
-- ============================================
CREATE TABLE IF NOT EXISTS crypto_tail_strategy_trigger (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
    strategy_id BIGINT NOT NULL COMMENT '策略ID',
    period_start_unix BIGINT NOT NULL COMMENT '周期起点 Unix 秒',
    market_title VARCHAR(500) DEFAULT NULL COMMENT '市场标题',
    outcome_index INT NOT NULL COMMENT '方向: 0=Up, 1=Down',
    trigger_price DECIMAL(20, 8) NOT NULL COMMENT '触发时价格',
    amount_usdc DECIMAL(20, 8) NOT NULL COMMENT '投入金额 USDC',
    order_id VARCHAR(128) DEFAULT NULL COMMENT '订单ID（成功时有值）',
    status VARCHAR(20) NOT NULL DEFAULT 'success' COMMENT '状态: success, fail',
    fail_reason VARCHAR(500) DEFAULT NULL COMMENT '失败原因',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    INDEX idx_strategy_id (strategy_id),
    INDEX idx_period (strategy_id, period_start_unix),
    INDEX idx_created_at (created_at),
    FOREIGN KEY (strategy_id) REFERENCES crypto_tail_strategy(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='尾盘策略触发记录表';
