package com.wrbug.polymarketbot.service.backtest

import com.wrbug.polymarketbot.dto.TradeData
import com.wrbug.polymarketbot.dto.BacktestStatisticsDto
import com.wrbug.polymarketbot.entity.BacktestTask
import com.wrbug.polymarketbot.entity.BacktestTrade
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.repository.BacktestTradeRepository
import com.wrbug.polymarketbot.repository.BacktestTaskRepository
import com.wrbug.polymarketbot.service.common.MarketPriceService
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingFilterService
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

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
     * 将回测任务转换为虚拟的 CopyTrading 配置用于执行
     * 注意：回测场景使用历史数据，不需要实时跟单的相关配置
     */
    private fun taskToCopyTrading(task: BacktestTask): CopyTrading {
        return CopyTrading(
            id = task.id,
            accountId = 0L,
            leaderId = task.leaderId,
            enabled = true,
            copyMode = task.copyMode,
            copyRatio = task.copyRatio,
            fixedAmount = null,
            maxOrderSize = task.maxOrderSize,
            minOrderSize = task.minOrderSize,
            maxDailyLoss = task.maxDailyLoss,
            maxDailyOrders = task.maxDailyOrders,
            priceTolerance = BigDecimal.ZERO,  // 回测使用历史价格，不需要容忍度
            delaySeconds = 0,  // 回测按时间线执行，无需延迟
            pollIntervalSeconds = 5,
            useWebSocket = false,
            websocketReconnectInterval = 5000,
            websocketMaxRetries = 10,
            supportSell = task.supportSell,
            minOrderDepth = null,  // 回测无实时订单簿数据
            maxSpread = null,  // 回测无实时价差数据
            keywordFilterMode = task.keywordFilterMode,
            keywords = task.keywords,
            configName = null,
            pushFailedOrders = false,
            pushFilteredOrders = false,
            createdAt = task.createdAt,
            updatedAt = task.updatedAt
        )
    }

    /**
     * 执行回测任务（支持分页和恢复）
     * 自动处理所有页面的数据，支持中断恢复
     */
    @Transactional
    suspend fun executeBacktest(task: BacktestTask, page: Int = 1, size: Int = 100) {
        try {
            logger.info("开始执行回测任务: taskId=${task.id}, taskName=${task.taskName}, startPage=$page, pageSize=$size")

            // 1. 更新任务状态为 RUNNING
            task.status = "RUNNING"
            task.executionStartedAt = System.currentTimeMillis()
            task.updatedAt = System.currentTimeMillis()
            backtestTaskRepository.save(task)

            // 2. 初始化
            var currentBalance = task.initialBalance
            val positions = mutableMapOf<String, Position>()
            val trades = mutableListOf<BacktestTrade>()
            // 每日订单数缓存：key为日期字符串(yyyy-MM-dd)，value为当天的 BUY 订单数
            val dailyOrderCountCache = mutableMapOf<String, Int>()
            // 每日亏损缓存：key为日期字符串(yyyy-MM-dd)，value为当天的累计亏损金额
            val dailyLossCache = mutableMapOf<String, BigDecimal>()

            // 3. 计算回测时间范围
            val endTime = System.currentTimeMillis()
            val startTime = task.startTime

            logger.info("回测时间范围: ${formatTimestamp(startTime)} - ${formatTimestamp(endTime)}, " +
                "初始余额: ${task.initialBalance.toPlainString()}")

            // 4. 恢复机制：如果有恢复点，计算从哪一页开始
            val startPage = if (task.lastProcessedTradeIndex != null && task.lastProcessedTradeIndex >= 0) {
                val lastProcessedIndex = task.lastProcessedTradeIndex
                val calculatedPage = (lastProcessedIndex / size) + 1

                // 特殊情况：如果lastProcessedTradeIndex刚好是100的倍数减1（比如99,199,299...）
                // 说明该页已经完全处理，应该从下一页开始
                val nextPage = if (lastProcessedIndex % size == size - 1) {
                    calculatedPage + 1
                } else {
                    calculatedPage
                }

                logger.info("恢复任务：已处理索引=$lastProcessedIndex, 从第 $nextPage 页开始")
                nextPage
            } else {
                logger.info("新任务：从第0页开始")
                0
            }

            // 5. 分页获取和处理交易数据
            var currentPage = maxOf(startPage, page)
            var globalIndex = if (currentPage > 1 && task.lastProcessedTradeIndex != null) {
                task.lastProcessedTradeIndex + 1
            } else {
                0
            }

            logger.info("开始分页处理：起始页=$currentPage, 起始索引=$globalIndex")

            while (true) {
                // 定期从数据库重新加载任务状态，确保能及时响应停止操作
                val currentTaskStatus = backtestTaskRepository.findById(task.id!!).orElse(null)
                if (currentTaskStatus == null || currentTaskStatus.status != "RUNNING") {
                    logger.info("回测任务状态已变更: ${currentTaskStatus?.status}，停止执行")
                    break
                }

                logger.info("正在获取第 $currentPage 页数据...")

                // 每页使用独立的交易列表，避免跨页重复保存
                val currentPageTrades = mutableListOf<BacktestTrade>()

                try {
                    // 获取当前页的交易数据（支持重试5次）
                    val pageTrades = backtestDataService.getLeaderHistoricalTradesForPage(
                        task.leaderId,
                        startTime,
                        endTime,
                        currentPage,
                        size
                    )

                    if (pageTrades.isEmpty()) {
                        logger.info("第 $currentPage 页无数据，所有数据处理完成")
                        break
                    }

                    logger.info("第 $currentPage 页获取到 ${pageTrades.size} 条交易")

                    // 处理当前页的交易
                    for (localIndex in pageTrades.indices) {
                        val leaderTrade = pageTrades[localIndex]
                        val index = globalIndex + localIndex

                        // 更新进度
                        val progress = if (pageTrades.size > 0) {
                            (localIndex * 100) / pageTrades.size
                        } else {
                            0
                        }
                        if (progress > task.progress) {
                            task.progress = progress
                            task.processedTradeCount = index + 1
                            backtestTaskRepository.save(task)
                        }

                        try {
                            // 5.1 实时检查并结算已到期的市场
                            currentBalance = settleExpiredPositions(task, positions, currentBalance, trades, leaderTrade.timestamp)

                            // 5.2 检查余额和持仓状态
                            if (currentBalance < BigDecimal.ZERO) {
                                logger.info("余额已为负，直接终止回测: $currentBalance")
                                break
                            }
                            if (currentBalance < BigDecimal.ONE && positions.isEmpty()) {
                                logger.info("余额不足且无持仓，停止回测: $currentBalance")
                                break
                            }

                            // 5.3 应用过滤规则
                            val copyTrading = taskToCopyTrading(task)
                            val filterResult = copyTradingFilterService.checkFilters(
                                copyTrading,
                                tokenId = "",
                                tradePrice = leaderTrade.price,
                                copyOrderAmount = null,
                                marketId = leaderTrade.marketId,
                                marketTitle = leaderTrade.marketTitle,
                                marketEndDate = null,
                                outcomeIndex = leaderTrade.outcomeIndex
                            )

                            if (!filterResult.isPassed) {
                                logger.debug("交易被过滤: ${leaderTrade.tradeId}")
                                continue
                            }

                            // 5.4 每日订单数检查 - 使用缓存，只统计 BUY 订单
                            val tradeDate = formatDate(leaderTrade.timestamp)
                            val dailyOrderCount = dailyOrderCountCache.getOrDefault(tradeDate, 0)

                            if (dailyOrderCount >= task.maxDailyOrders) {
                                logger.info("已达到每日最大 BUY 订单数限制: $dailyOrderCount / ${task.maxDailyOrders}")
                                continue
                            }


                            // 5.6 计算跟单金额
                            val followAmount = calculateFollowAmount(task, leaderTrade)

                            // 5.6.1 检查订单大小限制
                            val finalFollowAmount = if (followAmount > task.maxOrderSize) {
                                logger.info("跟单金额超过最大限制: $followAmount > ${task.maxOrderSize}，调整为最大值")
                                task.maxOrderSize
                            } else if (followAmount < task.minOrderSize) {
                                logger.info("跟单金额低于最小限制: $followAmount < ${task.minOrderSize}，调整为最小值")
                                task.minOrderSize
                            } else {
                                followAmount
                            }

                            // 5.6.2 检查每日最大亏损（买入订单）- 使用缓存
                            val dailyLoss = dailyLossCache.getOrDefault(tradeDate, BigDecimal.ZERO)
                            if (dailyLoss > task.maxDailyLoss) {
                                logger.info("已达到每日最大亏损限制: $dailyLoss / ${task.maxDailyLoss}，跳过买入订单")
                                continue
                            }

                            // 5.7 处理买卖逻辑
                            if (leaderTrade.side == "BUY") {
                                // 买入逻辑
                                val quantity = finalFollowAmount.divide(leaderTrade.price, 8, java.math.RoundingMode.DOWN)
                                val totalCost = finalFollowAmount

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

                                // 记录交易到当前页列表
                                currentPageTrades.add(BacktestTrade(
                                    backtestTaskId = task.id!!,
                                    tradeTime = leaderTrade.timestamp,
                                    marketId = leaderTrade.marketId,
                                    marketTitle = leaderTrade.marketTitle,
                                    side = "BUY",
                                    outcome = leaderTrade.outcome ?: leaderTrade.outcomeIndex.toString(),
                                    outcomeIndex = leaderTrade.outcomeIndex,
                                    quantity = quantity,
                                    price = leaderTrade.price.toSafeBigDecimal(),
                                    amount = finalFollowAmount,
                                    fee = BigDecimal.ZERO,
                                    profitLoss = null,
                                    balanceAfter = currentBalance,
                                    leaderTradeId = leaderTrade.tradeId
                                ))

                                // 更新每日订单数缓存
                                dailyOrderCountCache[tradeDate] = dailyOrderCount + 1

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
                                        position.quantity
                                    }
                                } else {
                                    position.quantity
                                }

                                val actualSellQuantity = if (sellQuantity > position.quantity) {
                                    position.quantity
                                } else {
                                    sellQuantity
                                }

                                // 计算卖出金额
                                val sellAmount = actualSellQuantity.multiply(leaderTrade.price.toSafeBigDecimal())

                                // 5.6.2 检查卖出金额限制
                                val finalSellAmount = if (sellAmount > task.maxOrderSize) {
                                    logger.info("卖出金额超过最大限制: $sellAmount > ${task.maxOrderSize}，调整为最大值")
                                    task.maxOrderSize
                                } else if (sellAmount < task.minOrderSize) {
                                    logger.info("卖出金额低于最小限制: $sellAmount < ${task.minOrderSize}，调整为最小值")
                                    task.minOrderSize
                                } else {
                                    sellAmount
                                }

                                val netAmount = finalSellAmount

                                // 计算盈亏
                                val cost = actualSellQuantity.multiply(position.avgPrice)
                                val profitLoss = netAmount.subtract(cost)

                                // 更新余额和持仓
                                currentBalance += netAmount
                                if (position.quantity <= BigDecimal.ZERO) {
                                    positions.remove(positionKey)
                                }

                                // 记录交易到当前页列表
                                currentPageTrades.add(BacktestTrade(
                                    backtestTaskId = task.id!!,
                                    tradeTime = leaderTrade.timestamp,
                                    marketId = leaderTrade.marketId,
                                    marketTitle = leaderTrade.marketTitle,
                                    side = "SELL",
                                    outcome = leaderTrade.outcome ?: leaderTrade.outcomeIndex.toString(),
                                    outcomeIndex = leaderTrade.outcomeIndex,
                                    quantity = actualSellQuantity,
                                    price = leaderTrade.price.toSafeBigDecimal(),
                                    amount = finalSellAmount,
                                    fee = BigDecimal.ZERO,
                                    profitLoss = profitLoss,
                                    balanceAfter = currentBalance,
                                    leaderTradeId = leaderTrade.tradeId
                                ))
                                // SELL 订单不计入每日订单数限制
                                
                                // 更新每日亏损缓存（只累加亏损，不累加盈利）
                                if (profitLoss < BigDecimal.ZERO) {
                                    val currentDailyLoss = dailyLossCache.getOrDefault(tradeDate, BigDecimal.ZERO)
                                    dailyLossCache[tradeDate] = currentDailyLoss + profitLoss.negate()
                                }
                            }

                        } catch (e: Exception) {
                            logger.error("处理交易失败: tradeId=${leaderTrade.tradeId}", e)
                        }
                    }

                    // 保存当前页的所有交易（每页处理完成后保存，避免重复插入）
                    if (currentPageTrades.isNotEmpty()) {
                        logger.info("保存第 $currentPage 页的交易数据，共 ${currentPageTrades.size} 笔")
                        
                        // 批量保存当前页的交易
                        backtestTradeRepository.saveAll(currentPageTrades)

                        // 更新当前页的最后处理信息
                        val lastTradeInPage = currentPageTrades.lastOrNull()
                        if (lastTradeInPage != null) {
                            task.lastProcessedTradeTime = lastTradeInPage.tradeTime
                            task.lastProcessedTradeIndex = globalIndex + pageTrades.size - 1
                            task.processedTradeCount = task.lastProcessedTradeIndex + 1
                            task.finalBalance = currentBalance
                            backtestTaskRepository.save(task)

                            logger.info("第 $currentPage 页处理完成，更新索引: ${task.lastProcessedTradeIndex}, 总处理数: ${task.processedTradeCount}")
                        }
                    } else {
                        logger.info("第 $currentPage 页没有交易需要保存")
                    }

                    // 将当前页交易添加到全局列表（用于最终统计）
                    trades.addAll(currentPageTrades)

                    // 将当前页交易添加到全局列表（用于最终统计）
                    trades.addAll(currentPageTrades)

                    // 更新全局索引，准备处理下一页
                    globalIndex += pageTrades.size
                    currentPage++

                } catch (e: Exception) {
                    logger.error("获取或处理第 $currentPage 页数据失败: ${e.message}", e)
                    // 重试失败，标记任务为 FAILED
                    throw e
                }
            }

            // 6. 处理回测结束时仍未到期的持仓
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

            task.finalBalance = currentBalance
            task.profitAmount = profitAmount
            task.profitRate = profitRate
            task.endTime = endTime
            task.status = finalStatus
            task.progress = 100
            task.totalTrades = trades.size
            task.buyTrades = trades.count { it.side == "BUY" }
            task.sellTrades = trades.count { it.side == "SELL" }
            task.winTrades = statistics.winTrades
            task.lossTrades = statistics.lossTrades
            task.winRate = statistics.winRate.toSafeBigDecimal()
            task.maxProfit = statistics.maxProfit.toSafeBigDecimal()
            task.maxLoss = statistics.maxLoss.toSafeBigDecimal()
            task.maxDrawdown = statistics.maxDrawdown.toSafeBigDecimal()
            task.avgHoldingTime = statistics.avgHoldingTime
            task.executionFinishedAt = System.currentTimeMillis()
            task.updatedAt = System.currentTimeMillis()

            backtestTaskRepository.save(task)

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
            task.executionFinishedAt = System.currentTimeMillis()
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
                    price >= BigDecimal("0.95") -> BigDecimal.ONE
                    price <= BigDecimal("0.05") -> BigDecimal.ZERO
                    else -> position.avgPrice
                }

                val settlementValue = position.quantity.multiply(settlementPrice)
                val profitLoss = settlementValue.subtract(position.quantity.multiply(position.avgPrice))

                balance += settlementValue

                // 记录结算交易
                trades.add(BacktestTrade(
                    backtestTaskId = task.id!!,
                    tradeTime = currentTime,
                    marketId = position.marketId,
                    marketTitle = "",
                    side = "SETTLEMENT",
                    outcome = when {
                        settlementPrice == BigDecimal.ONE -> "WIN"
                        settlementPrice == BigDecimal.ZERO -> "LOSE"
                        else -> "UNKNOWN"
                    },
                    outcomeIndex = position.outcomeIndex,
                    quantity = position.quantity,
                    price = settlementPrice,
                    amount = settlementValue,
                    fee = BigDecimal.ZERO,
                    profitLoss = profitLoss,
                    balanceAfter = balance,
                    leaderTradeId = null
                ))

                // 移除已结算的持仓
                positions.remove(positionKey)
            } catch (e: Exception) {
                logger.error("结算市场失败: marketId=${position.marketId}, outcomeIndex=${position.outcomeIndex}", e)
            }
        }

        return balance
    }

    /**
     * 结算未到期持仓
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
            val quantity = position.quantity
            val avgPrice = position.avgPrice
            val settlementPrice = avgPrice

            val settlementValue = quantity.multiply(settlementPrice)
            val profitLoss = settlementValue.negate()

            balance += settlementValue

            // 记录平仓交易
            trades.add(BacktestTrade(
                backtestTaskId = task.id!!,
                tradeTime = currentTime,
                marketId = position.marketId,
                marketTitle = "",
                side = "SETTLEMENT",
                outcome = "CLOSED",
                outcomeIndex = position.outcomeIndex,
                quantity = quantity,
                price = avgPrice,
                amount = settlementValue,
                fee = BigDecimal.ZERO,
                profitLoss = profitLoss,
                balanceAfter = balance,
                leaderTradeId = null
            ))
        }

        positions.clear()
        return balance
    }

    /**
     * 计算统计数据
     */
    private fun calculateStatistics(trades: List<BacktestTrade>): BacktestStatisticsDto {
        val buyTrades = trades.count { it.side == "BUY" }
        val sellTrades = trades.count { it.side == "SELL" }
        val winTrades = trades.count { it.profitLoss != null && it.profitLoss > BigDecimal.ZERO }
        val lossTrades = trades.count { it.profitLoss != null && it.profitLoss < BigDecimal.ZERO }

        var totalProfit = BigDecimal.ZERO
        var totalLoss = BigDecimal.ZERO
        var maxProfit = BigDecimal.ZERO
        var maxLoss = BigDecimal.ZERO

        // 计算最大回撤
        var runningBalance = if (trades.isNotEmpty()) {
            trades[0].balanceAfter?.toSafeBigDecimal() ?: BigDecimal.ZERO
        } else {
            BigDecimal.ZERO
        }
        var peakBalance = runningBalance
        var maxDrawdown = BigDecimal.ZERO

        for (i in trades.indices) {
            val trade = trades[i]
            val balance = trade.balanceAfter?.toSafeBigDecimal() ?: continue

            if (trade.profitLoss != null) {
                val pnl = trade.profitLoss.toSafeBigDecimal()
                if (pnl > BigDecimal.ZERO) {
                    totalProfit += pnl
                    if (pnl > maxProfit) maxProfit = pnl
                } else {
                    totalLoss += pnl
                    if (pnl < maxLoss) maxLoss = pnl
                }
            }

            if (balance > peakBalance) {
                peakBalance = balance
            }
            val drawdown = peakBalance - runningBalance
            if (drawdown > maxDrawdown) {
                maxDrawdown = drawdown
            }

            runningBalance = balance
        }

        // 计算平均持仓时间
        var avgHoldingTime: Long? = null
        if (trades.size > 1) {
            var totalHoldingTime = 0L
            var count = 0
            for (i in 0 until trades.size - 1) {
                val currentTrade = trades[i]
                val nextTrade = trades[i + 1]

                if (currentTrade.side == "BUY" && nextTrade.side == "SELL") {
                    val holdingTime = nextTrade.tradeTime - currentTrade.tradeTime
                    totalHoldingTime += holdingTime
                    count++
                }
            }

            if (count > 0) {
                avgHoldingTime = totalHoldingTime / count
            }
        }

        return BacktestStatisticsDto(
            totalTrades = trades.size,
            buyTrades = buyTrades,
            sellTrades = sellTrades,
            winTrades = winTrades,
            lossTrades = lossTrades,
            winRate = if (buyTrades + sellTrades > 0) {
                (winTrades.toBigDecimal().divide((buyTrades + sellTrades).toBigDecimal(), 4, java.math.RoundingMode.HALF_UP))
                    .multiply(BigDecimal("100"))
                    .toPlainString()
            } else {
                BigDecimal.ZERO.toPlainString()
            },
            maxProfit = maxProfit.toPlainString(),
            maxLoss = maxLoss.toPlainString(),
            maxDrawdown = maxDrawdown.toPlainString(),
            avgHoldingTime = avgHoldingTime
        )
    }

    /**
     * 计算跟单金额
     */
    private fun calculateFollowAmount(task: BacktestTask, leaderTrade: TradeData): BigDecimal {
        return if (task.copyMode == "RATIO") {
            // 比例模式：Leader 成交金额 × 跟单比例
            leaderTrade.amount.toSafeBigDecimal().multiply(task.copyRatio)
        } else {
            // 固定金额模式：使用配置的固定金额
            task.fixedAmount ?: leaderTrade.amount.toSafeBigDecimal()
        }
    }

    /**
     * 判断是否同一天
     */
    private fun isSameDay(timestamp1: Long, timestamp2: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = timestamp1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = timestamp2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * 格式化时间戳
     */
    private fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        return sdf.format(Date(timestamp))
    }

    /**
     * 格式化日期（用于缓存key）
     */
    private fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd")
        return sdf.format(Date(timestamp))
    }
}
