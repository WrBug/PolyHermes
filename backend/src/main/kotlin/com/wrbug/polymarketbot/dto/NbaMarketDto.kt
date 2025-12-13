package com.wrbug.polymarketbot.dto

/**
 * NBA 市场 DTO
 */
data class NbaMarketDto(
    val id: String? = null,
    val question: String? = null,
    val conditionId: String? = null,
    val slug: String? = null,
    val description: String? = null,
    val category: String? = null,
    val active: Boolean? = null,
    val closed: Boolean? = null,
    val archived: Boolean? = null,
    val volume: String? = null,
    val liquidity: String? = null,
    val endDate: String? = null,
    val startDate: String? = null,
    val outcomes: String? = null,
    val outcomePrices: String? = null,
    val volumeNum: Double? = null,
    val liquidityNum: Double? = null,
    val lastTradePrice: Double? = null,
    val bestBid: Double? = null,
    val bestAsk: Double? = null
)

/**
 * NBA 市场列表响应
 */
data class NbaMarketListResponse(
    val list: List<NbaMarketDto>,
    val total: Long
)

/**
 * NBA 市场列表请求
 */
data class NbaMarketListRequest(
    val active: Boolean? = true,
    val closed: Boolean? = false,
    val archived: Boolean? = false
)

