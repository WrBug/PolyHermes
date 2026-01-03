package com.wrbug.polymarketbot.service.common

import com.wrbug.polymarketbot.api.*
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * Polymarket CLOB API 服务封装
 * 提供订单操作、市场数据、交易数据等功能
 */
@Service
class PolymarketClobService(
    private val clobApi: PolymarketClobApi,  // 用于不需要认证的接口
    private val retrofitFactory: RetrofitFactory  // 用于创建带认证的客户端
) {
    
    private val logger = LoggerFactory.getLogger(PolymarketClobService::class.java)
    
    /**
     * 获取订单簿
     * 使用 market 参数（condition ID）
     */
    suspend fun getOrderbook(market: String): Result<OrderbookResponse> {
        return try {
            val response = clobApi.getOrderbook(tokenId = null, market = market)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("获取订单簿失败: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("获取订单簿异常: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 通过 tokenId 获取订单簿
     * 用于三元及以上市场，获取特定 outcome 的价格
     */
    suspend fun getOrderbookByTokenId(tokenId: String): Result<OrderbookResponse> {
        return try {
            val response = clobApi.getOrderbook(tokenId = tokenId, market = null)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("获取订单簿失败: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("获取订单簿异常: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 从订单表获取最新价（供前端显示使用）
     * 支持多元市场（二元、三元及以上）
     * 
     * @param tokenId token ID（通过 marketId 和 outcomeIndex 计算得出）
     * @return 最新价信息（bestBid 和 bestAsk），如果获取失败则返回错误
     */
    suspend fun getLatestPrice(tokenId: String): Result<LatestPriceResponse> {
        return try {
            val orderbookResult = getOrderbookByTokenId(tokenId)
            
            if (!orderbookResult.isSuccess) {
                val error = orderbookResult.exceptionOrNull()
                return Result.failure(Exception("获取订单簿失败: ${error?.message ?: "未知错误"}"))
            }
            
            val orderbook = orderbookResult.getOrNull()
            if (orderbook == null) {
                return Result.failure(IllegalStateException("订单表为空: tokenId=$tokenId"))
            }
            
            // 获取 bestBid（最高买入价）
            val bestBid = orderbook.bids.firstOrNull()?.price
            val bestBidPrice = bestBid?.toSafeBigDecimal()
            
            // 获取 bestAsk（最低卖出价）
            val bestAsk = orderbook.asks.firstOrNull()?.price
            val bestAskPrice = bestAsk?.toSafeBigDecimal()
            
            // 验证价格范围
            if (bestBidPrice != null && (bestBidPrice < BigDecimal("0.01") || bestBidPrice > BigDecimal("0.99"))) {
                logger.warn("订单表 bestBid 价格超出有效范围: $bestBid (tokenId=$tokenId)")
            }
            if (bestAskPrice != null && (bestAskPrice < BigDecimal("0.01") || bestAskPrice > BigDecimal("0.99"))) {
                logger.warn("订单表 bestAsk 价格超出有效范围: $bestAsk (tokenId=$tokenId)")
            }
            
            Result.success(
                LatestPriceResponse(
                    tokenId = tokenId,
                    bestBid = bestBid,
                    bestAsk = bestAsk
                )
            )
        } catch (e: Exception) {
            logger.error("获取最新价异常: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 从订单表获取最优价（用于市价单，带价格调整系数）
     * 支持多元市场（二元、三元及以上）
     * 
     * @param tokenId token ID（通过 marketId 和 outcomeIndex 计算得出）
     * @param isSellOrder 是否为卖出订单（true: 卖单，需要 bestBid；false: 买单，需要 bestAsk）
     * @param buyPriceAdjustment 买单价格调整系数（默认 +0.01）
     * @param sellPriceAdjustment 卖单价格调整系数（默认 -0.02）
     * @return 最优价格（已应用调整系数）
     * @throws IllegalStateException 如果无法获取订单表或订单表为空
     */
    suspend fun getOptimalPrice(
        tokenId: String,
        isSellOrder: Boolean,
        buyPriceAdjustment: BigDecimal = BigDecimal("0.01"),
        sellPriceAdjustment: BigDecimal = BigDecimal("0.02")
    ): String {
        val orderbookResult = getOrderbookByTokenId(tokenId)
        
        if (!orderbookResult.isSuccess) {
            val error = orderbookResult.exceptionOrNull()
            val errorMsg = "获取订单表失败: ${error?.message ?: "未知错误"}"
            logger.error(errorMsg)
            throw IllegalStateException(errorMsg)
        }
        
        val orderbook = orderbookResult.getOrNull()
        if (orderbook == null) {
            val errorMsg = "订单表为空: tokenId=$tokenId"
            logger.error(errorMsg)
            throw IllegalStateException(errorMsg)
        }
        
        if (isSellOrder) {
            // 市价卖单：需要 bestBid（最高买入价）
            val bestBid = orderbook.bids.firstOrNull()?.price
            if (bestBid == null) {
                val errorMsg = "订单表 bids 为空: tokenId=$tokenId"
                logger.error(errorMsg)
                throw IllegalStateException(errorMsg)
            }
            
            val bestBidPrice = bestBid.toSafeBigDecimal()
            if (bestBidPrice < BigDecimal("0.01") || bestBidPrice > BigDecimal("0.99")) {
                val errorMsg = "订单表 bestBid 价格超出有效范围: $bestBid (tokenId=$tokenId)"
                logger.error(errorMsg)
                throw IllegalStateException(errorMsg)
            }
            
            // 应用价格调整系数：bestBid - sellPriceAdjustment（减价，确保能立即成交）
            val adjustedPrice = bestBidPrice.subtract(sellPriceAdjustment)
            val finalPrice = when {
                adjustedPrice < BigDecimal("0.01") -> BigDecimal("0.01")
                adjustedPrice > BigDecimal("0.99") -> BigDecimal("0.99")
                else -> adjustedPrice
            }
            return finalPrice.toPlainString()
        } else {
            // 市价买单：需要 bestAsk（最低卖出价）
            val bestAsk = orderbook.asks.firstOrNull()?.price
            if (bestAsk == null) {
                val errorMsg = "订单表 asks 为空: tokenId=$tokenId"
                logger.error(errorMsg)
                throw IllegalStateException(errorMsg)
            }
            
            val bestAskPrice = bestAsk.toSafeBigDecimal()
            if (bestAskPrice < BigDecimal("0.01") || bestAskPrice > BigDecimal("0.99")) {
                val errorMsg = "订单表 bestAsk 价格超出有效范围: $bestAsk (tokenId=$tokenId)"
                logger.error(errorMsg)
                throw IllegalStateException(errorMsg)
            }
            
            // 应用价格调整系数：bestAsk + buyPriceAdjustment（加价，确保能立即成交）
            val adjustedPrice = bestAskPrice.add(buyPriceAdjustment)
            val finalPrice = when {
                adjustedPrice < BigDecimal("0.01") -> BigDecimal("0.01")
                adjustedPrice > BigDecimal("0.99") -> BigDecimal("0.99")
                else -> adjustedPrice
            }
            return finalPrice.toPlainString()
        }
    }
    
    /**
     * 获取价格信息
     */
    suspend fun getPrice(market: String): Result<PriceResponse> {
        return try {
            val response = clobApi.getPrice(market)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("获取价格失败: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("获取价格异常: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 获取中间价
     */
    suspend fun getMidpoint(market: String): Result<MidpointResponse> {
        return try {
            val response = clobApi.getMidpoint(market)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("获取中间价失败: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("获取中间价异常: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 创建订单（已废弃，使用 createSignedOrder 代替）
     * @deprecated 使用 createSignedOrder 代替，需要签名的订单对象
     */
    @Deprecated("使用 createSignedOrder 代替")
    suspend fun createOrder(request: CreateOrderRequest): Result<OrderResponse> {
        return Result.failure(UnsupportedOperationException("已废弃，请使用 createSignedOrder 方法"))
    }
    
    /**
     * 创建签名的订单
     * 注意：此方法需要完整的订单签名逻辑，当前为占位实现
     * TODO: 实现完整的订单签名逻辑（EIP-712 签名、金额计算等）
     * 参考: clob-client/src/order-builder/helpers.ts
     */
    suspend fun createSignedOrder(request: NewOrderRequest): Result<NewOrderResponse> {
        return try {
            val response = clobApi.createOrder(request)
            if (response.isSuccessful && response.body() != null) {
                val responseBody = response.body()!!
                if (responseBody.success) {
                    Result.success(responseBody)
                } else {
                    val errorBody = try {
                        response.errorBody()?.string()
                    } catch (e: Exception) {
                        null
                    }
                    val errorMsg = "创建订单失败: orderType=${request.orderType}, owner=${request.owner}, errorMsg=${responseBody.errorMsg}${if (errorBody != null) ", errorBody=$errorBody" else ""}"
                    logger.error(errorMsg)
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorBody = try {
                    response.errorBody()?.string()
                } catch (e: Exception) {
                    null
                }
                val errorMsg = "创建订单失败: orderType=${request.orderType}, owner=${request.owner}, code=${response.code()}, message=${response.message()}${if (errorBody != null) ", errorBody=$errorBody" else ""}"
                logger.error(errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            val errorMsg = "创建订单异常: orderType=${request.orderType}, owner=${request.owner}, error=${e.message}"
            logger.error(errorMsg, e)
            Result.failure(Exception(errorMsg, e))
        }
    }
    
    /**
     * 获取订单详情（需要 L2 认证）
     * 文档: https://docs.polymarket.com/developers/CLOB/orders/get-order
     * 
     * @param orderId 订单 ID
     * @param apiKey API Key
     * @param apiSecret API Secret
     * @param apiPassphrase API Passphrase
     * @param walletAddress 钱包地址（用于 POLY_ADDRESS 请求头）
     * @return 订单详情
     */
    suspend fun getOrder(
        orderId: String,
        apiKey: String,
        apiSecret: String,
        apiPassphrase: String,
        walletAddress: String
    ): Result<OpenOrder> {
        return try {
            // 创建带 L2 认证的 API 客户端
            val authenticatedClobApi = retrofitFactory.createClobApi(
                apiKey = apiKey,
                apiSecret = apiSecret,
                apiPassphrase = apiPassphrase,
                walletAddress = walletAddress
            )
            
            val response = authenticatedClobApi.getOrder(orderId)
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    Result.success(body)
                } else {
                    // 响应体为空，可能是订单不存在或已过期
                    logger.warn("获取订单详情失败: 响应体为空, orderId=$orderId, code=${response.code()}")
                    Result.failure(Exception("订单不存在或已过期: orderId=$orderId"))
                }
            } else {
                // HTTP 状态码不是 2xx
                val errorBody = response.errorBody()?.string()?.take(200) ?: "无错误详情"
                logger.warn("获取订单详情失败: HTTP ${response.code()}, orderId=$orderId, errorBody=$errorBody")
                Result.failure(Exception("获取订单详情失败: HTTP ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("获取订单详情异常: orderId=$orderId, ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 获取活跃订单
     */
    suspend fun getActiveOrders(
        id: String? = null,
        market: String? = null,
        asset_id: String? = null,
        next_cursor: String? = null
    ): Result<List<OrderResponse>> {
        return try {
            val response = clobApi.getActiveOrders(
                id = id,
                market = market,
                asset_id = asset_id,
                next_cursor = next_cursor
            )
            if (response.isSuccessful && response.body() != null) {
                val ordersResponse = response.body()!!
                Result.success(ordersResponse.data)
            } else {
                Result.failure(Exception("获取活跃订单失败: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("获取活跃订单异常: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 取消订单
     */
    suspend fun cancelOrder(orderId: String): Result<CancelOrderResponse> {
        return try {
            val response = clobApi.cancelOrder(orderId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("取消订单失败: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("取消订单异常: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 获取交易记录
     * 注意：CLOB API 不支持 limit 参数，需要通过 next_cursor 分页
     */
    suspend fun getTrades(
        id: String? = null,
        maker_address: String? = null,
        market: String? = null,
        asset_id: String? = null,
        before: String? = null,
        after: String? = null,
        next_cursor: String? = null,
        limit: Int? = null  // 注意：CLOB API 不支持 limit，这里仅用于文档说明
    ): Result<List<TradeResponse>> {
        return try {
            val response = clobApi.getTrades(
                id = id,
                maker_address = maker_address,
                market = market,
                asset_id = asset_id,
                before = before,
                after = after,
                next_cursor = next_cursor
            )
            if (response.isSuccessful && response.body() != null) {
                val tradesResponse = response.body()!!
                Result.success(tradesResponse.data)
            } else {
                Result.failure(Exception("获取交易记录失败: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("获取交易记录异常: ${e.message}", e)
            Result.failure(e)
        }
    }
}

