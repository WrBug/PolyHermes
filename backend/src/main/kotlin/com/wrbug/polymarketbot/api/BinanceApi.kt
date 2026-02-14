package com.wrbug.polymarketbot.api

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 币安现货公开 API（K 线等）
 * Base URL: https://api.binance.com
 * 文档: https://developers.binance.com/docs/binance-spot-api-docs/rest-api
 */
interface BinanceApi {

    /**
     * K 线数据
     * 返回每根 K 线: [openTime, open, high, low, close, volume, closeTime, ...]
     */
    @GET("/api/v3/klines")
    fun getKlines(
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("limit") limit: Int = 30,
        @Query("startTime") startTime: Long? = null,
        @Query("endTime") endTime: Long? = null
    ): Call<List<List<Any>>>
}
