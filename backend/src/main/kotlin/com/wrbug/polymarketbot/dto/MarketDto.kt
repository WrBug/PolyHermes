package com.wrbug.polymarketbot.dto

/**
 * 市场 DTO（用于返回给前端）
 */
data class MarketDto(
    val id: String,
    val question: String,
    val slug: String,
    val category: String,
    val active: Boolean,
    val volume: String?,
    val liquidity: String?,
    val endDate: Long?,  // 时间戳（毫秒）
    val createdAt: Long?,  // 时间戳（毫秒）
    val updatedAt: Long?,  // 时间戳（毫秒）
    val outcomes: List<OutcomeDto>?,
    val conditionId: String?,
    val description: String?,
    val image: String?,
    val icon: String?,
    val closed: Boolean?,
    val archived: Boolean?,
    val volumeNum: Double?,
    val liquidityNum: Double?,
    val bestBid: Double?,
    val bestAsk: Double?,
    val lastTradePrice: Double?,
    val events: List<MarketDto>? = null  // 事件列表（从 events[0] 获取 slug）
)

/**
 * 结果 DTO
 */
data class OutcomeDto(
    val name: String,  // 结果名称，如 "Yes" 或 "No"
    val price: String  // 价格
)

/**
 * 事件 DTO（用于其他 API 调用）
 */
data class EventListDto(
    val id: String,
    val title: String,
    val category: String,
    val active: Boolean,
    val markets: List<MarketDto>?,
    val createdAt: Long?  // 时间戳（毫秒）
)

/**
 * 系列 DTO
 */
data class SeriesDto(
    val id: String,
    val title: String,
    val category: String,
    val events: List<EventListDto>?,
    val createdAt: Long?  // 时间戳（毫秒）
)

/**
 * 评论 DTO
 */
data class CommentDto(
    val id: String,
    val market: String,
    val content: String,
    val parent: String?,
    val createdAt: Long,  // 时间戳（毫秒）
    val user: String?
)
