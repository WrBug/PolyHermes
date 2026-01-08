-- ============================================
-- V21: 添加 event_slug 字段到 markets 表
-- 用于存储跳转用的 slug（从 events[0].slug 获取）
-- ============================================

ALTER TABLE markets
ADD COLUMN event_slug VARCHAR(200) NULL COMMENT '跳转用的 slug（从 events[0].slug 获取，用于构建 URL）' AFTER slug;

