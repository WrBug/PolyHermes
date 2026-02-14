-- ============================================
-- V36: 尾盘策略触发记录 - TG 通知已发标记（与跟单轮询发 TG 一致）
-- ============================================

ALTER TABLE crypto_tail_strategy_trigger
    ADD COLUMN notification_sent TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已发送 TG 通知: 0=未发送, 1=已发送';

CREATE INDEX idx_trigger_notification ON crypto_tail_strategy_trigger (status, notification_sent);
