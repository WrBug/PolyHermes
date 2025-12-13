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
     * 获取体育元数据信息
     * 文档: https://docs.polymarket.com/api-reference/sports/get-sports-metadata-information
     * @return 体育元数据数组
     */
    @GET("/sports")
    suspend fun getSports(): Response<List<SportsMetadataResponse>>

    /**
     * 根据条件获取市场信息
     * 文档: https://docs.polymarket.com/api-reference/markets/list-markets
     * @param conditionIds condition ID 数组（16 进制字符串，如 "0x..."）
     * @param includeTag 是否包含标签信息
     * @param tags 标签 ID 数组，用于过滤市场（如 NBA 的 tag ID）
     * @param active 是否只返回活跃的市场
     * @param closed 是否包含已关闭的市场
     * @param archived 是否包含已归档的市场
     * @param limit 返回的市场数量限制
     * @param startDateMin 最小开始日期（ISO 8601 格式，UTC 时区，如 "2025-12-01T00:00:00Z"）
     * @param sportsMarketTypes 体育市场类型数组（如 ["moneyline"] 用于筛选 moneyline 类型）
     * @return 市场信息数组
     */
    @GET("/markets")
    suspend fun listMarkets(
        @Query("condition_ids") conditionIds: List<String>? = null,
        @Query("include_tag") includeTag: Boolean? = null,
        @Query("tags") tags: List<String>? = null,
        @Query("active") active: Boolean? = null,
        @Query("closed") closed: Boolean? = null,
        @Query("archived") archived: Boolean? = null,
        @Query("limit") limit: Int? = null,
        @Query("start_date_min") startDateMin: String? = null,
        @Query("sports_market_types") sportsMarketTypes: List<String>? = null,
    ): Response<List<MarketResponse>>
}

/**
 * 体育元数据响应
 * 文档: https://docs.polymarket.com/api-reference/sports/get-sports-metadata-information
 */
data class SportsMetadataResponse(
    val sport: String? = null,  // 体育标识符或缩写（如 "NBA"）
    val image: String? = null,  // 体育 logo 或图片 URL
    val resolution: String? = null,  // 官方决议源 URL
    val ordering: String? = null,  // 显示顺序（通常是 "home" 或 "away"）
    val tags: String? = null,  // 逗号分隔的标签 ID 列表
    val series: String? = null  // 系列标识符
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
    val tags: List<MarketTag>? = null,  // 市场标签列表
    val sportsMarketType: String? = null,  // 市场类型：moneyline, spread 等
    val gameStartTime: String? = null,  // 比赛开始时间（格式：2025-12-13 00:00:00+00）
    val createdAt: String? = null,  // 市场创建时间（ISO 8601 格式）
    val resolutionSource: String? = null  // 决议源 URL（如 "https://www.nba.com/"）
)

/**
 * 市场标签
 */
data class MarketTag(
    val id: String? = null,
    val label: String? = null,
    val slug: String? = null
)

