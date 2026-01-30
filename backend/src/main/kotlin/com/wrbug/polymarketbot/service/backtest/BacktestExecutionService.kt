package com.wrbug.polymarketbot.service.backtest

import com.wrbug.polymarketbot.entity.BacktestTask
import com.wrbug.polymarketbot.entity.BacktestTrade
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.repository.BacktestTradeRepository
import com.wrbug.polymarketbot.repository.BacktestTaskRepository
import com.wrbug.polymarketbot.service.common.MarketPriceService
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingFilterService
import com.wrbug.polymarketbot.service.copytrading.configs.FilterResult
import com.wrbug.polymarketbot.service.backtest.BacktestDataService
import com.wrbug.polymarketbot.service.backtest.LeaderTrade
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.*

/**
 * 回测执行服务
 * 执行回测任务的核心算法
 */
@Service
class BacktestExecutionService(
    private val backtestTaskRepository: BacktestTaskRepository,
    private val backtestTradeRepository: BacktestTradeRepository,
    private val backtestDataService: BacktestDataService,
    private val marketPriceService: MarketPriceService,
    private val copyTradingFilterService: CopyTradingFilterService
) {
    private val logger = LoggerFactory.getLogger(BacktestExecutionService::class.java)

    /**
     * 持仓数据结构
     */
    data class Position(
        val marketId: String,
        val outcome: String,
        val outcomeIndex: Int?,
        var quantity: BigDecimal,
        val avgPrice: BigDecimal,
        val leaderBuyQuantity: BigDecimal?
    )

    /**
     * 将 BacktestTask 转换为 CopyTrading 对象（用于过滤检查）
     */
    private fun taskToCopyTrading(task: BacktestTask): CopyTrading {
        return CopyTrading(
            id = task.id,
            accountId = 0L,  // 回测不需要账户ID
            leaderId = task.leaderId,
            enabled = true,
            copyMode = task.copyMode,
            copyRatio = task.copyRatio,
            fixedAmount = null,
            maxOrderSize = task.maxOrderSize,
            minOrderSize = task.minOrderSize,
            maxDailyLoss = task.maxDailyLoss,
            maxDailyOrders = task.maxDailyOrders,
            priceTolerance = task.priceTolerance,
            delaySeconds = task.delaySeconds,
            pollIntervalSeconds = 5,
            useWebSocket = false,
            websocketReconnectInterval = 5000,
            websocketMaxRetries = 10,
            supportSell = task.supportSell,
            minOrderDepth = task.minOrderDepth,
            maxSpread = task.maxSpread,
            minPrice = task.minPrice,
            maxPrice = task.maxPrice,
            maxPositionValue = task.maxPositionValue,
            keywordFilterMode = task.keywordFilterMode,
            keywords = task.keywords,
            configName = null,
            pushFailedOrders = false,
            pushFilteredOrders = false,
            maxMarketEndDate = task.maxMarketEndDate,
            createdAt = task.createdAt,
            updatedAt = task.updatedAt
        )
    }

    /**
     * 执行回测任务
     */
    @Transactional
    suspend fun executeBacktest(task: BacktestTask) {
        return try {
            logger.info("开始执行回测任务: taskId=${task.id}, taskName=${task.taskName}")

            // 1. 更新任务状态为 RUNNING
            task.status = "RUNNING"
            task.executionStartedAt = System.currentTimeMillis()
            task.updatedAt = System.currentTimeMillis()
            backtestTaskRepository.save(task)

            // 2. 初始化
            var currentBalance = task.initialBalance
            val positions = mutableMapOf<String, Position>() // marketId + outcomeIndex -> Position
            val trades = mutableListOf<BacktestTrade>()

            // 3. 计算回测时间范围
            val endTime = System.currentTimeMillis()
            val startTime = task.startTime

            logger.info("回测时间范围: ${formatTimestamp(startTime)} - ${formatTimestamp(endTime)}, " +
                "初始余额: ${task.initialBalance.toPlainString()}")

            // 4. 获取 Leader 历史交易
            val leaderTrades = backtestDataService.getLeaderHistoricalTrades(
                task.leaderId,
                startTime,
                endTime
            ).sortedBy { it.tradeTimestamp }

            logger.info("获取到 ${leaderTrades.size} 条历史交易")

            // 5. 按时间顺序回放交易
            var processedCount = 0
            val totalTrades = leaderTrades.size

            for (leaderTrade in leaderTrades) {
                // 检查是否需要停止
                if (task.status == "STOPPED") {
                    logger.info("回测任务已被停止")
                    break
                }

                processedCount++
                val progress = (processedCount * 100) / totalTrades
                if (progress >= task.progress + 5) {
                    task.progress = progress
                    backtestTaskRepository.save(task)
                }

                try {
                    // 5.1 实时检查并结算已到期的市场
                    currentBalance = settleExpiredPositions(task, positions, currentBalance, trades, leaderTrade.tradeTimestamp)

                    // 5.2 检查余额和持仓状态
                    if (currentBalance < BigDecimal.ONE && positions.isEmpty()) {
                        logger.info("余额不足且无持仓，停止回测: $currentBalance")
                        break
                    }

                    // 如果余额不足但有持仓，记录日志但继续处理
                    if (currentBalance < BigDecimal.ONE && positions.isNotEmpty()) {
                        logger.info("余额不足 $currentBalance，但还有 ${positions.size} 个持仓，继续处理")
                    }

                    // 5.3 应用过滤规则
                    val copyTrading = taskToCopyTrading(task)
                    val filterResult = copyTradingFilterService.checkFilters(
                        copyTrading,
                        tokenId = "",  // 回测不需要 tokenId
                        tradePrice = leaderTrade.price,
                        copyOrderAmount = null,
                        marketId = leaderTrade.marketId,
                        marketTitle = leaderTrade.marketTitle,
                        marketEndDate = null,
                        outcomeIndex = leaderTrade.outcomeIndex
                    )

                    if (!filterResult.isPassed) {
                        continue
                    }

                    // 5.4 每日订单数检查
                    val dailyOrderCount = trades.count {
                        isSameDay(it.tradeTime, leaderTrade.tradeTimestamp)
                    }

                    if (dailyOrderCount >= task.maxDailyOrders) {
                        logger.info("已达到每日最大订单数限制: $dailyOrderCount / ${task.maxDailyOrders}")
                        continue
                    }

                    // 5.5 价格容忍度检查
                    if (task.priceTolerance > BigDecimal.ZERO) {
                        val tolerance = task.priceTolerance.divide(BigDecimal("100"))
                        val minPrice = leaderTrade.price.multiply(BigDecimal.ONE.subtract(tolerance))
                        val maxPrice = leaderTrade.price.multiply(BigDecimal.ONE.add(tolerance))

                        val currentPrice = marketPriceService.getCurrentMarketPrice(
                            leaderTrade.marketId,
                            leaderTrade.outcomeIndex ?: 0
                        )

                        val currentPriceDecimal = currentPrice.toSafeBigDecimal()
                        if (currentPriceDecimal < minPrice || currentPriceDecimal > maxPrice) {
                            logger.info("价格超出容忍度范围: 当前=$currentPrice, 可用范围=[$minPrice, $maxPrice]")
                            continue
                        }
                    }

                    // 5.6 计算跟单金额
                    val followAmount = calculateFollowAmount(task, leaderTrade)

                    if (leaderTrade.side == "BUY") {
                        // 买入逻辑
                        val quantity = followAmount.divide(leaderTrade.price, 8, java.math.RoundingMode.DOWN)
                        val totalCost = followAmount  // 不计算手续费

                        // 严格模式: 仅检查当前可用余额
                        if (totalCost > currentBalance) {
                            logger.info("余额不足以执行买入订单: 需要 $totalCost, 可用 $currentBalance")
                            continue
                        }

                        // 更新余额和持仓
                        currentBalance -= totalCost
                        val positionKey = "${leaderTrade.marketId}:${leaderTrade.outcomeIndex ?: 0}"
                        positions[positionKey] = Position(
                            marketId = leaderTrade.marketId,
                            outcome = leaderTrade.outcome ?: "",
                            outcomeIndex = leaderTrade.outcomeIndex,
                            quantity = quantity,
                            avgPrice = leaderTrade.price.toSafeBigDecimal(),
                            leaderBuyQuantity = leaderTrade.size.toSafeBigDecimal()
                        )

                        // 记录交易
                        trades.add(BacktestTrade(
                            backtestTaskId = task.id!!,
                            tradeTime = leaderTrade.tradeTimestamp,
                            marketId = leaderTrade.marketId,
                            marketTitle = leaderTrade.marketTitle,
                            side = "BUY",
                            outcome = leaderTrade.outcome ?: leaderTrade.outcomeIndex.toString(),
                            outcomeIndex = leaderTrade.outcomeIndex,
                            quantity = quantity,
                            price = leaderTrade.price.toSafeBigDecimal(),
                            amount = followAmount,
                            fee = BigDecimal.ZERO,
                            profitLoss = null,
                            balanceAfter = currentBalance,
                            leaderTradeId = leaderTrade.tradeId
                        ))

                    } else {
                        // SELL 逻辑
                        if (!task.supportSell) {
                            continue
                        }

                        val positionKey = "${leaderTrade.marketId}:${leaderTrade.outcomeIndex ?: 0}"
                        val position = positions[positionKey] ?: continue

                        // 计算卖出数量
                        val sellQuantity = if (task.copyMode == "RATIO") {
                            if (position.leaderBuyQuantity != null && position.leaderBuyQuantity > BigDecimal.ZERO) {
                                position.quantity.multiply(
                                    leaderTrade.size.divide(position.leaderBuyQuantity, 8, java.math.RoundingMode.DOWN)
                                )
                            } else {
                                position.quantity  // 全部卖出
                            }
                        } else {
                            position.quantity  // 固定金额模式全部卖出
                        }

                        // 确保不超过持仓数量
                        val actualSellQuantity = if (sellQuantity > position.quantity) {
                            position.quantity
                        } else {
                            sellQuantity
                        }

                        val sellAmount = actualSellQuantity.multiply(leaderTrade.price.toSafeBigDecimal())
                        val netAmount = sellAmount  // 不扣除手续费

                        // 计算盈亏
                        val cost = actualSellQuantity.multiply(position.avgPrice)
                        val profitLoss = netAmount.subtract(cost)

                        // 更新余额和持仓
                        currentBalance += netAmount
                        position.quantity -= actualSellQuantity
                        if (position.quantity <= BigDecimal.ZERO) {
                            positions.remove(positionKey)
                        }

                        // 记录交易
                        trades.add(BacktestTrade(
                            backtestTaskId = task.id!!,
                            tradeTime = leaderTrade.tradeTimestamp,
                            marketId = leaderTrade.marketId,
                            marketTitle = leaderTrade.marketTitle,
                            side = "SELL",
                            outcome = leaderTrade.outcome ?: leaderTrade.outcomeIndex.toString(),
                            outcomeIndex = leaderTrade.outcomeIndex,
                            quantity = actualSellQuantity,
                            price = leaderTrade.price.toSafeBigDecimal(),
                            amount = sellAmount,
                            fee = BigDecimal.ZERO,
                            profitLoss = profitLoss,
                            balanceAfter = currentBalance,
                            leaderTradeId = leaderTrade.tradeId
                        ))
                    }
                } catch (e: Exception) {
                    logger.error("处理交易失败: tradeId=${leaderTrade.tradeId}", e)
                }
            }

            // 6. 处理回测结束时仍未到期的持仓 (兜底处理)
            currentBalance = settleRemainingPositions(task, positions, currentBalance, trades, endTime)

            // 7. 计算最终统计数据
            val statistics = calculateStatistics(trades)

            // 8. 更新任务状态
            val profitAmount = currentBalance.subtract(task.initialBalance)
            val profitRate = if (task.initialBalance > BigDecimal.ZERO) {
                profitAmount.divide(task.initialBalance, 4, java.math.RoundingMode.HALF_UP).multiply(BigDecimal("100"))
            } else {
                BigDecimal.ZERO
            }
            val finalStatus = if (task.status == "STOPPED") "STOPPED" else "COMPLETED"
            val updatedTask = task.copy(
                finalBalance = currentBalance,
                profitAmount = profitAmount,
                profitRate = profitRate,
                endTime = endTime,
                status = finalStatus,
                progress = 100,
                totalTrades = trades.size,
                buyTrades = trades.count { it.side == "BUY" },
                sellTrades = trades.count { it.side == "SELL" },
                winTrades = statistics.winTrades,
                lossTrades = statistics.lossTrades,
                winRate = statistics.winRate,
                maxProfit = statistics.maxProfit,
                maxLoss = statistics.maxLoss,
                maxDrawdown = statistics.maxDrawdown,
                avgHoldingTime = statistics.avgHoldingTime,
                executionFinishedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            backtestTaskRepository.save(updatedTask)

            // 9. 批量保存交易记录
            backtestTradeRepository.saveAll(trades)

            logger.info("回测任务执行完成: taskId=${task.id}, " +
                "最终余额=${currentBalance.toPlainString()}, " +
                "收益额=${task.profitAmount?.toPlainString()}, " +
                "收益率=${task.profitRate?.toPlainString()}%, " +
                "总交易数=${trades.size}, " +
                "盈利率=${task.winRate?.toPlainString()}%")

        } catch (e: Exception) {
            logger.error("回测任务执行失败: taskId=${task.id}", e)
            task.status = "FAILED"
            task.errorMessage = e.message
            task.updatedAt = System.currentTimeMillis()
            backtestTaskRepository.save(task)
            throw e
        }
    }

    /**
     * 结算已到期的市场
     */
    private suspend fun settleExpiredPositions(
        task: BacktestTask,
        positions: MutableMap<String, Position>,
        currentBalance: BigDecimal,
        trades: MutableList<BacktestTrade>,
        currentTime: Long
    ): BigDecimal {
        var balance = currentBalance
        for ((positionKey, position) in positions.toList()) {
            try {
                // 获取市场当前价格
                val marketPrice = marketPriceService.getCurrentMarketPrice(
                    position.marketId,
                    position.outcomeIndex ?: 0
                )

                val price = marketPrice.toSafeBigDecimal()

                // 通过市场价格判断结算价格
                val settlementPrice = when {
                    price >= BigDecimal("0.95") -> BigDecimal.ONE  // 胜出
                    price <= BigDecimal("0.05") -> BigDecimal.ZERO  // 失败
                    else -> position.avgPrice  // 未结算或不确定，按成本价
                }

                val settlementValue = position.quantity.multiply(settlementPrice)
                val profitLoss = settlementValue.subtract(position.quantity.multiply(position.avgPrice))

                balance += settlementValue

                // 记录结算交易
                trades.add(BacktestTrade(
                    backtestTaskId = task.id!!,
                    tradeTime = currentTime,
                    marketId = position.marketId,
                    marketTitle = null,
                    side = "SETTLEMENT",
                    outcome = position.outcome,
                    outcomeIndex = position.outcomeIndex,
                    quantity = position.quantity,
                    price = settlementPrice,
                    amount = settlementValue,
                    fee = BigDecimal.ZERO,
                    profitLoss = profitLoss,
                    balanceAfter = currentBalance,
                    leaderTradeId = null
                ))

                // 移除已结算的持仓
                positions.remove(positionKey)

                logger.info("市场结算: ${position.marketId}, 结算价=$settlementPrice, 盈亏=$profitLoss")
            } catch (e: Exception) {
                logger.warn("结算市场失败: ${position.marketId}", e)
            }
        }
        return balance
    }

    /**
     * 结算剩余持仓
     */
    private suspend fun settleRemainingPositions(
        task: BacktestTask,
        positions: MutableMap<String, Position>,
        currentBalance: BigDecimal,
        trades: MutableList<BacktestTrade>,
        currentTime: Long
    ): BigDecimal {
        var balance = currentBalance
        for ((positionKey, position) in positions.toList()) {
            try {
                val marketPrice = marketPriceService.getCurrentMarketPrice(
                    position.marketId,
                    position.outcomeIndex ?: 0
                )

                val price = marketPrice.toSafeBigDecimal()

                val settlementPrice = when {
                    price >= BigDecimal("0.95") -> BigDecimal.ONE
                    price <= BigDecimal("0.05") -> BigDecimal.ZERO
                    else -> position.avgPrice
                }

                val settlementValue = position.quantity.multiply(settlementPrice)
                val profitLoss = settlementValue.subtract(position.quantity.multiply(position.avgPrice))

                balance += settlementValue

                trades.add(BacktestTrade(
                    backtestTaskId = task.id!!,
                    tradeTime = currentTime,
                    marketId = position.marketId,
                    marketTitle = null,
                    side = "SETTLEMENT",
                    outcome = position.outcome,
                    outcomeIndex = position.outcomeIndex,
                    quantity = position.quantity,
                    price = settlementPrice,
                    amount = settlementValue,
                    fee = BigDecimal.ZERO,
                    profitLoss = profitLoss,
                    balanceAfter = balance,
                    leaderTradeId = null
                ))

                logger.info("回测结束时结算剩余持仓: ${position.marketId}, 结算价=$settlementPrice")
            } catch (e: Exception) {
                logger.warn("结算市场失败: ${position.marketId}", e)
            }
        }
        return balance
    }

    /**
     * 计算跟单金额
     */
    private fun calculateFollowAmount(
        task: BacktestTask,
        leaderTrade: LeaderTrade
    ): BigDecimal {
        return when (task.copyMode) {
            "RATIO" -> leaderTrade.amount.multiply(task.copyRatio)
            "FIXED" -> {
                task.fixedAmount ?: leaderTrade.amount
            }
            else -> leaderTrade.amount
        }.also {
            // 应用最大/最小订单限制
            val maxLimit = task.maxOrderSize
            val minLimit = task.minOrderSize
            if (it > maxLimit) maxLimit
            else if (it < minLimit) minLimit
            else it
        }
    }

    /**
     * 判断是否同一天
     */
    private fun isSameDay(timestamp1: Long, timestamp2: Long): Boolean {
        val calendar1 = Calendar.getInstance().apply { timeInMillis = timestamp1 }
        val calendar2 = Calendar.getInstance().apply { timeInMillis = timestamp2 }
        return calendar1.get(Calendar.YEAR) == calendar2.get(Calendar.YEAR) &&
                calendar1.get(Calendar.DAY_OF_YEAR) == calendar2.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * 格式化时间戳
     */
    private fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    /**
     * 计算统计数据
     */
    private fun calculateStatistics(trades: List<BacktestTrade>): StatisticsData {
        val buyTrades = trades.filter { it.side == "BUY" }
        val sellTrades = trades.filter { it.side == "SELL" }
        val settlementTrades = trades.filter { it.side == "SETTLEMENT" }

        val profitLossList = trades.mapNotNull { it.profitLoss }
        val winTrades = profitLossList.count { it > BigDecimal.ZERO }
        val lossTrades = profitLossList.count { it < BigDecimal.ZERO }

        val totalTrades = profitLossList.size
        val winRate = if (totalTrades > 0) {
            winTrades.toBigDecimal()
                .divide(totalTrades.toBigDecimal(), 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal("100"))
        } else {
            BigDecimal.ZERO
        }

        val maxProfit = profitLossList.maxOrNull() ?: BigDecimal.ZERO
        val maxLoss = profitLossList.minOrNull() ?: BigDecimal.ZERO

        // 计算最大回撤
        var maxBalance = BigDecimal.ZERO
        var maxDrawdown = BigDecimal.ZERO
        for (trade in trades) {
            if (trade.balanceAfter > maxBalance) {
                maxBalance = trade.balanceAfter
            }
            val drawdown = maxBalance.subtract(trade.balanceAfter)
            if (drawdown > maxDrawdown) {
                maxDrawdown = drawdown
            }
        }

        // 计算平均持仓时间
        val avgHoldingTime = calculateAvgHoldingTime(buyTrades, sellTrades, settlementTrades)

        return StatisticsData(
            winTrades = winTrades,
            lossTrades = lossTrades,
            winRate = winRate,
            maxProfit = maxProfit,
            maxLoss = maxLoss,
            maxDrawdown = maxDrawdown,
            avgHoldingTime = avgHoldingTime
        )
    }

    /**
     * 计算平均持仓时间
     */
    private fun calculateAvgHoldingTime(
        buyTrades: List<BacktestTrade>,
        sellTrades: List<BacktestTrade>,
        settlementTrades: List<BacktestTrade>
    ): Long? {
        val marketHoldings = mutableMapOf<String, MutableList<Long>>()

        // 记录买入时间
        for (buyTrade in buyTrades) {
            val key = "${buyTrade.marketId}:${buyTrade.outcomeIndex ?: 0}"
            marketHoldings.getOrPut(key) { mutableListOf() }.add(buyTrade.tradeTime)
        }

        // 计算持仓时间
        val holdingTimes = mutableListOf<Long>()
        for (sellTrade in sellTrades) {
            val key = "${sellTrade.marketId}:${sellTrade.outcomeIndex ?: 0}"
            val buyTimes = marketHoldings[key] ?: continue
            if (buyTimes.isNotEmpty()) {
                val buyTime = buyTimes.removeFirst()
                val holdingTime = sellTrade.tradeTime - buyTime
                if (holdingTime > 0) {
                    holdingTimes.add(holdingTime)
                }
            }
        }

        // 处理结算
        for (settleTrade in settlementTrades) {
            val key = "${settleTrade.marketId}:${settleTrade.outcomeIndex ?: 0}"
            val buyTimes = marketHoldings[key] ?: continue
            if (buyTimes.isNotEmpty()) {
                val buyTime = buyTimes.removeFirst()
                val holdingTime = settleTrade.tradeTime - buyTime
                if (holdingTime > 0) {
                    holdingTimes.add(holdingTime)
                }
            }
        }

        return if (holdingTimes.isNotEmpty()) {
            holdingTimes.sum().toLong() / holdingTimes.size
        } else {
            null
        }
    }

    /**
     * 统计数据
     */
    data class StatisticsData(
        val winTrades: Int,
        val lossTrades: Int,
        val winRate: BigDecimal,
        val maxProfit: BigDecimal,
        val maxLoss: BigDecimal,
        val maxDrawdown: BigDecimal,
        val avgHoldingTime: Long?
    )
}

