-- ============================================
-- V41: 大单监听策略表
-- ============================================
CREATE TABLE IF NOT EXISTS whale_monitor_strategy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '策略ID',
    account_id BIGINT NOT NULL COMMENT '钱包账户ID',
    name VARCHAR(255) DEFAULT NULL COMMENT '策略名称（可选，用于列表展示）',
    condition_ids TEXT NOT NULL COMMENT '监听市场 conditionId 列表（JSON 数组）',
    window_seconds INT NOT NULL DEFAULT 10 COMMENT '聚合窗口秒数',
    threshold_amount DECIMAL(20, 8) NOT NULL COMMENT '触发阈值金额',
    order_amount DECIMAL(20, 8) NOT NULL COMMENT '固定下单金额',
    min_price DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '最低下单价 0~1',
    max_price DECIMAL(20, 8) NOT NULL DEFAULT 1 COMMENT '最高下单价 0~1',
    cooldown_seconds INT NOT NULL DEFAULT 60 COMMENT '冷却秒数（同一 tokenId 两次触发间隔）',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用: 0=停用, 1=启用',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    updated_at BIGINT NOT NULL COMMENT '更新时间',
    INDEX idx_account_id (account_id),
    INDEX idx_enabled (enabled),
    FOREIGN KEY (account_id) REFERENCES wallet_accounts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大单监听策略表';

-- ============================================
-- 大单监听触发记录表
-- ============================================
CREATE TABLE IF NOT EXISTS whale_monitor_trigger (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
    strategy_id BIGINT NOT NULL COMMENT '策略ID',
    condition_id VARCHAR(128) NOT NULL COMMENT '市场 conditionId',
    token_id VARCHAR(128) NOT NULL COMMENT '触发 tokenId',
    side VARCHAR(10) NOT NULL DEFAULT 'BUY' COMMENT '方向',
    trigger_volume DECIMAL(20, 8) NOT NULL COMMENT '触发时窗口累计金额',
    order_price DECIMAL(20, 8) NOT NULL COMMENT '下单价格',
    order_size DECIMAL(20, 8) NOT NULL COMMENT '下单数量',
    order_amount DECIMAL(20, 8) NOT NULL COMMENT '下单金额',
    order_id VARCHAR(128) DEFAULT NULL COMMENT '订单ID（成功时有值）',
    status VARCHAR(20) NOT NULL DEFAULT 'success' COMMENT '状态: success, fail',
    fail_reason VARCHAR(500) DEFAULT NULL COMMENT '失败原因',
    created_at BIGINT NOT NULL COMMENT '创建时间',
    INDEX idx_strategy_id (strategy_id),
    INDEX idx_token (strategy_id, token_id),
    INDEX idx_created_at (created_at),
    FOREIGN KEY (strategy_id) REFERENCES whale_monitor_strategy(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大单监听触发记录表';
