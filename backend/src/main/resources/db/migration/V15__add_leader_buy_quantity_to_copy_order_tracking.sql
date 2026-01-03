-- 添加 Leader 买入数量字段，用于固定金额模式计算卖出比例
ALTER TABLE copy_order_tracking
ADD COLUMN leader_buy_quantity DECIMAL(20, 8) DEFAULT NULL COMMENT 'Leader 买入数量（用于固定金额模式计算卖出比例）';

-- 对于已有数据，如果无法从 API 查询，设置为 NULL（不影响现有功能）
-- 新创建的记录会在创建时自动填充此字段

