-- ============================================
-- V20: 添加关键字过滤字段
-- 在 copy_trading 表中添加关键字过滤配置
-- 支持白名单和黑名单两种模式
-- ============================================

-- 添加关键字过滤模式字段
-- DISABLED: 不启用关键字过滤（默认）
-- WHITELIST: 白名单模式，只跟单包含关键字的市场
-- BLACKLIST: 黑名单模式，不跟单包含关键字的市场
ALTER TABLE copy_trading
ADD COLUMN keyword_filter_mode VARCHAR(20) NOT NULL DEFAULT 'DISABLED' COMMENT '关键字过滤模式（DISABLED/WHITELIST/BLACKLIST）' AFTER max_position_count;

-- 添加关键字列表字段（JSON 数组）
-- 当 keyword_filter_mode 为 DISABLED 时，此字段为 NULL
-- 当 keyword_filter_mode 为 WHITELIST 或 BLACKLIST 时，此字段存储关键字数组，例如：["NBA", "足球", "NBA总决赛"]
ALTER TABLE copy_trading
ADD COLUMN keywords JSON NULL COMMENT '关键字列表（JSON数组），仅在WHITELIST或BLACKLIST模式下使用' AFTER keyword_filter_mode;

