-- ============================================
-- V33: 唯一约束从 wallet_address 改为 proxy_address
-- 允许同一 EOA 以不同代理类型（Magic/Safe）各导入一个账户，按代理地址去重
-- ============================================

-- 将已存在账户的 wallet_type 统一为 safe（历史数据兼容）
UPDATE wallet_accounts SET wallet_type = 'safe';

-- 删除 wallet_address 上的唯一约束（通过 KEY_COLUMN_USAGE 定位到该列的约束名）
SET @uk_name = (SELECT kcu.CONSTRAINT_NAME
                FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
                JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
                  ON kcu.TABLE_SCHEMA = tc.TABLE_SCHEMA AND kcu.TABLE_NAME = tc.TABLE_NAME AND kcu.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
                WHERE kcu.TABLE_SCHEMA = DATABASE()
                AND kcu.TABLE_NAME = 'wallet_accounts'
                AND tc.CONSTRAINT_TYPE = 'UNIQUE'
                AND kcu.COLUMN_NAME = 'wallet_address'
                LIMIT 1);
SET @sql = IF(@uk_name IS NOT NULL,
              CONCAT('ALTER TABLE wallet_accounts DROP INDEX ', @uk_name),
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 为 proxy_address 添加唯一约束（若已存在则跳过）
SET @uk_exists = (SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                  WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'wallet_accounts'
                  AND CONSTRAINT_TYPE = 'UNIQUE'
                  AND CONSTRAINT_NAME = 'uk_wallet_accounts_proxy_address'
                  LIMIT 1);
SET @sql2 = IF(@uk_exists IS NULL,
               'ALTER TABLE wallet_accounts ADD UNIQUE KEY uk_wallet_accounts_proxy_address (proxy_address)',
               'SELECT 1');
PREPARE stmt2 FROM @sql2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
