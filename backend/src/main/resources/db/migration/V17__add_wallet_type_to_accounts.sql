-- ============================================
-- 添加 wallet_type 字段到 wallet_accounts 表
-- 用于区分 Magic 和 Safe 两种钱包类型
-- ============================================

-- 使用存储过程检查并添加字段（如果不存在）
DELIMITER $$

CREATE PROCEDURE IF NOT EXISTS add_wallet_type_if_not_exists()
BEGIN
    DECLARE column_exists INT DEFAULT 0;
    
    SELECT COUNT(*) INTO column_exists
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wallet_accounts'
      AND COLUMN_NAME = 'wallet_type';
    
    IF column_exists = 0 THEN
        ALTER TABLE wallet_accounts 
        ADD COLUMN wallet_type VARCHAR(20) NOT NULL DEFAULT 'magic' COMMENT '钱包类型：magic（邮箱/OAuth登录）或 safe（MetaMask浏览器钱包）' AFTER is_enabled;
    END IF;
END$$

DELIMITER ;

-- 执行存储过程
CALL add_wallet_type_if_not_exists();

-- 删除存储过程
DROP PROCEDURE IF EXISTS add_wallet_type_if_not_exists;

-- 为现有账户设置默认walletType为magic（如果值为NULL）
UPDATE wallet_accounts SET wallet_type = 'magic' WHERE wallet_type IS NULL OR wallet_type = '';

