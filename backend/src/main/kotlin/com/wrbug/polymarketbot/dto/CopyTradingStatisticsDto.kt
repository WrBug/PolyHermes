package com.wrbug.polymarketbot.dto

/**
 * 跟单关系统计响应
 */
data class CopyTradingStatisticsResponse(
    val copyTradingId: Long,
    val accountId: Long,
    val accountName: String?,
    val leaderId: Long,
    val leaderName: String?,
    val enabled: Boolean,
    
    // 买入统计
    val totalBuyQuantity: String,
    val totalBuyOrders: Long,
    val totalBuyAmount: String,
    val avgBuyPrice: String,
    
    // 卖出统计
    val totalSellQuantity: String,
    val totalSellOrders: Long,
    val totalSellAmount: String,
    
    // 持仓统计
    val currentPositionQuantity: String,
    val currentPositionValue: String,
    
    // 盈亏统计
    val totalRealizedPnl: String,
    val totalUnrealizedPnl: String,
    val totalPnl: String,
    val totalPnlPercent: String
)

/**
 * 买入订单信息
 */
data class BuyOrderInfo(
    val orderId: String,
    val leaderTradeId: String,
    val marketId: String,
    val marketTitle: String? = null,  // 市场名称
    val marketSlug: String? = null,  // 市场 slug（用于显示）
    val eventSlug: String? = null,  // 跳转用的 slug（从 events[0].slug 获取）
    val marketCategory: String? = null,  // 市场分类（sports, crypto 等）
    val side: String,
    val quantity: String,
    val price: String,
    val amount: String,
    val matchedQuantity: String,
    val remainingQuantity: String,
    val status: String,  // filled, partially_matched, fully_matched
    val createdAt: Long
)

/**
 * 卖出订单信息
 */
data class SellOrderInfo(
    val orderId: String,
    val leaderTradeId: String,
    val marketId: String,
    val marketTitle: String? = null,  // 市场名称
    val marketSlug: String? = null,  // 市场 slug（用于显示）
    val eventSlug: String? = null,  // 跳转用的 slug（从 events[0].slug 获取）
    val marketCategory: String? = null,  // 市场分类（sports, crypto 等）
    val side: String,
    val quantity: String,
    val price: String,
    val amount: String,
    val realizedPnl: String,
    val createdAt: Long
)

/**
 * 匹配订单信息
 */
data class MatchedOrderInfo(
    val sellOrderId: String,
    val buyOrderId: String,
    val marketId: String? = null,  // 市场ID（从买入订单获取）
    val marketTitle: String? = null,  // 市场名称
    val marketSlug: String? = null,  // 市场 slug（用于显示）
    val eventSlug: String? = null,  // 跳转用的 slug（从 events[0].slug 获取）
    val marketCategory: String? = null,  // 市场分类（sports, crypto 等）
    val matchedQuantity: String,
    val buyPrice: String,
    val sellPrice: String,
    val realizedPnl: String,
    val matchedAt: Long
)

/**
 * 订单列表响应
 */
data class OrderListResponse(
    val list: List<Any>,  // BuyOrderInfo, SellOrderInfo 或 MatchedOrderInfo
    val total: Long,
    val page: Int,
    val limit: Int
)

/**
 * 订单跟踪查询请求
 */
data class OrderTrackingRequest(
    val copyTradingId: Long,
    val type: String,  // buy, sell, matched
    val page: Int? = 1,
    val limit: Int? = 20,
    val marketId: String? = null,
    val side: String? = null,
    val status: String? = null,
    val sellOrderId: String? = null,
    val buyOrderId: String? = null
)

/**
 * 按市场分组的订单查询请求
 */
data class MarketGroupedOrdersRequest(
    val copyTradingId: Long,
    val type: String,  // buy, sell, matched
    val page: Int? = 1,
    val limit: Int? = 20
)

/**
 * 单个市场的订单统计信息
 */
data class MarketOrderStats(
    val count: Long,
    val totalAmount: String,  // 总金额
    val totalPnl: String?,  // 总盈亏（买入订单未实现盈亏，此字段为空）
    val fullyMatched: Boolean,  // 是否全部成交
    val fullyMatchedCount: Long,  // 完全成交的订单数
    val partiallyMatchedCount: Long,  // 部分成交的订单数
    val filledCount: Long  // 未成交的订单数
)

/**
 * 单个市场分组的响应数据
 */
data class MarketOrderGroup(
    val marketId: String,
    val marketTitle: String?,
    val marketSlug: String?,  // 显示用的 slug
    val eventSlug: String? = null,  // 跳转用的 slug（从 events[0].slug 获取）
    val marketCategory: String?,
    val stats: MarketOrderStats,
    val orders: List<Any>  // BuyOrderInfo, SellOrderInfo 或 MatchedOrderInfo 的列表
)

/**
 * 按市场分组的订单列表响应
 */
data class MarketGroupedOrdersResponse(
    val list: List<MarketOrderGroup>,
    val total: Long,  // 市场总数
    val page: Int,
    val limit: Int
)

/**
 * 统计查询请求
 */
data class StatisticsDetailRequest(
    val copyTradingId: Long
)

/**
 * 全局统计请求
 */
data class GlobalStatisticsRequest(
    val startTime: Long? = null,
    val endTime: Long? = null
)

/**
 * Leader 统计请求
 */
data class LeaderStatisticsRequest(
    val leaderId: Long,
    val startTime: Long? = null,
    val endTime: Long? = null
)

/**
 * 分类统计请求
 */
data class CategoryStatisticsRequest(
    val category: String,  // sports 或 crypto
    val startTime: Long? = null,
    val endTime: Long? = null
)

/**
 * 统计响应（全局/Leader/分类）
 */
data class StatisticsResponse(
    val totalOrders: Long,
    val totalPnl: String,
    val winRate: String,
    val avgPnl: String,
    val maxProfit: String,
    val maxLoss: String
)

