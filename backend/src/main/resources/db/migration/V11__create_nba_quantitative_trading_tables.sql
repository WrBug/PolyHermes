-- NBA 量化交易系统数据库表

-- 1. NBA 市场表（Polymarket 市场信息）
CREATE TABLE IF NOT EXISTS nba_markets (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    polymarket_market_id VARCHAR(100) UNIQUE NOT NULL COMMENT 'Polymarket 市场 ID',
    condition_id VARCHAR(100) UNIQUE NOT NULL COMMENT 'Condition ID',
    market_slug VARCHAR(255) COMMENT '市场 slug',
    market_question TEXT COMMENT '市场名称/问题',
    market_description TEXT COMMENT '市场描述',
    category VARCHAR(50) DEFAULT 'sports' COMMENT '分类',
    active BOOLEAN DEFAULT true COMMENT '是否活跃',
    closed BOOLEAN DEFAULT false COMMENT '是否已关闭',
    archived BOOLEAN DEFAULT false COMMENT '是否已归档',
    volume VARCHAR(50) COMMENT '交易量',
    liquidity VARCHAR(50) COMMENT '流动性',
    outcomes TEXT COMMENT '结果选项（JSON）',
    end_date VARCHAR(50) COMMENT '结束日期',
    start_date VARCHAR(50) COMMENT '开始日期',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    INDEX idx_condition_id (condition_id),
    INDEX idx_active (active),
    INDEX idx_closed (closed),
    INDEX idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='NBA市场表（Polymarket市场）';

-- 2. NBA 比赛表（NBA 比赛信息）
CREATE TABLE IF NOT EXISTS nba_games (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    nba_game_id VARCHAR(100) UNIQUE COMMENT 'NBA 比赛 ID（来自 NBA API）',
    home_team VARCHAR(100) NOT NULL COMMENT '主队名称',
    away_team VARCHAR(100) NOT NULL COMMENT '客队名称',
    game_date DATE NOT NULL COMMENT '比赛日期',
    game_time BIGINT COMMENT '比赛时间（时间戳，毫秒）',
    game_status VARCHAR(50) DEFAULT 'scheduled' COMMENT '比赛状态：scheduled/active/finished',
    home_score INT DEFAULT 0 COMMENT '主队得分',
    away_score INT DEFAULT 0 COMMENT '客队得分',
    period INT DEFAULT 0 COMMENT '当前节次',
    time_remaining VARCHAR(50) COMMENT '剩余时间',
    polymarket_market_id VARCHAR(100) COMMENT '关联的 Polymarket 市场 ID',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    INDEX idx_game_date (game_date),
    INDEX idx_game_status (game_status),
    INDEX idx_home_team (home_team),
    INDEX idx_away_team (away_team),
    INDEX idx_polymarket_market_id (polymarket_market_id),
    FOREIGN KEY (polymarket_market_id) REFERENCES nba_markets(polymarket_market_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='NBA比赛表';

-- 3. 量化策略配置表
CREATE TABLE IF NOT EXISTS nba_quantitative_strategies (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    strategy_name VARCHAR(100) NOT NULL COMMENT '策略名称',
    strategy_description TEXT COMMENT '策略描述',
    account_id BIGINT NOT NULL COMMENT '关联账户 ID',
    enabled BOOLEAN DEFAULT true COMMENT '是否启用',
    
    -- 比赛筛选参数
    filter_teams TEXT COMMENT '关注的球队列表（JSON）',
    filter_date_from DATE COMMENT '日期范围开始',
    filter_date_to DATE COMMENT '日期范围结束',
    filter_game_importance VARCHAR(50) COMMENT '比赛重要性：all/regular/playoff/key',
    
    -- 触发条件参数
    min_win_probability_diff DECIMAL(5, 4) DEFAULT 0.1000 COMMENT '最小获胜概率差异',
    min_win_probability DECIMAL(5, 4) COMMENT '最小获胜概率',
    max_win_probability DECIMAL(5, 4) COMMENT '最大获胜概率',
    min_trade_value DECIMAL(5, 4) DEFAULT 0.0500 COMMENT '最小交易价值',
    min_remaining_time INT COMMENT '最小剩余时间（分钟）',
    max_remaining_time INT COMMENT '最大剩余时间（分钟）',
    min_score_diff INT COMMENT '最小分差',
    max_score_diff INT COMMENT '最大分差',
    
    -- 买入规则参数
    buy_amount_strategy VARCHAR(20) DEFAULT 'FIXED' COMMENT '买入金额策略：FIXED/RATIO/DYNAMIC',
    fixed_buy_amount DECIMAL(20, 8) COMMENT '固定买入金额（USDC）',
    buy_ratio DECIMAL(5, 4) COMMENT '买入比例（0-1）',
    base_buy_amount DECIMAL(20, 8) COMMENT '基础买入金额（USDC）',
    buy_timing VARCHAR(20) DEFAULT 'IMMEDIATE' COMMENT '买入时机：IMMEDIATE/DELAYED',
    delay_buy_seconds INT DEFAULT 0 COMMENT '延迟买入时间（秒）',
    buy_direction VARCHAR(10) DEFAULT 'AUTO' COMMENT '买入方向：AUTO/YES/NO',
    
    -- 卖出规则参数
    enable_sell BOOLEAN DEFAULT true COMMENT '是否启用卖出',
    take_profit_threshold DECIMAL(5, 4) COMMENT '止盈阈值（0-1）',
    stop_loss_threshold DECIMAL(5, 4) COMMENT '止损阈值（-1-0）',
    probability_reversal_threshold DECIMAL(5, 4) COMMENT '概率反转阈值（0-1）',
    sell_ratio DECIMAL(5, 4) DEFAULT 1.0000 COMMENT '卖出比例（0-1）',
    sell_timing VARCHAR(20) DEFAULT 'IMMEDIATE' COMMENT '卖出时机：IMMEDIATE/DELAYED',
    delay_sell_seconds INT DEFAULT 0 COMMENT '延迟卖出时间（秒）',
    
    -- 价格策略参数
    price_strategy VARCHAR(20) DEFAULT 'MARKET' COMMENT '价格策略：FIXED/MARKET/DYNAMIC',
    fixed_price DECIMAL(5, 4) COMMENT '固定价格（0-1）',
    price_offset DECIMAL(5, 4) DEFAULT 0.0000 COMMENT '价格偏移（-0.1-0.1）',
    
    -- 风险控制参数
    max_position DECIMAL(20, 8) DEFAULT 50.00000000 COMMENT '最大持仓（USDC）',
    min_position DECIMAL(20, 8) DEFAULT 5.00000000 COMMENT '最小持仓（USDC）',
    max_game_position DECIMAL(20, 8) COMMENT '单场比赛最大持仓（USDC）',
    max_daily_loss DECIMAL(20, 8) COMMENT '每日亏损限制（USDC）',
    max_daily_orders INT COMMENT '每日订单限制',
    max_daily_profit DECIMAL(20, 8) COMMENT '每日盈利目标（USDC）',
    price_tolerance DECIMAL(5, 4) DEFAULT 0.0500 COMMENT '价格容忍度（0-1）',
    min_probability_threshold DECIMAL(5, 4) COMMENT '最小概率阈值（0.5-1.0）',
    max_probability_threshold DECIMAL(5, 4) COMMENT '最大概率阈值（0.0-0.5）',
    
    -- 算法权重参数（高级）
    base_strength_weight DECIMAL(5, 4) DEFAULT 0.3000 COMMENT '基础实力权重',
    recent_form_weight DECIMAL(5, 4) DEFAULT 0.2500 COMMENT '近期状态权重',
    lineup_integrity_weight DECIMAL(5, 4) DEFAULT 0.2000 COMMENT '阵容完整度权重',
    star_status_weight DECIMAL(5, 4) DEFAULT 0.1500 COMMENT '球星状态权重',
    environment_weight DECIMAL(5, 4) DEFAULT 0.1000 COMMENT '环境因素权重',
    matchup_advantage_weight DECIMAL(5, 4) DEFAULT 0.2000 COMMENT '对位优势权重',
    score_diff_weight DECIMAL(5, 4) DEFAULT 0.3000 COMMENT '分差调整权重',
    momentum_weight DECIMAL(5, 4) DEFAULT 0.2000 COMMENT '势头调整权重',
    
    -- 系统配置参数
    data_update_frequency INT DEFAULT 30 COMMENT '数据更新频率（秒）',
    analysis_frequency INT DEFAULT 30 COMMENT '分析频率（秒）',
    push_failed_orders BOOLEAN DEFAULT false COMMENT '是否推送失败订单',
    push_frequency VARCHAR(20) DEFAULT 'REALTIME' COMMENT '推送频率：REALTIME/BATCH',
    batch_push_interval INT DEFAULT 1 COMMENT '批量推送间隔（秒）',
    
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    INDEX idx_account_id (account_id),
    INDEX idx_enabled (enabled),
    INDEX idx_strategy_name (strategy_name),
    FOREIGN KEY (account_id) REFERENCES wallet_accounts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='NBA量化策略配置表';

-- 4. 交易信号表
CREATE TABLE IF NOT EXISTS nba_trading_signals (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    strategy_id BIGINT NOT NULL COMMENT '策略 ID',
    game_id BIGINT COMMENT '比赛 ID',
    market_id BIGINT COMMENT '市场 ID',
    signal_type VARCHAR(10) NOT NULL COMMENT '信号类型：BUY/SELL',
    direction VARCHAR(10) NOT NULL COMMENT '方向：YES/NO',
    price DECIMAL(5, 4) NOT NULL COMMENT '价格（0-1）',
    quantity DECIMAL(20, 8) NOT NULL COMMENT '数量',
    total_amount DECIMAL(20, 8) NOT NULL COMMENT '总金额（USDC）',
    reason TEXT COMMENT '触发原因',
    win_probability DECIMAL(5, 4) COMMENT '获胜概率',
    trade_value DECIMAL(5, 4) COMMENT '交易价值',
    signal_status VARCHAR(20) DEFAULT 'GENERATED' COMMENT '信号状态：GENERATED/EXECUTING/SUCCESS/FAILED',
    execution_result TEXT COMMENT '执行结果',
    error_message TEXT COMMENT '错误信息',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    INDEX idx_strategy_id (strategy_id),
    INDEX idx_game_id (game_id),
    INDEX idx_market_id (market_id),
    INDEX idx_signal_type (signal_type),
    INDEX idx_signal_status (signal_status),
    INDEX idx_created_at (created_at),
    FOREIGN KEY (strategy_id) REFERENCES nba_quantitative_strategies(id) ON DELETE CASCADE,
    FOREIGN KEY (game_id) REFERENCES nba_games(id) ON DELETE SET NULL,
    FOREIGN KEY (market_id) REFERENCES nba_markets(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='NBA交易信号表';

-- 5. 策略执行统计表
CREATE TABLE IF NOT EXISTS nba_strategy_statistics (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    strategy_id BIGINT NOT NULL COMMENT '策略 ID',
    stat_date DATE NOT NULL COMMENT '统计日期',
    total_signals INT DEFAULT 0 COMMENT '总信号数',
    buy_signals INT DEFAULT 0 COMMENT '买入信号数',
    sell_signals INT DEFAULT 0 COMMENT '卖出信号数',
    success_signals INT DEFAULT 0 COMMENT '成功信号数',
    failed_signals INT DEFAULT 0 COMMENT '失败信号数',
    total_profit DECIMAL(20, 8) DEFAULT 0.00000000 COMMENT '总盈亏（USDC）',
    total_volume DECIMAL(20, 8) DEFAULT 0.00000000 COMMENT '总交易量（USDC）',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uk_strategy_date (strategy_id, stat_date),
    INDEX idx_strategy_id (strategy_id),
    INDEX idx_stat_date (stat_date),
    FOREIGN KEY (strategy_id) REFERENCES nba_quantitative_strategies(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='NBA策略执行统计表';

