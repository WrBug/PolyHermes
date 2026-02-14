-- 尾盘策略最小价差：NONE=不校验, FIXED=固定值, AUTO=历史计算
ALTER TABLE crypto_tail_strategy
    ADD COLUMN min_spread_mode VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT '最小价差模式: NONE, FIXED, AUTO',
    ADD COLUMN min_spread_value DECIMAL(20, 8) NULL COMMENT '最小价差数值（FIXED 时必填；AUTO 时可存计算值）';
