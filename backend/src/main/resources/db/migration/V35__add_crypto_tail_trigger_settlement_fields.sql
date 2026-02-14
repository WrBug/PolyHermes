-- ============================================
-- V35: 尾盘策略触发记录 - 结算与收益字段
-- 用于轮询服务：扫描 success 但未结算的订单，查链上结算结果并回写收益
-- ============================================

ALTER TABLE crypto_tail_strategy_trigger
    ADD COLUMN condition_id VARCHAR(66) DEFAULT NULL COMMENT '市场 conditionId（用于查链上结算）' AFTER order_id,
    ADD COLUMN resolved TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已结算: 0=未结算, 1=已结算',
    ADD COLUMN winner_outcome_index INT DEFAULT NULL COMMENT '市场赢家 outcome 索引（结算后写入）',
    ADD COLUMN realized_pnl DECIMAL(20, 8) DEFAULT NULL COMMENT '已实现盈亏 USDC（赢为正，输为负）',
    ADD COLUMN settled_at BIGINT DEFAULT NULL COMMENT '结算时间（毫秒时间戳）';

CREATE INDEX idx_trigger_settlement ON crypto_tail_strategy_trigger (status, resolved);
