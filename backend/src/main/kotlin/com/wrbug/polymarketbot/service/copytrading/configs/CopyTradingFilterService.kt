package com.wrbug.polymarketbot.service.copytrading.configs

import com.wrbug.polymarketbot.api.OrderbookResponse
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.lt
import com.wrbug.polymarketbot.util.multi
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.accounts.AccountService
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * 跟单过滤条件检查服务
 */
@Service
class CopyTradingFilterService(
    private val clobService: PolymarketClobService,
    private val accountService: AccountService
) {
    
    private val logger = LoggerFactory.getLogger(CopyTradingFilterService::class.java)
    
    /**
     * 检查过滤条件
     * @param copyTrading 跟单配置
     * @param tokenId token ID（用于获取订单簿）
     * @param tradePrice Leader 交易价格，用于价格区间检查
     * @param copyOrderAmount 跟单金额（USDC），用于仓位检查，如果为null则不进行仓位检查
     * @param marketId 市场ID，用于仓位检查（按市场过滤仓位）
     * @return 过滤结果
     */
    suspend fun checkFilters(
        copyTrading: CopyTrading,
        tokenId: String,
        tradePrice: BigDecimal? = null,  // Leader 交易价格，用于价格区间检查
        copyOrderAmount: BigDecimal? = null,  // 跟单金额（USDC），用于仓位检查
        marketId: String? = null  // 市场ID，用于仓位检查（按市场过滤仓位）
    ): FilterResult {
        // 1. 价格区间检查（如果配置了价格区间）
        if (tradePrice != null) {
            val priceRangeCheck = checkPriceRange(copyTrading, tradePrice)
            if (!priceRangeCheck.isPassed) {
                return FilterResult.priceRangeFailed(priceRangeCheck.reason)
            }
        }
        
        // 2. 检查是否需要获取订单簿
        // 只有在配置了需要订单簿的过滤条件时才获取
        val needOrderbook = copyTrading.maxSpread != null || copyTrading.minOrderDepth != null
        
        if (!needOrderbook) {
            // 不需要订单簿，直接通过
            return FilterResult.passed()
        }
        
        // 3. 获取订单簿（仅在需要时，只请求一次）
        val orderbookResult = clobService.getOrderbookByTokenId(tokenId)
        if (!orderbookResult.isSuccess) {
            val error = orderbookResult.exceptionOrNull()
            return FilterResult.orderbookError("获取订单簿失败: ${error?.message ?: "未知错误"}")
        }
        
        val orderbook = orderbookResult.getOrNull()
            ?: return FilterResult.orderbookEmpty()
        
        // 4. 买一卖一价差过滤（如果配置了）
        if (copyTrading.maxSpread != null) {
            val spreadCheck = checkSpread(copyTrading, orderbook)
            if (!spreadCheck.isPassed) {
                return FilterResult.spreadFailed(spreadCheck.reason, orderbook)
            }
        }
        
        // 5. 订单深度过滤（如果配置了，检查所有方向）
        if (copyTrading.minOrderDepth != null) {
            val depthCheck = checkOrderDepth(copyTrading, orderbook)
            if (!depthCheck.isPassed) {
                return FilterResult.orderDepthFailed(depthCheck.reason, orderbook)
            }
        }
        
        // 6. 仓位检查（如果配置了最大仓位限制且提供了跟单金额和市场ID）
        if (copyOrderAmount != null && marketId != null) {
            val positionCheck = checkPositionLimits(copyTrading, copyOrderAmount, marketId)
            if (!positionCheck.isPassed) {
                return positionCheck
            }
        }
        
        return FilterResult.passed(orderbook)
    }
    
    /**
     * 检查价格区间
     * @param copyTrading 跟单配置
     * @param tradePrice Leader 交易价格
     * @return 过滤结果
     */
    private fun checkPriceRange(
        copyTrading: CopyTrading,
        tradePrice: BigDecimal
    ): FilterResult {
        // 如果未配置价格区间，直接通过
        if (copyTrading.minPrice == null && copyTrading.maxPrice == null) {
            return FilterResult.passed()
        }
        
        // 检查最低价格
        if (copyTrading.minPrice != null && tradePrice.lt(copyTrading.minPrice)) {
            return FilterResult.priceRangeFailed("价格低于最低限制: $tradePrice < ${copyTrading.minPrice}")
        }
        
        // 检查最高价格
        if (copyTrading.maxPrice != null && tradePrice.gt(copyTrading.maxPrice)) {
            return FilterResult.priceRangeFailed("价格高于最高限制: $tradePrice > ${copyTrading.maxPrice}")
        }
        
        return FilterResult.passed()
    }
    
    /**
     * 检查买一卖一价差
     * bestBid: 买盘中的最高价格（最大值）
     * bestAsk: 卖盘中的最低价格（最小值）
     */
    private fun checkSpread(
        copyTrading: CopyTrading,
        orderbook: OrderbookResponse
    ): FilterResult {
        // 如果未启用价差过滤，直接通过
        if (copyTrading.maxSpread == null) {
            return FilterResult.passed()
        }
        
        // 获取买盘中的最高价格（bestBid = bids 中的最大值）
        val bestBid = orderbook.bids
            .mapNotNull { it.price.toSafeBigDecimal() }
            .maxOrNull()
        
        // 获取卖盘中的最低价格（bestAsk = asks 中的最小值）
        val bestAsk = orderbook.asks
            .mapNotNull { it.price.toSafeBigDecimal() }
            .minOrNull()
        
        if (bestBid == null || bestAsk == null) {
            return FilterResult.spreadFailed("订单簿缺少买一或卖一价格", orderbook)
        }
        
        // 计算价差（绝对价格）
        val spread = bestAsk.subtract(bestBid)
        
        if (spread.gt(copyTrading.maxSpread)) {
            return FilterResult.spreadFailed("价差过大: $spread > ${copyTrading.maxSpread}", orderbook)
        }
        
        return FilterResult.passed()
    }
    
    /**
     * 检查订单深度（检查所有方向：买盘和卖盘的总深度）
     */
    private fun checkOrderDepth(
        copyTrading: CopyTrading,
        orderbook: OrderbookResponse
    ): FilterResult {
        // 如果未启用订单深度过滤，直接通过
        if (copyTrading.minOrderDepth == null) {
            return FilterResult.passed()
        }
        
        // 计算买盘（bids）总深度
        var bidsDepth = BigDecimal.ZERO
        for (order in orderbook.bids) {
            val price = order.price.toSafeBigDecimal()
            val size = order.size.toSafeBigDecimal()
            val orderAmount = price.multi(size)
            bidsDepth = bidsDepth.add(orderAmount)
        }
        
        // 计算卖盘（asks）总深度
        var asksDepth = BigDecimal.ZERO
        for (order in orderbook.asks) {
            val price = order.price.toSafeBigDecimal()
            val size = order.size.toSafeBigDecimal()
            val orderAmount = price.multi(size)
            asksDepth = asksDepth.add(orderAmount)
        }
        
        // 计算总深度（买盘 + 卖盘）
        val totalDepth = bidsDepth.add(asksDepth)
        
        if (totalDepth.lt(copyTrading.minOrderDepth)) {
            return FilterResult.orderDepthFailed("订单深度不足: $totalDepth < ${copyTrading.minOrderDepth}", orderbook)
        }
        
        return FilterResult.passed()
    }
    
    /**
     * 检查仓位限制（按市场检查）
     * @param copyTrading 跟单配置
     * @param copyOrderAmount 跟单金额（USDC）
     * @param marketId 市场ID，用于过滤该市场的仓位
     * @return 过滤结果
     */
    private suspend fun checkPositionLimits(
        copyTrading: CopyTrading,
        copyOrderAmount: BigDecimal,
        marketId: String
    ): FilterResult {
        // 如果未配置仓位限制，直接通过
        if (copyTrading.maxPositionValue == null && copyTrading.maxPositionCount == null) {
            return FilterResult.passed()
        }
        
        try {
            // 获取账户的所有仓位信息
            val positionsResult = accountService.getAllPositions()
            if (positionsResult.isFailure) {
                logger.warn("获取仓位信息失败，跳过仓位检查: accountId=${copyTrading.accountId}, marketId=$marketId, error=${positionsResult.exceptionOrNull()?.message}")
                // 如果获取仓位失败，为了安全起见，不通过检查
                return FilterResult.maxPositionValueFailed("获取仓位信息失败，无法进行仓位检查")
            }
            
            val positions = positionsResult.getOrNull() ?: return FilterResult.maxPositionValueFailed("仓位信息为空")
            
            // 过滤出当前账户且该市场的仓位
            val marketPositions = positions.currentPositions.filter { 
                it.accountId == copyTrading.accountId && it.marketId == marketId
            }
            
            // 检查最大仓位金额（如果配置了）
            if (copyTrading.maxPositionValue != null) {
                // 计算该市场的当前仓位总价值（累加该市场所有仓位的 currentValue）
                val currentPositionValue = marketPositions.sumOf { position ->
                    position.currentValue.toSafeBigDecimal()
                }
                
                // 检查：该市场的当前仓位 + 跟单金额 <= 最大仓位金额
                val totalValueAfterOrder = currentPositionValue.add(copyOrderAmount)
                
                if (totalValueAfterOrder.gt(copyTrading.maxPositionValue)) {
                    return FilterResult.maxPositionValueFailed(
                        "超过最大仓位金额限制: 当前该市场仓位=${currentPositionValue} USDC, 跟单金额=${copyOrderAmount} USDC, 总计=${totalValueAfterOrder} USDC > 最大限制=${copyTrading.maxPositionValue} USDC"
                    )
                }
            }
            
            // 检查最大仓位数量（如果配置了）
            if (copyTrading.maxPositionCount != null) {
                // 计算该市场的当前仓位数量（该市场不同方向的仓位算不同仓位）
                val currentPositionCount = marketPositions.size
                
                // 检查：该市场的当前仓位数量 <= 最大仓位数量
                // 注意：如果该市场已有仓位，跟单可能会增加新的仓位（不同方向）或增加现有仓位
                // 为了简化，我们检查当前该市场的仓位数量是否已经达到或超过限制
                if (currentPositionCount >= copyTrading.maxPositionCount) {
                    return FilterResult.maxPositionCountFailed(
                        "超过最大仓位数量限制: 当前该市场仓位数量=${currentPositionCount} >= 最大限制=${copyTrading.maxPositionCount}"
                    )
                }
            }
            
            return FilterResult.passed()
        } catch (e: Exception) {
            logger.error("仓位检查异常: accountId=${copyTrading.accountId}, marketId=$marketId, error=${e.message}", e)
            // 如果检查异常，为了安全起见，不通过检查
            return FilterResult.maxPositionValueFailed("仓位检查异常: ${e.message}")
        }
    }
}

