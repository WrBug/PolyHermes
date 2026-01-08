package com.wrbug.polymarketbot.service.copytrading.configs

import com.wrbug.polymarketbot.api.OrderbookResponse
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.lt
import com.wrbug.polymarketbot.util.multi
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.wrbug.polymarketbot.util.JsonUtils
import org.slf4j.LoggerFactory
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.accounts.AccountService
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * 跟单过滤条件检查服务
 */
@Service
class CopyTradingFilterService(
    private val clobService: PolymarketClobService,
    private val accountService: AccountService,
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val jsonUtils: JsonUtils
) {
    
    private val logger = LoggerFactory.getLogger(CopyTradingFilterService::class.java)
    
    /**
     * 检查过滤条件
     * @param copyTrading 跟单配置
     * @param tokenId token ID（用于获取订单簿）
     * @param tradePrice Leader 交易价格，用于价格区间检查
     * @param copyOrderAmount 跟单金额（USDC），用于仓位检查，如果为null则不进行仓位检查
     * @param marketId 市场ID，用于仓位检查（按市场过滤仓位）
     * @param marketTitle 市场标题，用于关键字过滤
     * @return 过滤结果
     */
    suspend fun checkFilters(
        copyTrading: CopyTrading,
        tokenId: String,
        tradePrice: BigDecimal? = null,  // Leader 交易价格，用于价格区间检查
        copyOrderAmount: BigDecimal? = null,  // 跟单金额（USDC），用于仓位检查
        marketId: String? = null,  // 市场ID，用于仓位检查（按市场过滤仓位）
        marketTitle: String? = null  // 市场标题，用于关键字过滤
    ): FilterResult {
        // 1. 关键字过滤检查（如果配置了关键字过滤）
        if (copyTrading.keywordFilterMode != null && copyTrading.keywordFilterMode != "DISABLED") {
            val keywordCheck = checkKeywordFilter(copyTrading, marketTitle)
            if (!keywordCheck.isPassed) {
                return keywordCheck
            }
        }
        
        // 2. 价格区间检查（如果配置了价格区间）
        if (tradePrice != null) {
            val priceRangeCheck = checkPriceRange(copyTrading, tradePrice)
            if (!priceRangeCheck.isPassed) {
                return FilterResult.priceRangeFailed(priceRangeCheck.reason)
            }
        }
        
        // 3. 检查是否需要获取订单簿
        // 只有在配置了需要订单簿的过滤条件时才获取
        val needOrderbook = copyTrading.maxSpread != null || copyTrading.minOrderDepth != null
        
        if (!needOrderbook) {
            // 不需要订单簿，直接通过
            return FilterResult.passed()
        }
        
        // 4. 获取订单簿（仅在需要时，只请求一次）
        val orderbookResult = clobService.getOrderbookByTokenId(tokenId)
        if (!orderbookResult.isSuccess) {
            val error = orderbookResult.exceptionOrNull()
            return FilterResult.orderbookError("获取订单簿失败: ${error?.message ?: "未知错误"}")
        }
        
        val orderbook = orderbookResult.getOrNull()
            ?: return FilterResult.orderbookEmpty()
        
        // 5. 买一卖一价差过滤（如果配置了）
        if (copyTrading.maxSpread != null) {
            val spreadCheck = checkSpread(copyTrading, orderbook)
            if (!spreadCheck.isPassed) {
                return FilterResult.spreadFailed(spreadCheck.reason, orderbook)
            }
        }
        
        // 6. 订单深度过滤（如果配置了，检查所有方向）
        if (copyTrading.minOrderDepth != null) {
            val depthCheck = checkOrderDepth(copyTrading, orderbook)
            if (!depthCheck.isPassed) {
                return FilterResult.orderDepthFailed(depthCheck.reason, orderbook)
            }
        }
        
        // 7. 仓位检查（如果配置了最大仓位限制且提供了跟单金额和市场ID）
        if (copyOrderAmount != null && marketId != null) {
            val positionCheck = checkPositionLimits(copyTrading, copyOrderAmount, marketId)
            if (!positionCheck.isPassed) {
                return positionCheck
            }
        }
        
        return FilterResult.passed(orderbook)
    }
    
    /**
     * 检查关键字过滤
     * @param copyTrading 跟单配置
     * @param marketTitle 市场标题
     * @return 过滤结果
     */
    private fun checkKeywordFilter(
        copyTrading: CopyTrading,
        marketTitle: String?
    ): FilterResult {
        // 如果未启用关键字过滤，直接通过
        if (copyTrading.keywordFilterMode == null || copyTrading.keywordFilterMode == "DISABLED") {
            return FilterResult.passed()
        }
        
        // 如果没有市场标题，无法进行关键字过滤，为了安全起见，不通过
        if (marketTitle.isNullOrBlank()) {
            return FilterResult.keywordFilterFailed("市场标题为空，无法进行关键字过滤")
        }
        
        // 解析关键字列表
        val keywords = jsonUtils.parseStringArray(copyTrading.keywords)
        if (keywords.isEmpty()) {
            // 如果关键字列表为空，白名单模式不通过，黑名单模式通过
            return if (copyTrading.keywordFilterMode == "WHITELIST") {
                FilterResult.keywordFilterFailed("白名单模式但关键字列表为空")
            } else {
                FilterResult.passed()
            }
        }
        
        // 将市场标题转换为小写，用于不区分大小写的匹配
        val titleLower = marketTitle.lowercase()
        
        // 检查市场标题是否包含任意关键字
        val containsKeyword = keywords.any { keyword ->
            titleLower.contains(keyword.lowercase())
        }
        
        // 根据过滤模式决定是否通过
        return when (copyTrading.keywordFilterMode) {
            "WHITELIST" -> {
                if (containsKeyword) {
                    FilterResult.passed()
                } else {
                    FilterResult.keywordFilterFailed("白名单模式：市场标题不包含任何关键字。市场标题：$marketTitle，关键字列表：${keywords.joinToString(", ")}")
                }
            }
            "BLACKLIST" -> {
                if (containsKeyword) {
                    FilterResult.keywordFilterFailed("黑名单模式：市场标题包含关键字。市场标题：$marketTitle，匹配的关键字：${keywords.filter { titleLower.contains(it.lowercase()) }.joinToString(", ")}")
                } else {
                    FilterResult.passed()
                }
            }
            else -> FilterResult.passed()
        }
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
                // 比较数据库成本价（本地订单记录）和外部持仓市值（可能来自其他终端的操作），取最大值
                val dbValue = copyOrderTrackingRepository.sumCurrentPositionValueByMarket(copyTrading.id!!, marketId) ?: BigDecimal.ZERO
                val extValue = marketPositions.sumOf { it.currentValue.toSafeBigDecimal() }
                val currentPositionValue = dbValue.max(extValue)
                
                // 检查：该市场的当前仓位 + 跟单金额 <= 最大仓位金额
                val totalValueAfterOrder = currentPositionValue.add(copyOrderAmount)
                
                if (totalValueAfterOrder.gt(copyTrading.maxPositionValue)) {
                    return FilterResult.maxPositionValueFailed(
                        "超过最大仓位金额限制: 当前该市场仓位(取最大值)=${currentPositionValue} USDC (DB=${dbValue}, Ext=${extValue}), 跟单金额=${copyOrderAmount} USDC, 总计=${totalValueAfterOrder} USDC > 最大限制=${copyTrading.maxPositionValue} USDC"
                    )
                }
            }
            
            // 检查最大仓位数量（如果配置了）
            if (copyTrading.maxPositionCount != null) {
                // 使用数据库中的订单记录计算活跃仓位数量（解决延迟问题）
                val dbCount = copyOrderTrackingRepository.countActivePositions(copyTrading.id!!)
                
                // 计算外部持仓中的唯一市场数量（防止遗漏非本项目创建的仓位）
                val extCount = positions.currentPositions
                    .filter { it.accountId == copyTrading.accountId }
                    .map { it.marketId }
                    .distinct()
                    .size
                
                val currentPositionCount = maxOf(dbCount, extCount)
                
                // 检查：如果当前没有该市场的活跃仓位，且总仓位数量已达到限制，则不允许开新仓
                // 判断当前市场是否已有活跃仓位（数据库或外部持仓）
                val hasDbPosition = copyOrderTrackingRepository.existsByCopyTradingIdAndMarketIdAndRemainingQuantityGreaterThan(
                    copyTrading.id, marketId, BigDecimal.ZERO
                )
                val hasExtPosition = marketPositions.isNotEmpty()
                val hasCurrentMarketPosition = hasDbPosition || hasExtPosition
                
                if (!hasCurrentMarketPosition && currentPositionCount >= copyTrading.maxPositionCount) {
                    return FilterResult.maxPositionCountFailed(
                        "超过最大仓位数量限制: 当前活跃仓位总数(取最大值)=${currentPositionCount} (DB=${dbCount}, Ext=${extCount}) >= 最大限制=${copyTrading.maxPositionCount}"
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

