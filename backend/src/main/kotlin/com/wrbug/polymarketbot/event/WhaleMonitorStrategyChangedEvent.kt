package com.wrbug.polymarketbot.event

import org.springframework.context.ApplicationEvent

/**
 * 大单监听策略创建/更新/删除/启用状态变更后发布，通知 WS 服务重载监听配置
 */
class WhaleMonitorStrategyChangedEvent(source: Any) : ApplicationEvent(source)
