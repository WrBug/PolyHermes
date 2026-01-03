-- 添加 notification_sent 字段到 copy_order_tracking 表
-- 用于标记买入订单是否已发送通知
ALTER TABLE copy_order_tracking
ADD COLUMN notification_sent BOOLEAN DEFAULT FALSE COMMENT '是否已发送通知（从订单详情获取实际数据后发送）';

-- 为已存在的记录设置默认值（已存在的订单视为已发送通知）
UPDATE copy_order_tracking SET notification_sent = TRUE WHERE notification_sent IS NULL;

