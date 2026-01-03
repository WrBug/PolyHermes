-- 添加 price_updated 字段到 sell_match_record 表
-- 用于标记卖出价格是否已从订单详情中更新
ALTER TABLE sell_match_record
ADD COLUMN price_updated BOOLEAN DEFAULT FALSE COMMENT '价格是否已更新（从订单详情获取实际成交价）';

-- 为已存在的记录设置默认值
UPDATE sell_match_record SET price_updated = TRUE WHERE price_updated IS NULL;

