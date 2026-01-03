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
    private val accountService: AccountService,
    private val blockchainService: BlockchainService
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
            
            // 7. 获取链上实际持仓（用于准确计算未实现盈亏，考虑手动卖出的情况）
            val actualPositions = getActualPositions(account)
            
            // 8. 获取当前市场价格（用于计算未实现盈亏）
            val currentPrice = getCurrentMarketPrice(buyOrders)
            
            // 9. 计算未实现盈亏（使用链上实际持仓，而不是 remainingQuantity）
            val unrealizedPnl = calculateUnrealizedPnl(buyOrders, currentPrice, actualPositions)
            
            // 10. 计算持仓价值（使用链上实际持仓和当前价格）
            val positionValue = calculatePositionValue(buyOrders, currentPrice, actualPositions)
            
            // 11. 构建响应
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
                totalPnl = (statistics.totalRealizedPnl.toSafeBigDecimal().add(unrealizedPnl.toSafeBigDecimal())).toString(),
                totalPnlPercent = calculatePnlPercent(statistics.totalBuyAmount, statistics.totalRealizedPnl, unrealizedPnl)
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
        
        // 筛选
        if (!request.marketId.isNullOrBlank()) {
            orders = orders.filter { it.marketId == request.marketId }
        }
        if (!request.side.isNullOrBlank()) {
            orders = orders.filter { it.side == request.side }
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
            BuyOrderInfo(
                orderId = order.buyOrderId,
                leaderTradeId = order.leaderBuyTradeId,
                marketId = order.marketId,
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
        
        // 筛选
        if (!request.marketId.isNullOrBlank()) {
            records = records.filter { it.marketId == request.marketId }
        }
        if (!request.side.isNullOrBlank()) {
            records = records.filter { it.side == request.side }
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
            SellOrderInfo(
                orderId = record.sellOrderId,
                leaderTradeId = record.leaderSellTradeId,
                marketId = record.marketId,
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
        
        val total = filtered.size.toLong()
        
        // 排序（按创建时间倒序）
        filtered = filtered.sortedByDescending { it.createdAt }
        
        // 分页
        val page = (request.page ?: 1) - 1
        val limit = request.limit ?: 20
        val start = page * limit
        val end = minOf(start + limit, filtered.size)
        val pagedDetails = if (start < filtered.size) filtered.subList(start, end) else emptyList()
        
        // 转换为DTO
        val list = pagedDetails.map { detail ->
            MatchedOrderInfo(
                sellOrderId = sellMatchRecordRepository.findById(detail.matchRecordId).orElse(null)?.sellOrderId ?: "",
                buyOrderId = detail.buyOrderId,
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
     * 获取当前市场价格
     * 按 (marketId, outcomeIndex) 组合获取价格，支持多元市场
     */
    private suspend fun getCurrentMarketPrice(buyOrders: List<CopyOrderTracking>): Map<String, String> {
        val prices = mutableMapOf<String, String>()
        
        // 获取所有不同的 (marketId, outcomeIndex) 组合
        val marketOutcomePairs = buyOrders
            .filter { it.outcomeIndex != null }
            .map { Pair(it.marketId, it.outcomeIndex!!) }
            .distinct()
        
        for ((marketId, outcomeIndex) in marketOutcomePairs) {
            try {
                // 传递 outcomeIndex 参数，确保获取对应 outcome 的价格
                val result = accountService.getMarketPrice(marketId, outcomeIndex)
                result.onSuccess { response ->
                    // 使用当前价格
                    val price = response.currentPrice
                    if (price.isNotBlank() && price != "0") {
                        // 使用 "marketId:outcomeIndex" 作为 key
                        val key = "$marketId:$outcomeIndex"
                        prices[key] = price
                    }
                }
            } catch (e: Exception) {
                logger.warn("获取市场价格失败: marketId=$marketId, outcomeIndex=$outcomeIndex", e)
            }
        }
        
        return prices
    }
    
    /**
     * 获取链上实际持仓
     * 按 (marketId, outcomeIndex) 组合返回实际持仓数量
     */
    private suspend fun getActualPositions(account: Account?): Map<String, BigDecimal> {
        val positions = mutableMapOf<String, BigDecimal>()
        
        if (account == null || account.proxyAddress.isBlank()) {
            return positions
        }
        
        try {
            val positionsResult = blockchainService.getPositions(account.proxyAddress)
            if (positionsResult.isSuccess) {
                val positionList = positionsResult.getOrNull() ?: emptyList()
                for (pos in positionList) {
                    // 只处理有 conditionId 和 outcomeIndex 的仓位
                    if (pos.conditionId != null && pos.outcomeIndex != null && pos.size != null) {
                        val key = "${pos.conditionId}:${pos.outcomeIndex}"
                        val size = pos.size.toSafeBigDecimal()
                        // 如果 size > 0，表示有持仓；如果 size < 0，表示做空（取绝对值）
                        positions[key] = size.abs()
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("获取链上持仓失败: accountId=${account.id}, error=${e.message}", e)
        }
        
        return positions
    }
    
    /**
     * 计算未实现盈亏
     * 使用链上实际持仓数量，而不是 remainingQuantity（考虑手动卖出的情况）
     * 按市场聚合订单，计算加权平均买入价格，避免重复计算
     */
    private fun calculateUnrealizedPnl(
        buyOrders: List<CopyOrderTracking>,
        currentPrices: Map<String, String>,
        actualPositions: Map<String, BigDecimal>
    ): String {
        var totalUnrealizedPnl = BigDecimal.ZERO
        
        // 按市场聚合订单，计算加权平均买入价格
        val marketAggregates = mutableMapOf<String, Pair<BigDecimal, BigDecimal>>() // key -> (总持仓, 总成本)
        
        for (order in buyOrders) {
            // 如果没有 outcomeIndex，跳过（无法确定价格和持仓）
            if (order.outcomeIndex == null) {
                logger.warn("订单缺少 outcomeIndex，跳过未实现盈亏计算: orderId=${order.buyOrderId}, marketId=${order.marketId}")
                continue
            }
            
            // 使用 "marketId:outcomeIndex" 作为 key
            val key = "${order.marketId}:${order.outcomeIndex}"
            
            // 获取订单的持仓数量（使用 remainingQuantity，因为这是该订单的持仓）
            val orderQty = order.remainingQuantity.toSafeBigDecimal()
            
            // 如果订单持仓 <= 0，跳过
            if (orderQty.lte(BigDecimal.ZERO)) continue
            
            val buyPrice = order.price.toSafeBigDecimal()
            val orderCost = orderQty.multi(buyPrice)
            
            // 聚合同一市场的订单
            val existing = marketAggregates[key]
            if (existing != null) {
                val totalQty = existing.first.add(orderQty)
                val totalCost = existing.second.add(orderCost)
                marketAggregates[key] = Pair(totalQty, totalCost)
            } else {
                marketAggregates[key] = Pair(orderQty, orderCost)
            }
        }
        
        // 计算每个市场的未实现盈亏
        for ((key, aggregate) in marketAggregates) {
            val (totalQty, totalCost) = aggregate
            
            // 获取链上实际持仓数量（如果存在），否则使用聚合的持仓数量
            val actualQty = actualPositions[key] ?: totalQty
            
            // 如果实际持仓 <= 0，说明已全部卖出（包括手动卖出），跳过未实现盈亏计算
            if (actualQty.lte(BigDecimal.ZERO)) continue
            
            // 获取当前市场价格
            val currentPrice = currentPrices[key]?.toSafeBigDecimal()
                ?: continue  // 如果没有当前价格，跳过
            
            // 计算加权平均买入价格
            val avgBuyPrice = if (totalQty.gt(BigDecimal.ZERO)) {
                totalCost.div(totalQty)
            } else {
                continue
            }
            
            // 使用实际持仓数量和加权平均买入价格计算未实现盈亏
            val unrealizedPnl = currentPrice.subtract(avgBuyPrice).multi(actualQty)
            totalUnrealizedPnl = totalUnrealizedPnl.add(unrealizedPnl)
        }
        
        return totalUnrealizedPnl.toString()
    }
    
    /**
     * 计算持仓价值
     * 使用链上实际持仓数量和当前市场价格计算
     * 按市场聚合，避免重复计算
     */
    private fun calculatePositionValue(
        buyOrders: List<CopyOrderTracking>,
        currentPrices: Map<String, String>,
        actualPositions: Map<String, BigDecimal>
    ): String {
        var totalPositionValue = BigDecimal.ZERO
        
        // 按市场聚合，获取所有不同的市场
        val marketKeys = buyOrders
            .filter { it.outcomeIndex != null }
            .map { "${it.marketId}:${it.outcomeIndex}" }
            .distinct()
        
        for (key in marketKeys) {
            // 获取链上实际持仓数量（如果存在）
            val actualQty = actualPositions[key]
            
            // 如果没有链上持仓，计算该市场的总持仓（所有订单的 remainingQuantity 之和）
            val totalQty = actualQty ?: buyOrders
                .filter { it.outcomeIndex != null && "${it.marketId}:${it.outcomeIndex}" == key }
                .sumOf { it.remainingQuantity.toSafeBigDecimal() }
            
            // 如果持仓 <= 0，跳过
            if (totalQty.lte(BigDecimal.ZERO)) continue
            
            // 获取当前市场价格
            val currentPrice = currentPrices[key]?.toSafeBigDecimal()
                ?: continue  // 如果没有当前价格，跳过
            
            // 计算持仓价值：持仓数量 × 当前价格
            val positionValue = totalQty.multi(currentPrice)
            totalPositionValue = totalPositionValue.add(positionValue)
        }
        
        return totalPositionValue.toString()
    }
    
    /**
     * 计算盈亏百分比
     */
    private fun calculatePnlPercent(
        totalBuyAmount: String,
        totalRealizedPnl: String,
        totalUnrealizedPnl: String
    ): String {
        val buyAmount = totalBuyAmount.toSafeBigDecimal()
        if (buyAmount.lte(BigDecimal.ZERO)) return "0"
        
        val totalPnl = totalRealizedPnl.toSafeBigDecimal().add(totalUnrealizedPnl.toSafeBigDecimal())
        val percent = totalPnl.div(buyAmount).multi(100)
        
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
}

