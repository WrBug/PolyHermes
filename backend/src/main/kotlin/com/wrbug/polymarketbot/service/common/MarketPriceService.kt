package com.wrbug.polymarketbot.service.common

import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.BigInteger

/**
 * 市场价格服务
 * 统一封装从不同数据源获取市场价格的逻辑
 * 数据源包括：
 * 1. 链上 RPC 查询（市场结算结果）
 * 2. CLOB API（订单簿价格）
 */
@Service
class MarketPriceService(
    private val blockchainService: BlockchainService,
    private val retrofitFactory: RetrofitFactory,
    private val accountRepository: AccountRepository,
    private val cryptoUtils: CryptoUtils
) {
    
    private val logger = LoggerFactory.getLogger(MarketPriceService::class.java)
    
    /**
     * 获取当前市场最新价
     * 优先级：
     * 1. 链上查询市场结算结果（如果已结算，返回 1.0 或 0.0）
     * 2. CLOB API 查询订单簿价格（最准确，优先使用，使用 bestBid）
     * 3. Gamma Market API 查询市场价格（快速，作为备选）
     * 
     * 价格会被截位到 4 位小数（向下截断，不四舍五入），用于显示和后续计算
     * 
     * @param marketId 市场ID
     * @param outcomeIndex 结果索引
     * @return 市场价格（已截位到 4 位小数）
     * @throws IllegalStateException 如果所有数据源都失败
     */
    suspend fun getCurrentMarketPrice(marketId: String, outcomeIndex: Int): BigDecimal {
        // 1. 优先从链上查询市场结算结果
        val (chainPrice, hasRpcError) = getPriceFromChainCondition(marketId, outcomeIndex)
        if (chainPrice != null) {
            // 截位到 4 位小数（向下截断，不四舍五入）
            return chainPrice.setScale(4, java.math.RoundingMode.DOWN)
        }
        
        // 如果链上查询出现 RPC 错误（execution reverted），说明市场可能不存在或尚未创建
        // 在这种情况下，降级到其他数据源（CLOB API 或 Gamma API），而不是直接抛出异常
        // 因为 marketId 可能在 API 中存在，但在链上尚未创建
        if (hasRpcError) {
            logger.debug("链上查询市场条件出现 RPC 错误（execution reverted），降级到 API 查询: marketId=$marketId, outcomeIndex=$outcomeIndex")
        }
        
        // 2. 从 CLOB API 查询订单簿价格（最准确，优先使用）
        val orderbookPrice = getPriceFromClobOrderbook(marketId, outcomeIndex)
        if (orderbookPrice != null) {
            // 截位到 4 位小数（向下截断，不四舍五入）
            return orderbookPrice.setScale(4, java.math.RoundingMode.DOWN)
        }
        
        // 3. 从 Gamma Market API 查询市场价格（作为备选）
        val marketPrice = getPriceFromGammaMarket(marketId, outcomeIndex)
        if (marketPrice != null) {
            // 截位到 4 位小数（向下截断，不四舍五入）
            return marketPrice.setScale(4, java.math.RoundingMode.DOWN)
        }
        
        // 如果所有数据源都失败，抛出异常
        val errorMsg = "无法获取市场价格: marketId=$marketId, outcomeIndex=$outcomeIndex (链上查询、订单簿查询和 Market API 均失败)"
        logger.error(errorMsg)
        throw IllegalStateException(errorMsg)
    }
    
    /**
     * 从链上查询市场结算结果获取价格
     * 如果市场已结算：
     *   - payout > 0（赢了）→ 返回 1.0
     *   - payout == 0（输了）→ 返回 0.0
     * 如果市场未结算或查询失败，返回 null
     * 
     * @return Pair<BigDecimal?, Boolean> 第一个值是价格（如果已结算），第二个值表示是否发生了 RPC 错误（execution reverted）
     */
    private suspend fun getPriceFromChainCondition(marketId: String, outcomeIndex: Int): Pair<BigDecimal?, Boolean> {
        return try {
            val chainResult = blockchainService.getCondition(marketId)
            chainResult.fold(
                onSuccess = { (_, payouts) ->
                    // 如果 payouts 不为空，说明市场已结算
                    if (payouts.isNotEmpty() && outcomeIndex < payouts.size) {
                        val payout = payouts[outcomeIndex]
                        when {
                            payout > BigInteger.ZERO -> {
                                logger.info("从链上查询到市场已结算，该 outcome 赢了: marketId=$marketId, outcomeIndex=$outcomeIndex, payout=$payout")
                                return Pair(BigDecimal.ONE, false)
                            }
                            payout == BigInteger.ZERO -> {
                                logger.info("从链上查询到市场已结算，该 outcome 输了: marketId=$marketId, outcomeIndex=$outcomeIndex, payout=$payout")
                                return Pair(BigDecimal.ZERO, false)
                            }
                            else -> {
                                logger.warn("从链上查询到异常的 payout 值: marketId=$marketId, outcomeIndex=$outcomeIndex, payout=$payout")
                                Pair(null, false)
                            }
                        }
                    } else {
                        logger.debug("从链上查询到市场尚未结算: marketId=$marketId, payouts=${payouts.size}")
                        Pair(null, false)
                    }
                },
                onFailure = { e ->
                    // 检查是否是 execution reverted 错误
                    val isRpcError = e.message?.contains("execution reverted", ignoreCase = true) == true
                    if (isRpcError) {
                        logger.warn("链上查询市场条件出现 RPC 错误（execution reverted），可能市场不存在或尚未创建: marketId=$marketId, error=${e.message}")
                    } else {
                    logger.debug("链上查询市场条件失败，降级到 API 查询: marketId=$marketId, error=${e.message}")
                    }
                    Pair(null, isRpcError)
                }
            )
        } catch (e: Exception) {
            val isRpcError = e.message?.contains("execution reverted", ignoreCase = true) == true
            if (isRpcError) {
                logger.warn("链上查询市场条件异常（execution reverted）: marketId=$marketId, outcomeIndex=$outcomeIndex, error=${e.message}")
            } else {
            logger.debug("链上查询市场条件异常: marketId=$marketId, outcomeIndex=$outcomeIndex, error=${e.message}")
            }
            Pair(null, isRpcError)
        }
    }
    
    /**
     * 从 Gamma Market API 获取价格
     * 使用 outcomePrices 字段，格式通常为 JSON 字符串 "[\"0.5\", \"0.5\"]"
     * 如果查询失败或 outcomePrices 为空，返回 null
     */
    private suspend fun getPriceFromGammaMarket(marketId: String, outcomeIndex: Int): BigDecimal? {
        return try {
            val gammaApi = retrofitFactory.createGammaApi()
            val marketResponse = gammaApi.listMarkets(conditionIds = listOf(marketId))
            
            if (!marketResponse.isSuccessful || marketResponse.body() == null) {
                logger.debug("Gamma Market API 查询失败: marketId=$marketId, code=${marketResponse.code()}")
                return null
            }
            
            val markets = marketResponse.body()!!
            if (markets.isEmpty()) {
                logger.debug("Gamma Market API 未找到市场: marketId=$marketId")
                return null
            }
            
            val market = markets.first()
            
            // 尝试从 outcomePrices 字段获取价格
            val outcomePricesStr = market.outcomePrices
            if (outcomePricesStr.isNullOrBlank()) {
                logger.debug("Market outcomePrices 为空: marketId=$marketId")
                return null
            }
            
            // 解析 outcomePrices（通常是 JSON 数组字符串）
            val outcomePrices = try {
                // 移除首尾的方括号和引号，按逗号分割
                val cleanStr = outcomePricesStr.trim().removeSurrounding("[", "]")
                cleanStr.split(",").map { 
                    it.trim().removeSurrounding("\"").toSafeBigDecimal() 
                }
            } catch (e: Exception) {
                logger.warn("解析 outcomePrices 失败: marketId=$marketId, outcomePrices=$outcomePricesStr, error=${e.message}")
                null
            }
            
            if (outcomePrices != null && outcomeIndex < outcomePrices.size) {
                val price = outcomePrices[outcomeIndex]
                logger.debug("从 Gamma Market API 获取价格: marketId=$marketId, outcomeIndex=$outcomeIndex, price=$price")
                return price
            }
            
            null
        } catch (e: Exception) {
            logger.debug("Gamma Market API 查询异常: marketId=$marketId, outcomeIndex=$outcomeIndex, error=${e.message}")
            null
        }
    }
    
    
    /**
     * 从 CLOB API 查询订单簿价格
     * 获取订单簿的 bestBid 和 bestAsk，计算 midpoint = (bestBid + bestAsk) / 2
     * 订单簿数据最准确，反映当前市场真实价格
     * 如果查询失败，返回 null
     */
    private suspend fun getPriceFromClobOrderbook(marketId: String, outcomeIndex: Int): BigDecimal? {
        return try {
            // 获取 tokenId（用于查询特定 outcome 的订单簿）
            val tokenIdResult = blockchainService.getTokenId(marketId, outcomeIndex)
            if (!tokenIdResult.isSuccess) {
                return null
            }
            
            val tokenId = tokenIdResult.getOrNull() ?: return null
            
            // 尝试使用带鉴权的 CLOB API，如果没有则使用不带鉴权的 API
            val clobApi = try {
                getAuthenticatedClobApi() ?: retrofitFactory.createClobApiWithoutAuth()
            } catch (e: Exception) {
                logger.debug("获取带鉴权的 CLOB API 失败，使用不带鉴权的 API: ${e.message}")
                retrofitFactory.createClobApiWithoutAuth()
            }
            
            val orderbookResponse = clobApi.getOrderbook(tokenId = tokenId, market = null)
            
            if (!orderbookResponse.isSuccessful || orderbookResponse.body() == null) {
                return null
            }
            
            val orderbook = orderbookResponse.body()!!
            
            // 获取 bestBid（最高买入价）：从 bids 中找到价格最大的
            // bids 表示买入订单列表，价格越高表示愿意出的价格越高
            val bestBid = orderbook.bids
                .mapNotNull { it.price.toSafeBigDecimal() }
                .maxOrNull()
            
            // 获取 bestAsk（最低卖出价）：从 asks 中找到价格最小的
            // asks 表示卖出订单列表，价格越低表示愿意卖的价格越低
            val bestAsk = orderbook.asks
                .mapNotNull { it.price.toSafeBigDecimal() }
                .minOrNull()
            
            // 由于主要用于卖出场景，优先使用 bestBid（最高买入价，卖给愿意买入的人）
            // 如果没有 bestBid，则使用 midpoint 或 bestAsk
            if (bestBid != null) {
                logger.debug("从订单簿获取价格（bestBid）: marketId=$marketId, outcomeIndex=$outcomeIndex, bestBid=$bestBid, bestAsk=$bestAsk")
                return bestBid
            } else if (bestAsk != null && bestAsk > BigDecimal.ZERO) {
                // 如果没有 bestBid，使用 bestAsk 作为备选
                logger.debug("从订单簿获取价格（bestAsk）: marketId=$marketId, outcomeIndex=$outcomeIndex, bestAsk=$bestAsk")
                return bestAsk
            }
            
            null
        } catch (e: Exception) {
            logger.debug("CLOB API 查询订单簿失败: marketId=$marketId, outcomeIndex=$outcomeIndex, error=${e.message}")
            null
        }
    }
    
    /**
     * 获取带鉴权的 CLOB API 客户端
     * 使用第一个有 API 凭证的账户
     * 如果都没有，返回 null
     */
    private fun getAuthenticatedClobApi(): PolymarketClobApi? {
        return try {
            // 使用第一个有 API 凭证的账户
            val account = accountRepository.findAllByOrderByCreatedAtAsc()
                .firstOrNull { it.apiKey != null && it.apiSecret != null && it.apiPassphrase != null }
            
            if (account == null || account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                return null
            }
            
            // 解密 API 凭证
            val apiKey = account.apiKey
            val apiSecret = try {
                cryptoUtils.decrypt(account.apiSecret)
            } catch (e: Exception) {
                logger.debug("解密 API Secret 失败: ${e.message}")
                return null
            }
            val apiPassphrase = try {
                cryptoUtils.decrypt(account.apiPassphrase)
            } catch (e: Exception) {
                logger.debug("解密 API Passphrase 失败: ${e.message}")
                return null
            }
            
            // 创建带鉴权的 CLOB API 客户端
            retrofitFactory.createClobApi(apiKey, apiSecret, apiPassphrase, account.walletAddress)
        } catch (e: Exception) {
            logger.debug("获取带鉴权的 CLOB API 失败: ${e.message}")
            null
        }
    }
    
}

