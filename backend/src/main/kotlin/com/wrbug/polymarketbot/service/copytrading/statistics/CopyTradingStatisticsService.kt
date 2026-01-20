package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.*
import com.wrbug.polymarketbot.repository.*
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.wrbug.polymarketbot.util.multi
import com.wrbug.polymarketbot.util.div
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.eq
import com.wrbug.polymarketbot.util.lte
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import com.wrbug.polymarketbot.service.accounts.AccountService
import com.wrbug.polymarketbot.service.common.BlockchainService
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 跟单统计服务
 * 提供统计信息和订单列表查询
 */
@Service
class CopyTradingStatisticsService(
    private val copyTradingRepository: CopyTradingRepository,
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val sellMatchRecordRepository: SellMatchRecordRepository,
    private val sellMatchDetailRepository: SellMatchDetailRepository,
    private val accountRepository: AccountRepository,
    private val leaderRepository: LeaderRepository,
    private val marketService: com.wrbug.polymarketbot.service.common.MarketService
) {
    
    private val logger = LoggerFactory.getLogger(CopyTradingStatisticsService::class.java)
    
    /**
     * 获取跟单关系统计
     */
    suspend fun getStatistics(copyTradingId: Long): Result<CopyTradingStatisticsResponse> {
        return try {
            // 1. 获取跟单关系
            val copyTrading = copyTradingRepository.findById(copyTradingId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("跟单关系不存在: $copyTradingId"))
            
            // 2. 获取关联信息
            val account = accountRepository.findById(copyTrading.accountId).orElse(null)
            val leader = leaderRepository.findById(copyTrading.leaderId).orElse(null)
            
            // 3. 获取买入订单
            val buyOrders = copyOrderTrackingRepository.findByCopyTradingId(copyTradingId)
            
            // 4. 获取卖出记录
            val sellRecords = sellMatchRecordRepository.findByCopyTradingId(copyTradingId)
            
            // 5. 获取匹配明细
            val matchDetails = sellMatchDetailRepository.findByCopyTradingId(copyTradingId)
            
            // 6. 计算统计信息
            val statistics = calculateStatistics(buyOrders, sellRecords, matchDetails)
            
            // 7. 不再计算未实现盈亏和持仓价值（优化性能）
            // 未实现盈亏计算需要查询链上持仓和市场价格，性能开销大
            val unrealizedPnl = "0"
            val positionValue = "0"
            
            // 8. 构建响应（总盈亏 = 已实现盈亏）
            val response = CopyTradingStatisticsResponse(
                copyTradingId = copyTradingId,
                accountId = copyTrading.accountId,
                accountName = account?.accountName,
                leaderId = copyTrading.leaderId,
                leaderName = leader?.leaderName,
                enabled = copyTrading.enabled,
                totalBuyQuantity = statistics.totalBuyQuantity,
                totalBuyOrders = statistics.totalBuyOrders,
                totalBuyAmount = statistics.totalBuyAmount,
                avgBuyPrice = statistics.avgBuyPrice,
                totalSellQuantity = statistics.totalSellQuantity,
                totalSellOrders = statistics.totalSellOrders,
                totalSellAmount = statistics.totalSellAmount,
                currentPositionQuantity = statistics.currentPositionQuantity,
                currentPositionValue = positionValue,
                totalRealizedPnl = statistics.totalRealizedPnl,
                totalUnrealizedPnl = unrealizedPnl,
                totalPnl = statistics.totalRealizedPnl,
                totalPnlPercent = calculatePnlPercentOnlyRealized(statistics.totalBuyAmount, statistics.totalRealizedPnl)
            )
            
            Result.success(response)
        } catch (e: Exception) {
            logger.error("获取统计信息失败: copyTradingId=$copyTradingId", e)
            Result.failure(e)
        }
    }
    
    /**
     * 查询订单列表
     */
    fun getOrderList(request: OrderTrackingRequest): Result<OrderListResponse> {
        return try {
            // 1. 验证跟单关系
            copyTradingRepository.findById(request.copyTradingId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("跟单关系不存在: ${request.copyTradingId}"))
            
            // 2. 根据类型查询
            val (list, total) = when (request.type.lowercase()) {
                "buy" -> getBuyOrderList(request)
                "sell" -> getSellOrderList(request)
                "matched" -> getMatchedOrderList(request)
                else -> return Result.failure(IllegalArgumentException("不支持的订单类型: ${request.type}"))
            }
            
            // 3. 构建响应
            val response = OrderListResponse(
                list = list,
                total = total,
                page = request.page ?: 1,
                limit = request.limit ?: 20
            )
            
            Result.success(response)
        } catch (e: Exception) {
            logger.error("查询订单列表失败: ${request.copyTradingId}, type=${request.type}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 获取买入订单列表
     */
    private fun getBuyOrderList(request: OrderTrackingRequest): Pair<List<BuyOrderInfo>, Long> {
        var orders = copyOrderTrackingRepository.findByCopyTradingId(request.copyTradingId)
        
        // 批量获取市场信息（用于筛选）
        val allMarketIds = orders.map { it.marketId }.distinct()
        val markets = marketService.getMarkets(allMarketIds)
        
        // 筛选
        if (!request.marketId.isNullOrBlank()) {
            // marketId 支持模糊匹配
            orders = orders.filter { it.marketId.contains(request.marketId!!, ignoreCase = true) }
        }
        if (!request.marketTitle.isNullOrBlank()) {
            // marketTitle 关键字筛选
            orders = orders.filter { order ->
                val market = markets[order.marketId]
                market?.title?.contains(request.marketTitle!!, ignoreCase = true) == true
            }
        }
        if (!request.status.isNullOrBlank()) {
            orders = orders.filter { it.status == request.status }
        }
        
        val total = orders.size.toLong()
        
        // 排序（按创建时间倒序）
        orders = orders.sortedByDescending { it.createdAt }
        
        // 分页
        val page = (request.page ?: 1) - 1
        val limit = request.limit ?: 20
        val start = page * limit
        val end = minOf(start + limit, orders.size)
        val pagedOrders = if (start < orders.size) orders.subList(start, end) else emptyList()
        
        // 转换为DTO
        val list = pagedOrders.map { order ->
            val amount = order.quantity.toSafeBigDecimal().multi(order.price)
            val market = markets[order.marketId]
            BuyOrderInfo(
                orderId = order.buyOrderId,
                leaderTradeId = order.leaderBuyTradeId,
                marketId = order.marketId,
                marketTitle = market?.title,
                marketSlug = market?.slug,  // 显示用的 slug
                eventSlug = market?.eventSlug,  // 跳转用的 slug（从数据库读取）
                marketCategory = market?.category,
                side = order.side,
                quantity = order.quantity.toString(),
                price = order.price.toString(),
                amount = amount.toString(),
                matchedQuantity = order.matchedQuantity.toString(),
                remainingQuantity = order.remainingQuantity.toString(),
                status = order.status,
                createdAt = order.createdAt
            )
        }
        
        return Pair(list, total)
    }
    
    /**
     * 获取卖出订单列表
     */
    private fun getSellOrderList(request: OrderTrackingRequest): Pair<List<SellOrderInfo>, Long> {
        var records = sellMatchRecordRepository.findByCopyTradingId(request.copyTradingId)
        
        // 批量获取市场信息（用于筛选）
        val allMarketIds = records.map { it.marketId }.distinct()
        val markets = marketService.getMarkets(allMarketIds)
        
        // 筛选
        if (!request.marketId.isNullOrBlank()) {
            // marketId 支持模糊匹配
            records = records.filter { it.marketId.contains(request.marketId!!, ignoreCase = true) }
        }
        if (!request.marketTitle.isNullOrBlank()) {
            // marketTitle 关键字筛选
            records = records.filter { record ->
                val market = markets[record.marketId]
                market?.title?.contains(request.marketTitle!!, ignoreCase = true) == true
            }
        }
        
        val total = records.size.toLong()
        
        // 排序（按创建时间倒序）
        records = records.sortedByDescending { it.createdAt }
        
        // 分页
        val page = (request.page ?: 1) - 1
        val limit = request.limit ?: 20
        val start = page * limit
        val end = minOf(start + limit, records.size)
        val pagedRecords = if (start < records.size) records.subList(start, end) else emptyList()
        
        // 转换为DTO
        val list = pagedRecords.map { record ->
            val amount = record.totalMatchedQuantity.toSafeBigDecimal().multi(record.sellPrice)
            val market = markets[record.marketId]
            SellOrderInfo(
                orderId = record.sellOrderId,
                leaderTradeId = record.leaderSellTradeId,
                marketId = record.marketId,
                marketTitle = market?.title,
                marketSlug = market?.slug,  // 显示用的 slug
                eventSlug = market?.eventSlug,  // 跳转用的 slug（从数据库读取）
                marketCategory = market?.category,
                side = record.side,
                quantity = record.totalMatchedQuantity.toString(),
                price = record.sellPrice.toString(),
                amount = amount.toString(),
                realizedPnl = record.totalRealizedPnl.toString(),
                createdAt = record.createdAt
            )
        }
        
        return Pair(list, total)
    }
    
    /**
     * 获取匹配订单列表
     */
    private fun getMatchedOrderList(request: OrderTrackingRequest): Pair<List<MatchedOrderInfo>, Long> {
        val matchDetails = sellMatchDetailRepository.findByCopyTradingId(request.copyTradingId)
        
        // 获取所有相关的卖出记录（用于筛选）
        val matchRecordIds = matchDetails.map { it.matchRecordId }.distinct()
        val matchRecords = matchRecordIds.mapNotNull { id ->
            sellMatchRecordRepository.findById(id).orElse(null)
        }
        val marketIds = matchRecords.map { it.marketId }.distinct()
        val markets = marketService.getMarkets(marketIds)
        
        // 筛选
        var filtered = matchDetails
        if (!request.sellOrderId.isNullOrBlank()) {
            val sellRecord = sellMatchRecordRepository.findBySellOrderId(request.sellOrderId)
            if (sellRecord != null) {
                filtered = filtered.filter { it.matchRecordId == sellRecord.id }
            } else {
                filtered = emptyList()
            }
        }
        if (!request.buyOrderId.isNullOrBlank()) {
            filtered = filtered.filter { it.buyOrderId == request.buyOrderId }
        }
        if (!request.marketId.isNullOrBlank()) {
            // marketId 支持模糊匹配
            filtered = filtered.filter { detail ->
                val matchRecord = matchRecords.find { it.id == detail.matchRecordId }
                matchRecord?.marketId?.contains(request.marketId!!, ignoreCase = true) == true
            }
        }
        if (!request.marketTitle.isNullOrBlank()) {
            // marketTitle 关键字筛选
            filtered = filtered.filter { detail ->
                val matchRecord = matchRecords.find { it.id == detail.matchRecordId }
                val market = matchRecord?.let { markets[it.marketId] }
                market?.title?.contains(request.marketTitle!!, ignoreCase = true) == true
            }
        }
        
        val total = filtered.size.toLong()
        
        // 排序（按创建时间倒序）
        filtered = filtered.sortedByDescending { it.createdAt }
        
        // 分页
        val page = (request.page ?: 1) - 1
        val limit = request.limit ?: 20
        val start = page * limit
        val end = minOf(start + limit, filtered.size)
        val pagedDetails = if (start < filtered.size) filtered.subList(start, end) else emptyList()
        
        // 获取匹配记录以获取市场ID
        val pagedMatchRecordIds = pagedDetails.map { it.matchRecordId }.distinct()
        val pagedMatchRecords = pagedMatchRecordIds.mapNotNull { id ->
            sellMatchRecordRepository.findById(id).orElse(null)
        }
        val pagedMarketIds = pagedMatchRecords.map { it.marketId }.distinct()
        val pagedMarkets = marketService.getMarkets(pagedMarketIds)
        
        // 转换为DTO
        val list = pagedDetails.map { detail ->
            val matchRecord = pagedMatchRecords.find { it.id == detail.matchRecordId }
            val market = matchRecord?.let { pagedMarkets[it.marketId] }
            MatchedOrderInfo(
                sellOrderId = matchRecord?.sellOrderId ?: "",
                buyOrderId = detail.buyOrderId,
                marketId = matchRecord?.marketId,
                marketTitle = market?.title,
                marketSlug = market?.slug,  // 显示用的 slug
                eventSlug = market?.eventSlug,  // 跳转用的 slug（从数据库读取）
                marketCategory = market?.category,
                matchedQuantity = detail.matchedQuantity.toString(),
                buyPrice = detail.buyPrice.toString(),
                sellPrice = detail.sellPrice.toString(),
                realizedPnl = detail.realizedPnl.toString(),
                matchedAt = detail.createdAt
            )
        }
        
        return Pair(list, total)
    }
    
    /**
     * 计算统计信息
     */
    private fun calculateStatistics(
        buyOrders: List<CopyOrderTracking>,
        sellRecords: List<SellMatchRecord>,
        matchDetails: List<SellMatchDetail>
    ): StatisticsData {
        // 买入统计
        val totalBuyQuantity = buyOrders.sumOf { it.quantity.toSafeBigDecimal() }
        val totalBuyAmount = buyOrders.sumOf { it.quantity.toSafeBigDecimal().multi(it.price) }
        val totalBuyOrders = buyOrders.size.toLong()
        val avgBuyPrice = if (totalBuyQuantity.gt(BigDecimal.ZERO)) {
            totalBuyAmount.div(totalBuyQuantity)
        } else {
            BigDecimal.ZERO
        }
        
        // 卖出统计
        // 使用 SellMatchDetail 计算总卖出金额，确保准确性
        // 因为每个明细都记录了准确的匹配数量和卖出价格
        val totalSellQuantity = sellRecords.sumOf { it.totalMatchedQuantity.toSafeBigDecimal() }
        val totalSellAmount = matchDetails.sumOf { it.matchedQuantity.toSafeBigDecimal().multi(it.sellPrice) }
        val totalSellOrders = sellRecords.size.toLong()
        
        // 持仓统计
        val currentPositionQuantity = buyOrders.sumOf { it.remainingQuantity.toSafeBigDecimal() }
        
        // 已实现盈亏
        val totalRealizedPnl = matchDetails.sumOf { it.realizedPnl.toSafeBigDecimal() }
        
        return StatisticsData(
            totalBuyQuantity = totalBuyQuantity.toString(),
            totalBuyOrders = totalBuyOrders,
            totalBuyAmount = totalBuyAmount.toString(),
            avgBuyPrice = avgBuyPrice.toString(),
            totalSellQuantity = totalSellQuantity.toString(),
            totalSellOrders = totalSellOrders,
            totalSellAmount = totalSellAmount.toString(),
            currentPositionQuantity = currentPositionQuantity.toString(),
            totalRealizedPnl = totalRealizedPnl.toString()
        )
    }
    
    /**
     * 计算盈亏百分比（仅基于已实现盈亏）
     */
    private fun calculatePnlPercentOnlyRealized(
        totalBuyAmount: String,
        totalRealizedPnl: String
    ): String {
        val buyAmount = totalBuyAmount.toSafeBigDecimal()
        if (buyAmount.lte(BigDecimal.ZERO)) return "0"
        
        val percent = totalRealizedPnl.toSafeBigDecimal().div(buyAmount).multi(100)
        
        return percent.setScale(2, RoundingMode.HALF_UP).toString()
    }
    
    /**
     * 获取全局统计
     */
    suspend fun getGlobalStatistics(startTime: Long? = null, endTime: Long? = null): Result<StatisticsResponse> {
        return try {
            // 获取所有跟单关系
            val allCopyTradings = copyTradingRepository.findAll()
            
            // 计算统计信息
            val statistics = calculateAggregateStatistics(allCopyTradings.map { it.id!! }, startTime, endTime)
            
            Result.success(statistics)
        } catch (e: Exception) {
            logger.error("获取全局统计失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 获取 Leader 统计
     */
    suspend fun getLeaderStatistics(leaderId: Long, startTime: Long? = null, endTime: Long? = null): Result<StatisticsResponse> {
        return try {
            // 获取该 Leader 的所有跟单关系
            val copyTradings = copyTradingRepository.findByLeaderId(leaderId)
            
            if (copyTradings.isEmpty()) {
                return Result.failure(IllegalArgumentException("Leader $leaderId 没有跟单关系"))
            }
            
            // 计算统计信息
            val statistics = calculateAggregateStatistics(copyTradings.map { it.id!! }, startTime, endTime)
            
            Result.success(statistics)
        } catch (e: Exception) {
            logger.error("获取 Leader 统计失败: leaderId=$leaderId", e)
            Result.failure(e)
        }
    }
    
    /**
     * 获取分类统计
     */
    suspend fun getCategoryStatistics(category: String, startTime: Long? = null, endTime: Long? = null): Result<StatisticsResponse> {
        return try {
            // 验证分类
            if (category != "sports" && category != "crypto") {
                return Result.failure(IllegalArgumentException("分类必须是 sports 或 crypto"))
            }
            
            // 获取该分类的所有 Leader
            val leaders = leaderRepository.findAll().filter { it.category == category }
            
            if (leaders.isEmpty()) {
                return Result.failure(IllegalArgumentException("分类 $category 没有 Leader"))
            }
            
            // 获取这些 Leader 的所有跟单关系
            val leaderIds = leaders.mapNotNull { it.id }
            val copyTradings = copyTradingRepository.findAll().filter { it.leaderId in leaderIds }
            
            if (copyTradings.isEmpty()) {
                return Result.failure(IllegalArgumentException("分类 $category 没有跟单关系"))
            }
            
            // 计算统计信息
            val statistics = calculateAggregateStatistics(copyTradings.map { it.id!! }, startTime, endTime)
            
            Result.success(statistics)
        } catch (e: Exception) {
            logger.error("获取分类统计失败: category=$category", e)
            Result.failure(e)
        }
    }
    
    /**
     * 计算聚合统计信息（多个跟单关系的汇总）
     */
    private suspend fun calculateAggregateStatistics(
        copyTradingIds: List<Long>,
        startTime: Long?,
        endTime: Long?
    ): StatisticsResponse {
        // 获取所有买入订单
        val allBuyOrders = copyTradingIds.flatMap { copyOrderTrackingRepository.findByCopyTradingId(it) }
            .filter { order ->
                // 时间筛选
                when {
                    startTime != null && endTime != null -> order.createdAt >= startTime && order.createdAt <= endTime
                    startTime != null -> order.createdAt >= startTime
                    endTime != null -> order.createdAt <= endTime
                    else -> true
                }
            }
        
        // 获取所有匹配明细（已实现盈亏）
        val allMatchDetails = copyTradingIds.flatMap { sellMatchDetailRepository.findByCopyTradingId(it) }
            .filter { detail ->
                // 时间筛选
                when {
                    startTime != null && endTime != null -> detail.createdAt >= startTime && detail.createdAt <= endTime
                    startTime != null -> detail.createdAt >= startTime
                    endTime != null -> detail.createdAt <= endTime
                    else -> true
                }
            }
        
        // 计算统计指标
        val totalOrders = allBuyOrders.size.toLong()
        val totalPnl = allMatchDetails.sumOf { it.realizedPnl.toSafeBigDecimal() }
        
        // 计算胜率：盈利订单数 / 总订单数
        // 盈利订单：该订单的所有匹配明细的盈亏总和 > 0
        val profitableOrders = allBuyOrders.count { buyOrder ->
            val orderPnl = allMatchDetails
                .filter { it.buyOrderId == buyOrder.buyOrderId }
                .sumOf { it.realizedPnl.toSafeBigDecimal() }
            orderPnl.gt(BigDecimal.ZERO)
        }
        val winRate = if (totalOrders > 0) {
            (BigDecimal(profitableOrders).divide(BigDecimal(totalOrders), 4, RoundingMode.HALF_UP) * BigDecimal(100))
                .setScale(2, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }
        
        // 平均盈亏
        val avgPnl = if (totalOrders > 0) {
            totalPnl.divide(BigDecimal(totalOrders), 8, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }
        
        // 最大盈利和最大亏损（按订单计算）
        var maxProfit = BigDecimal.ZERO
        var maxLoss = BigDecimal.ZERO
        
        allBuyOrders.forEach { buyOrder ->
            val orderPnl = allMatchDetails
                .filter { it.buyOrderId == buyOrder.buyOrderId }
                .sumOf { it.realizedPnl.toSafeBigDecimal() }
            
            if (orderPnl.gt(maxProfit)) {
                maxProfit = orderPnl
            }
            if (orderPnl < maxLoss) {
                maxLoss = orderPnl
            }
        }
        
        return StatisticsResponse(
            totalOrders = totalOrders,
            totalPnl = totalPnl.toString(),
            winRate = winRate.toString(),
            avgPnl = avgPnl.toString(),
            maxProfit = maxProfit.toString(),
            maxLoss = maxLoss.toString()
        )
    }
    
    /**
     * 统计数据结构
     */
    private data class StatisticsData(
        val totalBuyQuantity: String,
        val totalBuyOrders: Long,
        val totalBuyAmount: String,
        val avgBuyPrice: String,
        val totalSellQuantity: String,
        val totalSellOrders: Long,
        val totalSellAmount: String,
        val currentPositionQuantity: String,
        val totalRealizedPnl: String
    )
    
    /**
     * 获取按市场分组的买入订单列表
     */
    fun getBuyOrderListGroupedByMarket(request: MarketGroupedOrdersRequest): Result<MarketGroupedOrdersResponse> {
        return try {
            // 1. 验证跟单关系
            copyTradingRepository.findById(request.copyTradingId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("跟单关系不存在: ${request.copyTradingId}"))
            
            // 2. 获取所有买入订单
            var orders = copyOrderTrackingRepository.findByCopyTradingId(request.copyTradingId)
            
            // 3. 批量获取市场信息（用于筛选）
            val allMarketIds = orders.map { it.marketId }.distinct()
            val markets = marketService.getMarkets(allMarketIds)
            
            // 4. 筛选
            if (!request.marketId.isNullOrBlank()) {
                // marketId 支持模糊匹配
                orders = orders.filter { it.marketId.contains(request.marketId!!, ignoreCase = true) }
            }
            if (!request.marketTitle.isNullOrBlank()) {
                // marketTitle 关键字筛选
                orders = orders.filter { order ->
                    val market = markets[order.marketId]
                    market?.title?.contains(request.marketTitle!!, ignoreCase = true) == true
                }
            }
            // 5. 按市场ID分组
            val groups = mutableMapOf<String, MutableList<CopyOrderTracking>>()
            orders.forEach { order ->
                val marketId = order.marketId
                if (!groups.containsKey(marketId)) {
                    groups[marketId] = mutableListOf()
                }
                groups[marketId]!!.add(order)
            }
            
            // 4. 转换为分组数据并计算统计信息
            val marketIds = groups.keys.toList()

            val list = marketIds.map { marketId ->
                val marketOrders = groups[marketId] ?: mutableListOf()

                // 计算统计信息
                val count = marketOrders.size.toLong()
                val totalAmount = marketOrders.sumOf { order ->
                    order.quantity.toSafeBigDecimal().multi(order.price)
                }

                // 计算订单状态统计
                val fullyMatchedCount = marketOrders.count { it.status == "fully_matched" }
                val partiallyMatchedCount = marketOrders.count { it.status == "partially_matched" }
                val filledCount = marketOrders.count { it.status == "filled" }
                val fullyMatched = fullyMatchedCount == marketOrders.size

                val stats = MarketOrderStats(
                    count = count,
                    totalAmount = totalAmount.toString(),
                    totalPnl = null,  // 买入订单没有已实现盈亏
                    fullyMatched = fullyMatched,
                    fullyMatchedCount = fullyMatchedCount.toLong(),
                    partiallyMatchedCount = partiallyMatchedCount.toLong(),
                    filledCount = filledCount.toLong()
                )

                // 排序（按创建时间倒序）
                marketOrders.sortByDescending { it.createdAt }

                // 转换为 DTO
                val orderDtos = marketOrders.map { order ->
                    val amount = order.quantity.toSafeBigDecimal().multi(order.price)
                    val market = markets[order.marketId]
                    BuyOrderInfo(
                        orderId = order.buyOrderId,
                        leaderTradeId = order.leaderBuyTradeId,
                        marketId = order.marketId,
                        marketTitle = market?.title,
                        marketSlug = market?.slug,  // 显示用的 slug
                        eventSlug = market?.eventSlug,  // 跳转用的 slug（从数据库读取）
                        marketCategory = market?.category,
                        side = order.side,
                        quantity = order.quantity.toString(),
                        price = order.price.toString(),
                        amount = amount.toString(),
                        matchedQuantity = order.matchedQuantity.toString(),
                        remainingQuantity = order.remainingQuantity.toString(),
                        status = order.status,
                        createdAt = order.createdAt
                    )
                }

                MarketOrderGroup(
                    marketId = marketId,
                    marketTitle = markets[marketId]?.title,
                    marketSlug = markets[marketId]?.slug,  // 显示用的 slug
                    eventSlug = markets[marketId]?.eventSlug,  // 跳转用的 slug（从数据库读取）
                    marketCategory = markets[marketId]?.category,
                    stats = stats,
                    orders = orderDtos as List<Any>
                )
            }.sortedByDescending { group ->
                // 找出该市场最近的买入订单时间
                group.orders.mapNotNull { order ->
                    when (order) {
                        is BuyOrderInfo -> order.createdAt
                        else -> null
                    }
                }.maxOrNull() ?: 0L
            }

            // 5. 分页
            val page = (request.page ?: 1)
            val limit = request.limit ?: 20
            val total = list.size.toLong()

            val start = (page - 1) * limit
            val end = minOf(start + limit, list.size)
            val pagedList = if (start < list.size) list.subList(start, end) else emptyList()

            val response = MarketGroupedOrdersResponse(
                list = pagedList,
                total = total,
                page = page,
                limit = limit
            )

            Result.success(response)
        } catch (e: Exception) {
            logger.error("获取按市场分组的卖出订单列表失败: copyTradingId=${request.copyTradingId}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 获取按市场分组的卖出订单列表
     */
    fun getSellOrderListGroupedByMarket(request: MarketGroupedOrdersRequest): Result<MarketGroupedOrdersResponse> {
        return try {
            // 1. 验证跟单关系
            copyTradingRepository.findById(request.copyTradingId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("跟单关系不存在: ${request.copyTradingId}"))
            
            // 2. 获取所有卖出记录
            var sellRecords = sellMatchRecordRepository.findByCopyTradingId(request.copyTradingId)
            
            // 3. 批量获取市场信息（用于筛选）
            val allMarketIds = sellRecords.map { it.marketId }.distinct()
            val markets = marketService.getMarkets(allMarketIds)
            
            // 4. 筛选
            if (!request.marketId.isNullOrBlank()) {
                // marketId 支持模糊匹配
                sellRecords = sellRecords.filter { it.marketId.contains(request.marketId!!, ignoreCase = true) }
            }
            if (!request.marketTitle.isNullOrBlank()) {
                // marketTitle 关键字筛选
                sellRecords = sellRecords.filter { record ->
                    val market = markets[record.marketId]
                    market?.title?.contains(request.marketTitle!!, ignoreCase = true) == true
                }
            }
            // 5. 按市场ID分组
            val groups = mutableMapOf<String, MutableList<SellMatchRecord>>()
            sellRecords.forEach { record ->
                val marketId = record.marketId
                if (!groups.containsKey(marketId)) {
                    groups[marketId] = mutableListOf()
                }
                groups[marketId]!!.add(record)
            }
            
            // 4. 转换为分组数据并计算统计信息
            val marketIds = groups.keys.toList()
            
            val list = marketIds.map { marketId ->
                val marketRecords = groups[marketId] ?: mutableListOf()
                
                // 计算统计信息
                val count = marketRecords.size.toLong()
                val totalAmount = marketRecords.sumOf { record ->
                    record.totalMatchedQuantity.toSafeBigDecimal().multi(record.sellPrice)
                }
                val totalPnl = marketRecords.sumOf { it.totalRealizedPnl.toSafeBigDecimal() }
                
                val stats = MarketOrderStats(
                    count = count,
                    totalAmount = totalAmount.toString(),
                    totalPnl = totalPnl.toString(),
                    fullyMatched = true,  // 卖出订单都是已成交的
                    fullyMatchedCount = count,  // 所有订单都是已成交的
                    partiallyMatchedCount = 0L,
                    filledCount = 0L
                )
                
                // 排序（按创建时间倒序）
                marketRecords.sortByDescending { it.createdAt }
                
                // 转换为 DTO
                val orderDtos = marketRecords.map { record ->
                    val amount = record.totalMatchedQuantity.toSafeBigDecimal().multi(record.sellPrice)
                    val market = markets[record.marketId]
                    SellOrderInfo(
                        orderId = record.sellOrderId,
                        leaderTradeId = record.leaderSellTradeId,
                        marketId = record.marketId,
                        marketTitle = market?.title,
                        marketSlug = market?.slug,  // 显示用的 slug
                        eventSlug = market?.eventSlug,  // 跳转用的 slug（从数据库读取）
                        marketCategory = market?.category,
                        side = record.side,
                        quantity = record.totalMatchedQuantity.toString(),
                        price = record.sellPrice.toString(),
                        amount = amount.toString(),
                        realizedPnl = record.totalRealizedPnl.toString(),
                        createdAt = record.createdAt
                    )
                }
                
                MarketOrderGroup(
                    marketId = marketId,
                    marketTitle = markets[marketId]?.title,
                    marketSlug = markets[marketId]?.slug,  // 显示用的 slug
                    eventSlug = markets[marketId]?.eventSlug,  // 跳转用的 slug（从数据库读取）
                    marketCategory = markets[marketId]?.category,
                    stats = stats,
                    orders = orderDtos as List<Any>
                )
            }.sortedByDescending { group ->
                // 找出该市场最近的卖出订单时间（与买入订单分组排序规则一致）
                group.orders.mapNotNull { order ->
                    when (order) {
                        is SellOrderInfo -> order.createdAt
                        else -> null
                    }
                }.maxOrNull() ?: 0L
            }
            
            // 5. 分页
            val page = (request.page ?: 1)
            val limit = request.limit ?: 20
            val total = list.size.toLong()
            
            val start = (page - 1) * limit
            val end = minOf(start + limit, list.size)
            val pagedList = if (start < list.size) list.subList(start, end) else emptyList()
            
            val response = MarketGroupedOrdersResponse(
                list = pagedList,
                total = total,
                page = page,
                limit = limit
            )
            
            Result.success(response)
        } catch (e: Exception) {
            logger.error("获取按市场分组的卖出订单列表失败: copyTradingId=${request.copyTradingId}", e)
            Result.failure(e)
        }
    }
    
}
