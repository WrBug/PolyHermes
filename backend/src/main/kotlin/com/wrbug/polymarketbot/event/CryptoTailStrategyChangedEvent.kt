package com.wrbug.polymarketbot.event

import org.springframework.context.ApplicationEvent

/**
 * 尾盘策略创建/更新/启用状态变更后发布，用于立即触发一轮执行检查。
 */
class CryptoTailStrategyChangedEvent(source: Any) : ApplicationEvent(source)
