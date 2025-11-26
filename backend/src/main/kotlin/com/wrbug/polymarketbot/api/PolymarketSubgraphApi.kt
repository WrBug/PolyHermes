package com.wrbug.polymarketbot.api

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Polymarket Data API 接口定义
 * 用于查询仓位信息
 * Base URL: https://data-api.polymarket.com
 */
interface PolymarketDataApi {
    
    /**
     * 获取用户当前仓位
     * 文档: https://docs.polymarket.com/api-reference/core/get-current-positions-for-a-user
     */
    @GET("/positions")
    suspend fun getPositions(
        @Query("user") user: String,
        @Query("market") market: String? = null,
        @Query("eventId") eventId: String? = null,
        @Query("sizeThreshold") sizeThreshold: Double? = null,
        @Query("redeemable") redeemable: Boolean? = null,
        @Query("mergeable") mergeable: Boolean? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Query("sortBy") sortBy: String? = null,
        @Query("sortDirection") sortDirection: String? = null,
        @Query("title") title: String? = null
    ): Response<List<PositionResponse>>
    
    /**
     * 获取用户仓位总价值
     * 文档: https://docs.polymarket.com/api-reference/core/get-total-value-of-a-users-positions
     */
    @GET("/value")
    suspend fun getTotalValue(
        @Query("user") user: String,
        @Query("market") market: List<String>? = null
    ): Response<List<ValueResponse>>
}

/**
 * 仓位响应（根据 Polymarket Data API 文档）
 */
data class PositionResponse(
    val proxyWallet: String,
    val asset: String? = null,
    val conditionId: String? = null,
    val size: Double? = null,
    val avgPrice: Double? = null,
    val initialValue: Double? = null,
    val currentValue: Double? = null,
    val cashPnl: Double? = null,
    val percentPnl: Double? = null,
    val totalBought: Double? = null,
    val realizedPnl: Double? = null,
    val percentRealizedPnl: Double? = null,
    val curPrice: Double? = null,
    val redeemable: Boolean? = null,
    val mergeable: Boolean? = null,
    val title: String? = null,
    val slug: String? = null,
    val icon: String? = null,
    val eventSlug: String? = null,
    val outcome: String? = null,
    val outcomeIndex: Int? = null,
    val oppositeOutcome: String? = null,
    val oppositeAsset: String? = null,
    val endDate: String? = null,
    val negativeRisk: Boolean? = null
)

/**
 * 仓位价值响应（根据 Polymarket Data API 文档）
 */
data class ValueResponse(
    val user: String,
    val value: Double
)


