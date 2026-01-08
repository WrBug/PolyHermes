package com.wrbug.polymarketbot.api

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Polymarket Gamma API 接口定义
 * 用于查询市场信息
 * Base URL: https://gamma-api.polymarket.com
 * 文档: https://docs.polymarket.com/api-reference/markets/list-markets
 */
interface PolymarketGammaApi {

    /**
     * 根据 condition ID 列表获取市场信息
     * 文档: https://docs.polymarket.com/api-reference/markets/list-markets
     * @param conditionIds condition ID 数组（16 进制字符串，如 "0x..."）
     * @param clobTokenIds CLOB token ID 数组（用于通过 tokenId 查询市场）
     * @param includeTag 是否包含标签信息
     * @return 市场信息数组
     */
    @GET("/markets")
    suspend fun listMarkets(
        @Query("condition_ids") conditionIds: List<String>? = null,
        @Query("clob_token_ids") clobTokenIds: List<String>? = null,
        @Query("include_tag") includeTag: Boolean? = null
    ): Response<List<MarketResponse>>
}

/**
 * 事件响应（从 MarketResponse.events 解析）
 */
data class EventResponse(
    val id: String? = null,
    val ticker: String? = null,
    val slug: String,
    val title: String,
    val category: String? = null,
    val active: Boolean? = null,
    val closed: Boolean? = null,
    val archived: Boolean? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val createdAt: String? = null
)

/**
 * 市场响应（根据 Gamma API 文档）
 */
data class MarketResponse(
    val id: String? = null,
    val question: String? = null,  // 市场名称
    val conditionId: String? = null,
    val slug: String? = null,
    val icon: String? = null,
    val image: String? = null,
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
    val bestAsk: Double? = null,
    val events: List<EventResponse>? = null,  // 事件列表（从 events[0] 获取 slug）
    // 以下字段可能存在于响应中，但不在标准文档中
    val clobTokenIds: String? = null,  // CLOB token IDs（可能是 JSON 字符串或数组）
    val clob_token_ids: String? = null  // 下划线格式（兼容不同 API 版本）
)

