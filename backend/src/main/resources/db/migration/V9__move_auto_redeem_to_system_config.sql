-- ============================================
-- V9: 将自动赎回从跟单配置迁移到系统配置
-- 1. 从 copy_trading 表中读取 auto_redeem 值，迁移到 system_config
-- 2. 移除 copy_trading.auto_redeem 字段
-- ============================================

-- 1. 初始化 auto_redeem 配置项到 system_config（如果不存在）
-- 默认值为 true（因为之前 copy_trading.auto_redeem 默认是 true）
INSERT IGNORE INTO system_config (config_key, config_value, description, created_at, updated_at)
VALUES 
    ('auto_redeem', 'true', '自动赎回（系统级别配置，默认开启）', UNIX_TIMESTAMP() * 1000, UNIX_TIMESTAMP() * 1000);

-- 2. 如果 copy_trading 表中存在数据，检查是否有 auto_redeem = false 的配置
-- 如果所有配置都是 true，则保持 system_config 中的值为 true
-- 如果存在 false，则设置为 false（保守策略：只要有一个配置是 false，就设置为 false）
UPDATE system_config
SET config_value = CASE 
    WHEN EXISTS (
        SELECT 1 FROM copy_trading 
        WHERE auto_redeem = FALSE
    ) THEN 'false'
    ELSE 'true'
END,
updated_at = UNIX_TIMESTAMP() * 1000
WHERE config_key = 'auto_redeem';

-- 3. 移除 copy_trading 表中的 auto_redeem 字段
ALTER TABLE copy_trading 
DROP COLUMN auto_redeem;

