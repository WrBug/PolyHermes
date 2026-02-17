-- 尾盘策略价差字段重构：支持最小价差/最大价差方向，使用枚举数值存储
-- 1. 重命名 min_spread_mode -> spread_mode，并转换为 TINYINT (0=NONE, 1=FIXED, 2=AUTO)
-- 2. 重命名 min_spread_value -> spread_value
-- 3. 新增 spread_direction 字段，使用 TINYINT (0=MIN, 1=MAX)

-- 步骤1: 重命名并迁移 spread_mode 数据
ALTER TABLE crypto_tail_strategy
    ADD COLUMN spread_mode_new TINYINT NOT NULL DEFAULT 0 COMMENT '价差模式: 0=NONE, 1=FIXED, 2=AUTO';

UPDATE crypto_tail_strategy
SET spread_mode_new = CASE
    WHEN min_spread_mode = 'NONE' THEN 0
    WHEN min_spread_mode = 'FIXED' THEN 1
    WHEN min_spread_mode = 'AUTO' THEN 2
    ELSE 0
END;

ALTER TABLE crypto_tail_strategy
    DROP COLUMN min_spread_mode,
    CHANGE COLUMN spread_mode_new spread_mode TINYINT NOT NULL DEFAULT 0 COMMENT '价差模式: 0=NONE, 1=FIXED, 2=AUTO';

-- 步骤2: 重命名 spread_value
ALTER TABLE crypto_tail_strategy
    CHANGE COLUMN min_spread_value spread_value DECIMAL(20, 8) NULL COMMENT '价差数值（FIXED 时必填；AUTO 时可存计算值）';

-- 步骤3: 新增 spread_direction 字段
ALTER TABLE crypto_tail_strategy
    ADD COLUMN spread_direction TINYINT NOT NULL DEFAULT 0 COMMENT '价差方向: 0=MIN（价差>=配置值触发）, 1=MAX（价差<=配置值触发）';
