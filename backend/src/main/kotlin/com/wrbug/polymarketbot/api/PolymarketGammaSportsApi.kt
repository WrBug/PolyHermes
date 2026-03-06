package com.wrbug.polymarketbot.api

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Polymarket Gamma API 体育市场接口
 * Base URL: https://gamma-api.polymarket.com
 */
interface PolymarketGammaSportsApi {

    /**
     * 获取体育类别列表
     * GET /sports
     */
    @GET("/sports")
    suspend fun getSports(): Response<List<SportsCategoryResponse>>

    /**
     * 按条件搜索市场
     * GET /markets
     * @param tagId 标签ID（体育类别）
     * @param active 是否活跃
     * @param closed 是否已关闭
     * @param limit 返回数量
     * @param order 排序字段
     * @param ascending 是否升序
     * @param slug 搜索关键词
     */
    @GET("/markets")
    suspend fun searchMarkets(
        @Query("tag_id") tagId: Long? = null,
        @Query("active") active: Boolean? = null,
        @Query("closed") closed: Boolean? = null,
        @Query("limit") limit: Int? = null,
        @Query("order") order: String? = null,
        @Query("ascending") ascending: Boolean? = null,
        @Query("slug") slug: String? = null,
        @Query("condition_ids") conditionIds: String? = null
    ): Response<List<SportsMarketResponse>>
}

/**
 * 体育类别响应
 */
data class SportsCategoryResponse(
    val sport: String? = null,
    val image: String? = null,
    val tags: String? = null
)

/**
 * 体育市场响应
 */
data class SportsMarketResponse(
    val id: String? = null,
    val question: String? = null,
    val conditionId: String? = null,
    val slug: String? = null,
    val outcomes: String? = null,
    val outcomePrices: String? = null,
    val endDate: String? = null,
    val startDate: String? = null,
    val bestBid: Double? = null,
    val bestAsk: Double? = null,
    val clobTokenIds: String? = null,
    val liquidity: String? = null,
    val liquidityNum: Double? = null,
    val volume: String? = null,
    val volumeNum: Double? = null,
    val active: Boolean? = null,
    val closed: Boolean? = null,
    val events: List<SportsEventResponse>? = null
)

/**
 * 体育事件响应
 */
data class SportsEventResponse(
    val id: String? = null,
    val slug: String? = null,
    val title: String? = null,
    val ticker: String? = null
)
