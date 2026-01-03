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
     * 2. CLOB API 查询订单簿价格（最准确，使用 bestBid）
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
        val chainPrice = getPriceFromChainCondition(marketId, outcomeIndex)
        if (chainPrice != null) {
            // 截位到 4 位小数（向下截断，不四舍五入）
            return chainPrice.setScale(4, java.math.RoundingMode.DOWN)
        }
        
        // 2. 从 CLOB API 查询订单簿价格（最准确）
        val orderbookPrice = getPriceFromClobOrderbook(marketId, outcomeIndex)
        if (orderbookPrice != null) {
            // 截位到 4 位小数（向下截断，不四舍五入）
            return orderbookPrice.setScale(4, java.math.RoundingMode.DOWN)
        }
        
        // 如果所有数据源都失败，抛出异常
        val errorMsg = "无法获取市场价格: marketId=$marketId, outcomeIndex=$outcomeIndex (链上查询和订单簿查询均失败)"
        logger.error(errorMsg)
        throw IllegalStateException(errorMsg)
    }
    
    /**
     * 从链上查询市场结算结果获取价格
     * 如果市场已结算：
     *   - payout > 0（赢了）→ 返回 1.0
     *   - payout == 0（输了）→ 返回 0.0
     * 如果市场未结算或查询失败，返回 null
     */
    private suspend fun getPriceFromChainCondition(marketId: String, outcomeIndex: Int): BigDecimal? {
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
                                return BigDecimal.ONE
                            }
                            payout == BigInteger.ZERO -> {
                                logger.info("从链上查询到市场已结算，该 outcome 输了: marketId=$marketId, outcomeIndex=$outcomeIndex, payout=$payout")
                                return BigDecimal.ZERO
                            }
                            else -> {
                                logger.warn("从链上查询到异常的 payout 值: marketId=$marketId, outcomeIndex=$outcomeIndex, payout=$payout")
                                null
                            }
                        }
                    } else {
                        logger.debug("从链上查询到市场尚未结算: marketId=$marketId, payouts=${payouts.size}")
                        null
                    }
                },
                onFailure = { e ->
                    logger.debug("链上查询市场条件失败，降级到 API 查询: marketId=$marketId, error=${e.message}")
                    null
                }
            )
        } catch (e: Exception) {
            logger.debug("链上查询市场条件异常: marketId=$marketId, outcomeIndex=$outcomeIndex, error=${e.message}")
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

