-- 移除 NBA 市场相关的外键约束
-- 由于不再在数据库中存储市场信息，需要移除这些外键约束

-- 1. 移除 nba_games 表的外键约束
SET @fk_name = (SELECT CONSTRAINT_NAME 
                FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE 
                WHERE TABLE_SCHEMA = DATABASE() 
                  AND TABLE_NAME = 'nba_games' 
                  AND COLUMN_NAME = 'polymarket_market_id' 
                  AND REFERENCED_TABLE_NAME = 'nba_markets'
                LIMIT 1);

SET @sql = IF(@fk_name IS NOT NULL, 
              CONCAT('ALTER TABLE nba_games DROP FOREIGN KEY ', @fk_name), 
              'SELECT "Foreign key constraint not found"');

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 移除 nba_trading_signals 表的外键约束（如果存在）
SET @fk_name = (SELECT CONSTRAINT_NAME 
                FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE 
                WHERE TABLE_SCHEMA = DATABASE() 
                  AND TABLE_NAME = 'nba_trading_signals' 
                  AND COLUMN_NAME = 'market_id' 
                  AND REFERENCED_TABLE_NAME = 'nba_markets'
                LIMIT 1);

SET @sql = IF(@fk_name IS NOT NULL, 
              CONCAT('ALTER TABLE nba_trading_signals DROP FOREIGN KEY ', @fk_name), 
              'SELECT "Foreign key constraint not found"');

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

