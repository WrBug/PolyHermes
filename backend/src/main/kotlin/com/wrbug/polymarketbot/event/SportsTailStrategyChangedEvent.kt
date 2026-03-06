package com.wrbug.polymarketbot.event

import org.springframework.context.ApplicationEvent

/**
 * 体育尾盘策略变更事件
 * 当策略创建、删除、成交、卖出时发布此事件
 */
class SportsTailStrategyChangedEvent(source: Any) : ApplicationEvent(source)
