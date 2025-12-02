-- ============================================
-- 数据库初始化脚本（合并所有迁移版本）
-- ============================================

-- ============================================
-- 1. 创建账户表
-- ============================================
CREATE TABLE IF NOT EXISTS wallet_accounts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    private_key VARCHAR(500) NOT NULL COMMENT '私钥（加密存储）',
    wallet_address VARCHAR(42) NOT NULL UNIQUE COMMENT '钱包地址（从私钥推导）',
    proxy_address VARCHAR(42) NOT NULL COMMENT 'Polymarket 代理钱包地址（从合约获取，必须）',
    api_key VARCHAR(500) NULL COMMENT 'Polymarket API Key（可选，加密存储）',
    api_secret VARCHAR(500) NULL COMMENT 'Polymarket API Secret（可选，加密存储）',
    api_passphrase VARCHAR(500) NULL COMMENT 'Polymarket API Passphrase（可选，加密存储）',
    account_name VARCHAR(100) NULL COMMENT '账户名称',
    is_default BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否默认账户',
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用（用于订单推送等功能的开关）',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
    INDEX idx_wallet_address (wallet_address),
    INDEX idx_is_default (is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钱包账户表';

-- ============================================
-- 2. 创建被跟单者（Leader）表
-- ============================================
CREATE TABLE IF NOT EXISTS copy_trading_leaders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    leader_address VARCHAR(42) NOT NULL UNIQUE COMMENT '被跟单者的钱包地址',
    leader_name VARCHAR(100) NULL COMMENT '被跟单者名称',
    category VARCHAR(20) NULL COMMENT '分类筛选（sports/crypto），null表示不筛选',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
    INDEX idx_leader_address (leader_address),
    INDEX idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='被跟单者表';

-- ============================================
-- 3. 创建跟单模板表
-- ============================================
CREATE TABLE IF NOT EXISTS copy_trading_templates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_name VARCHAR(100) NOT NULL UNIQUE COMMENT '模板名称',
    copy_mode VARCHAR(10) NOT NULL DEFAULT 'RATIO' COMMENT '跟单金额模式（RATIO/FIXED）',
    copy_ratio DECIMAL(10, 2) NOT NULL DEFAULT 1.00 COMMENT '跟单比例（仅在copyMode=RATIO时生效）',
    fixed_amount DECIMAL(20, 8) NULL COMMENT '固定跟单金额（仅在copyMode=FIXED时生效）',
    max_order_size DECIMAL(20, 8) NOT NULL DEFAULT 1000.00000000 COMMENT '单笔订单最大金额（USDC）',
    min_order_size DECIMAL(20, 8) NOT NULL DEFAULT 1.00000000 COMMENT '单笔订单最小金额（USDC）',
    max_daily_loss DECIMAL(20, 8) NOT NULL DEFAULT 10000.00000000 COMMENT '每日最大亏损限制（USDC）',
    max_daily_orders INT NOT NULL DEFAULT 100 COMMENT '每日最大跟单订单数',
    price_tolerance DECIMAL(5, 2) NOT NULL DEFAULT 5.00 COMMENT '价格容忍度（百分比，0-100）',
    delay_seconds INT NOT NULL DEFAULT 0 COMMENT '跟单延迟（秒，默认0立即跟单）',
    poll_interval_seconds INT NOT NULL DEFAULT 5 COMMENT '轮询间隔（秒，仅在WebSocket不可用时使用）',
    use_websocket BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否优先使用WebSocket推送',
    websocket_reconnect_interval INT NOT NULL DEFAULT 5000 COMMENT 'WebSocket重连间隔（毫秒）',
    websocket_max_retries INT NOT NULL DEFAULT 10 COMMENT 'WebSocket最大重试次数',
    support_sell BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否支持跟单卖出',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
    INDEX idx_template_name (template_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跟单模板表';

-- ============================================
-- 4. 创建跟单关系表（钱包-模板关联，多对多关系）
-- ============================================
CREATE TABLE IF NOT EXISTS copy_trading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id BIGINT NOT NULL COMMENT '钱包账户ID',
    template_id BIGINT NOT NULL COMMENT '模板ID',
    leader_id BIGINT NOT NULL COMMENT 'Leader ID',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
    UNIQUE KEY uk_account_template_leader (account_id, template_id, leader_id),
    INDEX idx_account_id (account_id),
    INDEX idx_template_id (template_id),
    INDEX idx_leader_id (leader_id),
    INDEX idx_enabled (enabled),
    FOREIGN KEY (account_id) REFERENCES wallet_accounts(id) ON DELETE CASCADE,
    FOREIGN KEY (template_id) REFERENCES copy_trading_templates(id) ON DELETE RESTRICT,
    FOREIGN KEY (leader_id) REFERENCES copy_trading_leaders(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跟单关系表（钱包-模板关联）';

-- ============================================
-- 5. 创建订单跟踪表
-- ============================================
CREATE TABLE IF NOT EXISTS copy_order_tracking (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    copy_trading_id BIGINT NOT NULL COMMENT '跟单关系ID',
    account_id BIGINT NOT NULL COMMENT '账户ID',
    leader_id BIGINT NOT NULL COMMENT 'Leader ID',
    template_id BIGINT NOT NULL COMMENT '模板ID',
    market_id VARCHAR(100) NOT NULL COMMENT '市场地址',
    side VARCHAR(10) NOT NULL COMMENT '方向：YES/NO',
    outcome_index INT NULL COMMENT '结果索引（0, 1, 2, ...），支持多元市场',
    buy_order_id VARCHAR(100) NOT NULL COMMENT '跟单买入订单ID',
    leader_buy_trade_id VARCHAR(100) NOT NULL COMMENT 'Leader 买入交易ID',
    quantity DECIMAL(20, 8) NOT NULL COMMENT '买入数量',
    price DECIMAL(20, 8) NOT NULL COMMENT '买入价格',
    matched_quantity DECIMAL(20, 8) NOT NULL DEFAULT 0 COMMENT '已匹配卖出数量',
    remaining_quantity DECIMAL(20, 8) NOT NULL COMMENT '剩余未匹配数量',
    status VARCHAR(20) NOT NULL COMMENT '状态：filled, fully_matched, partially_matched',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
    INDEX idx_copy_trading (copy_trading_id),
    INDEX idx_remaining (remaining_quantity, status),
    INDEX idx_market_side (market_id, side),
    INDEX idx_market_outcome (market_id, outcome_index),
    INDEX idx_leader_trade (leader_id, leader_buy_trade_id),
    FOREIGN KEY (copy_trading_id) REFERENCES copy_trading(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单跟踪表';

-- ============================================
-- 6. 创建卖出匹配记录表
-- ============================================
CREATE TABLE IF NOT EXISTS sell_match_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    copy_trading_id BIGINT NOT NULL COMMENT '跟单关系ID',
    sell_order_id VARCHAR(100) NOT NULL COMMENT '跟单卖出订单ID',
    leader_sell_trade_id VARCHAR(100) NOT NULL COMMENT 'Leader 卖出交易ID',
    market_id VARCHAR(100) NOT NULL COMMENT '市场地址',
    side VARCHAR(10) NOT NULL COMMENT '方向：YES/NO',
    outcome_index INT NULL COMMENT '结果索引（0, 1, 2, ...），支持多元市场',
    total_matched_quantity DECIMAL(20, 8) NOT NULL COMMENT '总匹配数量',
    sell_price DECIMAL(20, 8) NOT NULL COMMENT '卖出价格',
    total_realized_pnl DECIMAL(20, 8) NOT NULL COMMENT '总已实现盈亏',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    INDEX idx_copy_trading (copy_trading_id),
    INDEX idx_sell_order (sell_order_id),
    INDEX idx_market_outcome (market_id, outcome_index),
    INDEX idx_leader_trade (leader_sell_trade_id),
    FOREIGN KEY (copy_trading_id) REFERENCES copy_trading(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='卖出匹配记录表';

-- ============================================
-- 7. 创建匹配明细表
-- ============================================
CREATE TABLE IF NOT EXISTS sell_match_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    match_record_id BIGINT NOT NULL COMMENT '关联 sell_match_record.id',
    tracking_id BIGINT NOT NULL COMMENT '关联 copy_order_tracking.id',
    buy_order_id VARCHAR(100) NOT NULL COMMENT '买入订单ID',
    matched_quantity DECIMAL(20, 8) NOT NULL COMMENT '匹配的数量',
    buy_price DECIMAL(20, 8) NOT NULL COMMENT '买入价格',
    sell_price DECIMAL(20, 8) NOT NULL COMMENT '卖出价格',
    realized_pnl DECIMAL(20, 8) NOT NULL COMMENT '盈亏 = (sell_price - buy_price) * matched_quantity',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    INDEX idx_match_record (match_record_id),
    INDEX idx_tracking (tracking_id),
    INDEX idx_buy_order (buy_order_id),
    FOREIGN KEY (match_record_id) REFERENCES sell_match_record(id) ON DELETE CASCADE,
    FOREIGN KEY (tracking_id) REFERENCES copy_order_tracking(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='匹配明细表';

-- ============================================
-- 8. 创建已处理交易表（用于去重）
-- ============================================
CREATE TABLE IF NOT EXISTS processed_trade (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    leader_id BIGINT NOT NULL COMMENT 'Leader ID',
    leader_trade_id VARCHAR(100) NOT NULL COMMENT 'Leader 的交易ID（trade.id，唯一标识）',
    trade_type VARCHAR(10) NOT NULL COMMENT '交易类型：BUY 或 SELL',
    source VARCHAR(20) NOT NULL COMMENT '数据来源：websocket 或 polling',
    status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS' COMMENT '处理状态：SUCCESS（成功）、FAILED（失败）',
    processed_at BIGINT NOT NULL COMMENT '处理时间（毫秒时间戳）',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    UNIQUE KEY uk_leader_trade (leader_id, leader_trade_id),
    INDEX idx_processed_at (processed_at),
    INDEX idx_leader_id (leader_id),
    FOREIGN KEY (leader_id) REFERENCES copy_trading_leaders(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='已处理交易表（用于去重）';

-- ============================================
-- 9. 创建失败交易记录表
-- ============================================
CREATE TABLE IF NOT EXISTS failed_trade (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    leader_id BIGINT NOT NULL COMMENT 'Leader ID',
    leader_trade_id VARCHAR(100) NOT NULL COMMENT 'Leader 的交易ID',
    trade_type VARCHAR(10) NOT NULL COMMENT '交易类型：BUY 或 SELL',
    copy_trading_id BIGINT NOT NULL COMMENT '跟单关系ID',
    account_id BIGINT NOT NULL COMMENT '账户ID',
    market_id VARCHAR(100) NOT NULL COMMENT '市场地址',
    side VARCHAR(10) NOT NULL COMMENT '方向：YES/NO',
    price VARCHAR(50) NOT NULL COMMENT '价格',
    size VARCHAR(50) NOT NULL COMMENT '数量',
    error_message TEXT COMMENT '错误信息',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数',
    failed_at BIGINT NOT NULL COMMENT '失败时间（毫秒时间戳）',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    INDEX idx_leader_trade (leader_id, leader_trade_id),
    INDEX idx_copy_trading (copy_trading_id),
    INDEX idx_failed_at (failed_at),
    FOREIGN KEY (copy_trading_id) REFERENCES copy_trading(id) ON DELETE CASCADE,
    FOREIGN KEY (leader_id) REFERENCES copy_trading_leaders(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='失败交易记录表';

-- ============================================
-- 10. 创建用户表（用于JWT登录鉴权）
-- ============================================
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名（唯一）',
    password VARCHAR(255) NOT NULL COMMENT '密码（BCrypt加密）',
    is_default BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否默认账户（首次创建的用户）',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
    INDEX idx_username (username),
    INDEX idx_is_default (is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表（JWT登录鉴权）';

-- ============================================
-- 11. 创建代理配置表
-- ============================================
CREATE TABLE IF NOT EXISTS proxy_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    type VARCHAR(20) NOT NULL COMMENT '代理类型：HTTP, CLASH, SS',
    enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否启用',
    host VARCHAR(255) NULL COMMENT '代理主机（HTTP代理）',
    port INT NULL COMMENT '代理端口（HTTP代理）',
    username VARCHAR(100) NULL COMMENT '代理用户名（HTTP代理，可选）',
    password VARCHAR(255) NULL COMMENT '代理密码（HTTP代理，可选，BCrypt加密）',
    subscription_url VARCHAR(500) NULL COMMENT '订阅链接（Clash/SS代理）',
    subscription_config TEXT NULL COMMENT '订阅配置内容（Clash/SS代理，JSON格式）',
    last_subscription_update BIGINT NULL COMMENT '最后订阅更新时间（毫秒时间戳）',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
    INDEX idx_type (type),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='代理配置表';
