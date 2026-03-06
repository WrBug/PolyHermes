-- Flyway migration V41
-- Create sports_tail_strategy table
CREATE TABLE `sports_tail_strategy` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `account_id` BIGINT NOT NULL COMMENT '账户ID',
    `condition_id` VARCHAR(100) NOT NULL COMMENT '市场 conditionId',
    `market_title` VARCHAR(500) COMMENT '市场标题',
    `event_slug` VARCHAR(255) COMMENT '事件slug',
    `yes_token_id` VARCHAR(100) COMMENT 'YES Token ID',
    `no_token_id` VARCHAR(100) COMMENT 'NO Token ID',
    `trigger_price` DECIMAL(20, 8) NOT NULL COMMENT '触发价格',
    `amount_mode` VARCHAR(10) NOT NULL COMMENT '金额模式: FIXED/RATIO',
    `amount_value` DECIMAL(20, 8) NOT NULL COMMENT '金额值',
    `take_profit_price` DECIMAL(20, 8) COMMENT '止盈价格',
    `stop_loss_price` DECIMAL(20, 8) COMMENT '止损价格',
    `filled` BOOLEAN NOT NULL DEFAULT false COMMENT '是否已成交',
    `filled_price` DECIMAL(20, 8) COMMENT '成交价格',
    `filled_outcome_index` INT COMMENT '成交方向索引 0=YES, 1=NO',
    `filled_outcome_name` VARCHAR(50) COMMENT '成交方向名称',
    `filled_amount` DECIMAL(20, 8) COMMENT '成交金额',
    `filled_shares` DECIMAL(20, 8) COMMENT '成交份额',
    `filled_at` BIGINT COMMENT '成交时间',
    `sold` BOOLEAN NOT NULL DEFAULT false COMMENT '是否已卖出',
    `sell_price` DECIMAL(20, 8) COMMENT '卖出价格',
    `sell_type` VARCHAR(20) COMMENT '卖出类型',
    `sell_amount` DECIMAL(20, 8) COMMENT '卖出金额',
    `realized_pnl` DECIMAL(20, 8) COMMENT '已实现盈亏',
    `sold_at` BIGINT COMMENT '卖出时间',
    `created_at` BIGINT NOT NULL COMMENT '创建时间',
    `updated_at` BIGINT NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`)
);

-- Create sports_tail_strategy_trigger table
CREATE TABLE `sports_tail_strategy_trigger` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `strategy_id` BIGINT NOT NULL COMMENT '策略ID',
    `account_id` BIGINT NOT NULL COMMENT '账户ID',
    `condition_id` VARCHAR(100) NOT NULL COMMENT '市场 conditionId',
    `market_title` VARCHAR(500) COMMENT '市场标题',
    `buy_price` DECIMAL(20, 8) NOT NULL COMMENT '买入价格',
    `outcome_index` INT NOT NULL COMMENT '买入方向索引 0=YES, 1=NO',
    `outcome_name` VARCHAR(50) COMMENT '买入方向名称',
    `buy_amount` DECIMAL(20, 8) NOT NULL COMMENT '买入金额',
    `buy_shares` DECIMAL(20, 8) COMMENT '买入份额',
    `buy_order_id` VARCHAR(100) COMMENT '买入订单ID',
    `buy_status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '买入状态',
    `buy_fail_reason` VARCHAR(500) COMMENT '买入失败原因',
    `sell_price` DECIMAL(20, 8) COMMENT '卖出价格',
    `sell_type` VARCHAR(20) COMMENT '卖出类型',
    `sell_amount` DECIMAL(20, 8) COMMENT '卖出金额',
    `sell_order_id` VARCHAR(100) COMMENT '卖出订单ID',
    `sell_status` VARCHAR(20) COMMENT '卖出状态',
    `sell_fail_reason` VARCHAR(500) COMMENT '卖出失败原因',
    `realized_pnl` DECIMAL(20, 8) COMMENT '已实现盈亏',
    `triggered_at` BIGINT NOT NULL COMMENT '触发时间',
    `sold_at` BIGINT COMMENT '卖出时间',
    `created_at` BIGINT NOT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`)
);

-- Create indexes
CREATE INDEX idx_sports_tail_strategy_account_id ON sports_tail_strategy (account_id);
CREATE INDEX idx_sports_tail_strategy_condition_id ON sports_tail_strategy (condition_id);
CREATE INDEX idx_sports_tail_trigger_account_id ON sports_tail_strategy_trigger (account_id);
CREATE INDEX idx_sports_tail_trigger_strategy_id ON sports_tail_strategy_trigger (strategy_id);
CREATE INDEX idx_sports_tail_trigger_triggered_at ON sports_tail_strategy_trigger (triggered_at);
