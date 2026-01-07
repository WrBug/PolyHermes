package com.wrbug.polymarketbot.api

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.*

/**
 * Polymarket CLOB API 接口定义
 * 用于程序化地管理市场订单
 */
interface PolymarketClobApi {
    
    /**
     * 获取订单簿
     * 注意：Polymarket CLOB API 的 /book 接口需要 token_id 参数
     * 但我们先尝试使用 market 参数，如果不支持再修改
     */
    @GET("/book")
    suspend fun getOrderbook(
        @Query("token_id") tokenId: String? = null,
        @Query("market") market: String? = null
    ): Response<OrderbookResponse>
    
    /**
     * 获取价格信息
     * 注意：Polymarket CLOB API 的 /price 接口需要 token_id 和 side 参数
     * 但我们使用订单簿来获取价格，因为订单簿支持 market 参数
     */
    @GET("/price")
    suspend fun getPrice(
        @Query("token_id") tokenId: String? = null,
        @Query("side") side: String? = null,
        @Query("market") market: String? = null
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
     * 文档: https://docs.polymarket.com/developers/CLOB/orders/create-order
     * 端点: POST /order（注意是单数，不是 /orders）
     * 需要 L2 认证
     * 
     * 请求格式：
     * {
     *   "order": { signed order object },
     *   "owner": "api key",
     *   "orderType": "GTC" | "FOK" | "GTD" | "FAK"
     * }
     */
    @POST("/order")
    suspend fun createOrder(
        @Body request: NewOrderRequest
    ): Response<NewOrderResponse>
    
    /**
     * 批量创建订单
     */
    @POST("/orders/batch")
    suspend fun createOrdersBatch(
        @Body request: CreateOrdersBatchRequest
    ): Response<List<OrderResponse>>
    
    /**
     * 获取订单信息
     * 文档: https://docs.polymarket.com/developers/CLOB/orders/get-order
     * 端点: GET /data/order/{order_hash}
     * 需要 L2 认证
     * 注意：实际返回格式是直接返回 OpenOrder 对象，不是包装在 { "order": ... } 中
     */
    @GET("/data/order/{orderId}")
    suspend fun getOrder(
        @Path("orderId") orderId: String
    ): Response<OpenOrder>
    
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
     * 获取费率
     * 文档: https://docs.polymarket.com/developers/market-makers/maker-rebates-program#1-fetch-the-fee-rate
     * 端点: GET /fee-rate
     * 
     * @param tokenId Token ID
     * @return 费率响应
     */
    @GET("/fee-rate")
    suspend fun getFeeRate(
        @Query("token_id") tokenId: String
    ): Response<FeeRateResponse>
    
    /**
     * 获取服务器时间
     * 端点: /time
     */
    @GET("/time")
    suspend fun getServerTime(): Response<ServerTimeResponse>
}

// 请求和响应数据类

/**
 * 签名的订单对象（根据官方文档）
 * 参考: https://docs.polymarket.com/developers/CLOB/orders/create-order
 */
data class SignedOrderObject(
    val salt: Long,                    // random salt used to create unique order
    val maker: String,                  // maker address (funder)
    val signer: String,                 // signing address
    val taker: String,                  // taker address (operator)
    val tokenId: String,                // ERC1155 token ID of conditional token being traded
    val makerAmount: String,            // maximum amount maker is willing to spend
    val takerAmount: String,            // minimum amount taker will pay the maker in return
    val expiration: String,             // unix expiration timestamp
    val nonce: String,                  // maker's exchange nonce of the order is associated
    val feeRateBps: String,             // fee rate basis points as required by the operator
    val side: String,                   // buy or sell enum index ("BUY" or "SELL")
    val signatureType: Int,             // signature type enum index
    val signature: String               // hex encoded signature
)

/**
 * 创建订单请求（根据官方文档）
 * 参考: https://docs.polymarket.com/developers/CLOB/orders/create-order
 */
data class NewOrderRequest(
    val order: SignedOrderObject,       // signed object
    val owner: String,                  // api key of order owner
    val orderType: String,              // order type ("FOK", "GTC", "GTD", "FAK")
    val deferExec: Boolean = false      // defer execution flag
)

/**
 * 创建订单响应（根据官方文档）
 * 注意：API 返回的字段名是 orderID（大写），需要使用 @SerializedName 映射
 */
data class NewOrderResponse(
    val success: Boolean,               // boolean indicating if server-side error
    @SerializedName("errorMsg")
    val errorMsg: String? = null,       // error message in case of unsuccessful placement
    @SerializedName("orderID")
    val orderId: String? = null,        // id of order（API 返回字段名为 orderID）
    @SerializedName("transactionsHashes")
    val transactionsHashes: List<String>? = null,  // transaction hashes（API 返回字段名为 transactionsHashes）
    @SerializedName("status")
    val status: String? = null,         // order status (matched, pending, etc.)
    @SerializedName("takingAmount")
    val takingAmount: String? = null,   // taking amount
    @SerializedName("makingAmount")
    val makingAmount: String? = null    // making amount
)

/**
 * 旧的订单请求格式（已废弃，保留用于兼容）
 * @deprecated 使用 NewOrderRequest 代替
 */
@Deprecated("使用 NewOrderRequest 代替，需要签名的订单对象")
data class CreateOrderRequest(
    val market: String? = null,      // condition ID（可选，如果提供tokenId则不需要）
    val token_id: String? = null,    // token ID（可选，如果提供market则不需要）
    val side: String,                // "BUY" or "SELL"
    val price: String,
    val size: String,
    val type: String = "LIMIT",
    val expiration: Long? = null
)

@Deprecated("使用 NewOrderRequest 代替")
data class CreateOrdersBatchRequest(
    val orders: List<NewOrderRequest>
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

/**
 * OpenOrder 对象（根据实际 API 返回）
 * 文档: https://docs.polymarket.com/developers/CLOB/orders/get-order
 * 注意：实际返回格式是直接返回订单对象，不是包装在 { "order": ... } 中
 */
data class OpenOrder(
    val id: String,                              // order id
    val status: String,                          // order current status (LIVE, FILLED, CANCELLED, etc.)
    val owner: String,                           // api key
    @SerializedName("maker_address")
    val makerAddress: String,                    // maker address (funder)
    val market: String,                          // market id (condition id)
    @SerializedName("asset_id")
    val assetId: String,                         // token id
    val side: String,                            // BUY or SELL
    @SerializedName("original_size")
    val originalSize: String,                    // original order size at placement
    @SerializedName("size_matched")
    val sizeMatched: String,                     // size of order that has been matched/filled
    val price: String,                           // price
    val outcome: String,                         // human readable outcome the order is for
    val expiration: String,                       // unix timestamp when the order expired, 0 if it does not expire
    @SerializedName("order_type")
    val orderType: String,                        // order type (GTC, FOK, GTD)
    @SerializedName("associate_trades")
    val associateTrades: List<String>? = null,  // any Trade id the order has been partially included in
    @SerializedName("created_at")
    val createdAt: Long                          // unix timestamp when the order was created
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
    val side: String,  // BUY 或 SELL
    val price: String,
    val size: String,
    val timestamp: String,  // ISO 8601 格式字符串或时间戳
    val user: String?,
    val outcomeIndex: Int? = null,  // 结果索引（0=YES, 1=NO）
    val outcome: String? = null    // 结果名称（如 "Up", "Down"）
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

/**
 * 费率响应
 * 文档: https://docs.polymarket.com/developers/market-makers/maker-rebates-program#1-fetch-the-fee-rate
 * 
 * 注意：根据 TypeScript clob-client 源码，API 返回的字段名是 base_fee，而不是文档中的 fee_rate_bps
 * 参考: clob-client/src/client.ts:312
 */
data class FeeRateResponse(
    @SerializedName("base_fee")
    val baseFee: Int  // 费率基点（0 表示无费率，1000 表示 10%）
)

/**
 * 最新价响应（从订单表获取）
 */
data class LatestPriceResponse(
    val tokenId: String,
    val bestBid: String?,  // 最高买入价
    val bestAsk: String?   // 最低卖出价
)

