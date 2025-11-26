package com.wrbug.polymarketbot.api

import retrofit2.Response
import retrofit2.http.*

/**
 * Polymarket CLOB API 接口定义
 * 用于程序化地管理市场订单
 */
interface PolymarketClobApi {
    
    /**
     * 获取订单簿
     */
    @GET("/book")
    suspend fun getOrderbook(
        @Query("market") market: String
    ): Response<OrderbookResponse>
    
    /**
     * 获取价格信息
     */
    @GET("/price")
    suspend fun getPrice(
        @Query("market") market: String
    ): Response<PriceResponse>
    
    /**
     * 获取中间价
     */
    @GET("/midpoint")
    suspend fun getMidpoint(
        @Query("market") market: String
    ): Response<MidpointResponse>
    
    /**
     * 获取价差
     */
    @GET("/spreads")
    suspend fun getSpreads(
        @Query("market") market: String
    ): Response<SpreadsResponse>
    
    /**
     * 创建单个订单
     */
    @POST("/orders")
    suspend fun createOrder(
        @Body request: CreateOrderRequest
    ): Response<OrderResponse>
    
    /**
     * 批量创建订单
     */
    @POST("/orders/batch")
    suspend fun createOrdersBatch(
        @Body request: CreateOrdersBatchRequest
    ): Response<List<OrderResponse>>
    
    /**
     * 获取订单信息
     */
    @GET("/orders/{orderId}")
    suspend fun getOrder(
        @Path("orderId") orderId: String
    ): Response<OrderResponse>
    
    /**
     * 获取活跃订单
     * 端点: /data/orders
     * 注意：Polymarket CLOB API 使用 GET 方法，参数通过 query params 传递
     * 虽然项目规范要求使用 POST，但这是外部 API，必须遵循 API 的实际要求
     */
    @GET("/data/orders")
    suspend fun getActiveOrders(
        @Query("id") id: String? = null,
        @Query("market") market: String? = null,
        @Query("asset_id") asset_id: String? = null,
        @Query("next_cursor") next_cursor: String? = null
    ): Response<GetActiveOrdersResponse>
    
    /**
     * 取消订单
     */
    @DELETE("/orders/{orderId}")
    suspend fun cancelOrder(
        @Path("orderId") orderId: String
    ): Response<CancelOrderResponse>
    
    /**
     * 批量取消订单
     */
    @DELETE("/orders/batch")
    suspend fun cancelOrdersBatch(
        @Body request: CancelOrdersBatchRequest
    ): Response<CancelOrdersBatchResponse>
    
    /**
     * 获取交易记录
     * 端点: /data/trades
     * 注意：Polymarket CLOB API 使用 GET 方法，参数通过 query params 传递
     */
    @GET("/data/trades")
    suspend fun getTrades(
        @Query("id") id: String? = null,
        @Query("maker_address") maker_address: String? = null,
        @Query("market") market: String? = null,
        @Query("asset_id") asset_id: String? = null,
        @Query("before") before: String? = null,
        @Query("after") after: String? = null,
        @Query("next_cursor") next_cursor: String? = null
    ): Response<GetTradesResponse>
    
    /**
     * 创建 API Key（L1 认证）
     * 端点: /auth/api-key
     * 需要 L1 认证头（POLY_ADDRESS, POLY_SIGNATURE, POLY_TIMESTAMP, POLY_NONCE）
     */
    @POST("/auth/api-key")
    suspend fun createApiKey(): Response<ApiKeyResponse>
    
    /**
     * 获取现有 API Key（L1 认证）
     * 端点: /auth/derive-api-key
     * 需要 L1 认证头（POLY_ADDRESS, POLY_SIGNATURE, POLY_TIMESTAMP, POLY_NONCE）
     */
    @GET("/auth/derive-api-key")
    suspend fun deriveApiKey(): Response<ApiKeyResponse>
    
    /**
     * 获取服务器时间
     * 端点: /time
     */
    @GET("/time")
    suspend fun getServerTime(): Response<ServerTimeResponse>
}

// 请求和响应数据类
data class CreateOrderRequest(
    val market: String,
    val side: String,  // "BUY" or "SELL"
    val price: String,
    val size: String,
    val type: String = "LIMIT",
    val expiration: Long? = null
)

data class CreateOrdersBatchRequest(
    val orders: List<CreateOrderRequest>
)

data class CancelOrdersBatchRequest(
    val orderIds: List<String>
)

data class OrderbookResponse(
    val bids: List<OrderbookEntry>,
    val asks: List<OrderbookEntry>
)

data class OrderbookEntry(
    val price: String,
    val size: String
)

data class PriceResponse(
    val market: String,
    val lastPrice: String?,
    val bestBid: String?,
    val bestAsk: String?
)

data class MidpointResponse(
    val market: String,
    val midpoint: String
)

data class SpreadsResponse(
    val market: String,
    val spread: String
)

data class OrderResponse(
    val id: String,
    val market: String,
    val side: String,
    val price: String,
    val size: String,
    val filled: String,
    val status: String,
    val createdAt: String  // ISO 8601 格式字符串
)

data class CancelOrderResponse(
    val orderId: String,
    val status: String
)

data class CancelOrdersBatchResponse(
    val cancelled: List<String>,
    val failed: List<String>
)

data class TradeResponse(
    val id: String,
    val market: String,
    val side: String,
    val price: String,
    val size: String,
    val timestamp: String,  // ISO 8601 格式字符串
    val user: String?
)

/**
 * 获取活跃订单响应
 * 注意：参数通过 @Query 传递，不需要单独的 Request 类
 */
data class GetActiveOrdersResponse(
    val data: List<OrderResponse>,
    val next_cursor: String? = null
)

/**
 * 获取交易记录响应
 */
data class GetTradesResponse(
    val data: List<TradeResponse>,
    val next_cursor: String? = null
)

/**
 * API Key 响应
 */
data class ApiKeyResponse(
    val apiKey: String,
    val secret: String,
    val passphrase: String
)

/**
 * 服务器时间响应
 */
data class ServerTimeResponse(
    val timestamp: Long
)

