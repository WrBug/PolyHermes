package com.wrbug.polymarketbot.service.backtest

import com.wrbug.polymarketbot.dto.TradeData
import com.wrbug.polymarketbot.dto.BacktestStatisticsDto
import com.wrbug.polymarketbot.entity.BacktestTask
import com.wrbug.polymarketbot.entity.BacktestTrade
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.repository.BacktestTradeRepository
import com.wrbug.polymarketbot.repository.BacktestTaskRepository
import com.wrbug.polymarketbot.service.common.MarketPriceService
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingFilterService
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

@Service
class BacktestExecutionService(
    private val backtestTaskRepository: BacktestTaskRepository,
    private val backtestTradeRepository: BacktestTradeRepository,
    private val backtestDataService: BacktestDataService,
    private val marketPriceService: MarketPriceService,
    private val marketService: MarketService,
    private val copyTradingFilterService: CopyTradingFilterService
) {
    private val logger = LoggerFactory.getLogger(BacktestExecutionService::class.java)

    /**
     * 持仓数据结构
     * @param marketEndDate 市场结束时间（毫秒），用于到期结算判断，null 表示未知
     */
    data class Position(
        val marketId: String,
        val outcome: String,
        val outcomeIndex: Int?,
        var quantity: BigDecimal,
        val avgPrice: BigDecimal,
        val leaderBuyQuantity: BigDecimal?,
        val marketEndDate: Long? = null
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
            maxPositionValue = task.maxPositionValue,
            minPrice = task.minPrice,  // 最低价格
            maxPrice = task.maxPrice,  // 最高价格
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
    /** 每批请求 API 的条数（基于 start 游标分页，避免 offset 过大） */
    private val backtestBatchLimit = 500

    @Transactional
    suspend fun executeBacktest(task: BacktestTask, page: Int = 1, size: Int = 100) {
        try {
            logger.info("开始执行回测任务: taskId=${task.id}, taskName=${task.taskName}, batchLimit=$backtestBatchLimit")

            // 1. 更新任务状态为 RUNNING
            task.status = "RUNNING"
            task.executionStartedAt = System.currentTimeMillis()
            task.updatedAt = System.currentTimeMillis()
            backtestTaskRepository.save(task)

            // 2. 初始化
            var currentBalance = task.initialBalance
            val positions = mutableMapOf<String, Position>()
            val trades = mutableListOf<BacktestTrade>()
            val dailyOrderCountCache = mutableMapOf<String, Int>()
            val dailyLossCache = mutableMapOf<String, BigDecimal>()
            val seenTradeIds = mutableSetOf<String>()

            // 3. 回测时间范围：首次执行以当前时间为基准取最近 backtestDays 天；断点续跑保留原 startTime，仅 endTime 延到当前
            val endTime = System.currentTimeMillis()
            val startTime = if (task.lastProcessedTradeTime == null) {
                endTime - (task.backtestDays * 24L * 3600 * 1000)
            } else {
                task.startTime
            }

            logger.info("回测时间范围: ${formatTimestamp(startTime)} - ${formatTimestamp(endTime)} (${task.backtestDays} 天), " +
                "初始余额: ${task.initialBalance.toPlainString()}")

            // 4. 游标分页：恢复时也从 lastProcessedTradeTime 所在秒开始拉（不加 1），与分页规则一致；已处理的通过 timestamp 跳过，不依赖内存 seenTradeIds
            var cursorSeconds = if (task.lastProcessedTradeTime != null) {
                task.lastProcessedTradeTime!! / 1000
            } else {
                startTime / 1000
            }
            val endSeconds = endTime / 1000
            val resumeThresholdMs = task.lastProcessedTradeTime ?: 0L

            logger.info("开始游标分页：cursorStart=$cursorSeconds（恢复则跳过 timestamp<=${resumeThresholdMs}ms）")

            var terminateBacktest = false
            while (true) {
                if (terminateBacktest) {
                    logger.info("余额已为负或不足，终止回测循环")
                    break
                }
                val currentTaskStatus = backtestTaskRepository.findById(task.id!!).orElse(null)
                if (currentTaskStatus == null || currentTaskStatus.status != "RUNNING") {
                    logger.info("回测任务状态已变更: ${currentTaskStatus?.status}，停止执行")
                    break
                }

                logger.info("正在获取批次数据 cursorStart=$cursorSeconds (${formatTimestamp(cursorSeconds * 1000)}) ...")

                val currentPageTrades = mutableListOf<BacktestTrade>()

                try {
                    val batch = backtestDataService.getLeaderHistoricalTradesBatch(
                        task.leaderId,
                        startTime,
                        endTime,
                        cursorSeconds,
                        backtestBatchLimit
                    )
                    val pageTrades = batch.trades

                    if (pageTrades.isEmpty()) {
                        logger.info("本批无数据，所有数据处理完成")
                        break
                    }

                    logger.info("本批获取 ${pageTrades.size} 条交易，是否有下一页: ${batch.nextCursorSeconds != null}")

                    val countAtBatchStart = task.processedTradeCount
                    var lastProcessedIndexInPage: Int? = null
                    var processedInBatch = 0
                    for (localIndex in pageTrades.indices) {
                        val leaderTrade = pageTrades[localIndex]
                        if (leaderTrade.tradeId in seenTradeIds) {
                            logger.debug("跳过重复交易: ${leaderTrade.tradeId}")
                            continue
                        }
                        if (resumeThresholdMs > 0 && leaderTrade.timestamp <= resumeThresholdMs) {
                            logger.debug("恢复时跳过已处理时间戳: tradeId=${leaderTrade.tradeId}, timestamp=${leaderTrade.timestamp}")
                            continue
                        }
                        seenTradeIds.add(leaderTrade.tradeId)

                        val index = countAtBatchStart + processedInBatch
                        lastProcessedIndexInPage = index
                        processedInBatch++

                        // 进度按时间比例：(当前订单时间 - 开始时间) / (结束时间 - 开始时间) * 100，运行中上限 99
                        val timeRange = endTime - startTime
                        val progress = if (timeRange > 0) {
                            val elapsed = (leaderTrade.timestamp - startTime).coerceIn(0L, timeRange)
                            min(99, ((elapsed * 100) / timeRange).toInt())
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
                            currentBalance = settleExpiredPositions(task, positions, currentBalance, trades, leaderTrade.timestamp, currentPageTrades)

                            // 5.2 检查余额和持仓状态
                            if (currentBalance <= BigDecimal.ONE) {
                                logger.info(
                                    if (currentBalance < BigDecimal.ZERO) "余额已为负，直接终止回测: $currentBalance"
                                    else "余额<=1，停止回测: $currentBalance"
                                )
                                terminateBacktest = true
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
                                // 余额不足时按最大可用余额交易，仍须满足最小订单金额
                                val actualBuyAmount = if (currentBalance < finalFollowAmount) {
                                    logger.debug("余额不足，按最大余额买入: balance=$currentBalance, 原需=$finalFollowAmount, marketId=${leaderTrade.marketId}")
                                    currentBalance
                                } else {
                                    finalFollowAmount
                                }
                                if (actualBuyAmount < task.minOrderSize) {
                                    logger.debug("可用金额低于最小订单限制跳过: actual=$actualBuyAmount, minOrderSize=${task.minOrderSize}")
                                    continue
                                }
                                val quantity = actualBuyAmount.divide(leaderTrade.price, 8, java.math.RoundingMode.DOWN)
                                if (quantity <= BigDecimal.ZERO) {
                                    logger.debug("计算数量为0跳过: actualBuyAmount=$actualBuyAmount, price=${leaderTrade.price}")
                                    continue
                                }
                                val totalCost = actualBuyAmount

                                // 5.6.3 检查最大仓位限制（如果配置了）
                                if (task.maxPositionValue != null) {
                                    val positionKey = "${leaderTrade.marketId}:${leaderTrade.outcomeIndex ?: 0}"
                                    val currentPosition = positions[positionKey]
                                    val currentPositionValue = if (currentPosition != null) {
                                        currentPosition.quantity.multiply(currentPosition.avgPrice)
                                    } else {
                                        BigDecimal.ZERO
                                    }
                                    val totalValueAfterOrder = currentPositionValue.add(actualBuyAmount)
                                    
                                    if (totalValueAfterOrder.gt(task.maxPositionValue)) {
                                        val currentPositionValueStr = currentPositionValue.stripTrailingZeros().toPlainString()
                                        val totalValueStr = totalValueAfterOrder.stripTrailingZeros().toPlainString()
                                        val maxValueStr = task.maxPositionValue.stripTrailingZeros().toPlainString()
                                        logger.info("超过最大仓位金额限制: 市场=${leaderTrade.marketId}, 方向=${leaderTrade.outcomeIndex}, 当前仓位=${currentPositionValueStr} USDC, 买入金额=${actualBuyAmount} USDC, 总计=${totalValueStr} USDC > 最大限制=${maxValueStr} USDC")
                                        continue
                                    }
                                }

                                // 更新余额和持仓（同市场同 outcome 多次买入合并：数量相加、加权均价、leaderBuyQuantity 相加）
                                currentBalance -= totalCost
                                val positionKey = "${leaderTrade.marketId}:${leaderTrade.outcomeIndex ?: 0}"
                                val price = leaderTrade.price.toSafeBigDecimal()
                                val leaderSize = leaderTrade.size.toSafeBigDecimal()
                                val existing = positions[positionKey]
                                positions[positionKey] = if (existing != null) {
                                    val newQuantity = existing.quantity.add(quantity)
                                    val newAvgPrice = if (newQuantity > BigDecimal.ZERO) {
                                        existing.quantity.multiply(existing.avgPrice).add(quantity.multiply(price))
                                            .divide(newQuantity, 8, java.math.RoundingMode.HALF_UP)
                                    } else {
                                        price
                                    }
                                    val newLeaderBuyQuantity = (existing.leaderBuyQuantity ?: BigDecimal.ZERO).add(leaderSize)
                                    Position(
                                        marketId = leaderTrade.marketId,
                                        outcome = leaderTrade.outcome ?: "",
                                        outcomeIndex = leaderTrade.outcomeIndex,
                                        quantity = newQuantity,
                                        avgPrice = newAvgPrice,
                                        leaderBuyQuantity = newLeaderBuyQuantity,
                                        marketEndDate = existing.marketEndDate
                                    )
                                } else {
                                    val market = marketService.getMarket(leaderTrade.marketId)
                                    Position(
                                        marketId = leaderTrade.marketId,
                                        outcome = leaderTrade.outcome ?: "",
                                        outcomeIndex = leaderTrade.outcomeIndex,
                                        quantity = quantity,
                                        avgPrice = price,
                                        leaderBuyQuantity = leaderSize,
                                        marketEndDate = market?.endDate
                                    )
                                }

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
                                    amount = actualBuyAmount,
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

                    // 保存本批交易
                    if (currentPageTrades.isNotEmpty()) {
                        logger.info("保存本批交易，共 ${currentPageTrades.size} 笔")
                        backtestTradeRepository.saveAll(currentPageTrades)

                        val lastTradeInPage = currentPageTrades.lastOrNull()
                        if (lastTradeInPage != null && lastProcessedIndexInPage != null) {
                            task.lastProcessedTradeTime = lastTradeInPage.tradeTime
                            task.lastProcessedTradeIndex = lastProcessedIndexInPage
                            task.processedTradeCount = lastProcessedIndexInPage + 1
                            task.finalBalance = currentBalance
                            backtestTaskRepository.save(task)
                            logger.info("本批处理完成，lastProcessedTradeIndex=${task.lastProcessedTradeIndex}, 总处理数=${task.processedTradeCount}")
                        }
                    } else {
                        logger.info("本批没有交易需要保存")
                    }

                    trades.addAll(currentPageTrades)

                    if (batch.nextCursorSeconds == null) {
                        logger.info("本批不足 $backtestBatchLimit 条，已是最后一页")
                        break
                    }
                    cursorSeconds = batch.nextCursorSeconds!!

                } catch (e: Exception) {
                    logger.error("获取或处理本批数据失败: ${e.message}", e)
                    // 重试失败，标记任务为 FAILED
                    throw e
                }
            }

            // 6. 处理回测结束时仍未到期的持仓
            val remainingSettlements = mutableListOf<BacktestTrade>()
            currentBalance = settleRemainingPositions(task, positions, currentBalance, trades, endTime, remainingSettlements)
            if (remainingSettlements.isNotEmpty()) {
                backtestTradeRepository.saveAll(remainingSettlements)
                logger.info("回测结束结算剩余持仓，持久化 ${remainingSettlements.size} 笔 SETTLEMENT(CLOSED)")
            }

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
     * @param batchTradesToSave 本批要持久化的交易列表，到期结算（赎回/输）会追加到此列表并随本批一起落库
     */
    private suspend fun settleExpiredPositions(
        task: BacktestTask,
        positions: MutableMap<String, Position>,
        currentBalance: BigDecimal,
        trades: MutableList<BacktestTrade>,
        currentTime: Long,
        batchTradesToSave: MutableList<BacktestTrade>
    ): BigDecimal {
        var balance = currentBalance

        for ((positionKey, position) in positions.toList()) {
            try {
                // 仅当市场已到期（结束时间 <= 当前回测时间）时才结算，避免未到期持仓被误结算
                if (position.marketEndDate == null || position.marketEndDate!! > currentTime) {
                    logger.debug("持仓未到期跳过结算: marketId=${position.marketId}, endDate=${position.marketEndDate}, currentTime=$currentTime")
                    continue
                }
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

                val marketTitle = marketService.getMarket(position.marketId)?.title ?: ""
                val settlementTrade = BacktestTrade(
                    backtestTaskId = task.id!!,
                    tradeTime = currentTime,
                    marketId = position.marketId,
                    marketTitle = marketTitle,
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
                )
                trades.add(settlementTrade)
                batchTradesToSave.add(settlementTrade)

                // 移除已结算的持仓
                positions.remove(positionKey)
            } catch (e: Exception) {
                logger.error("结算市场失败: marketId=${position.marketId}, outcomeIndex=${position.outcomeIndex}", e)
            }
        }

        return balance
    }

    /**
     * 结算未到期持仓（回测结束时剩余持仓按均价平仓）
     * @param settlementsToSave 本批结算记录会追加到此列表，调用方需落库
     */
    private suspend fun settleRemainingPositions(
        task: BacktestTask,
        positions: MutableMap<String, Position>,
        currentBalance: BigDecimal,
        trades: MutableList<BacktestTrade>,
        currentTime: Long,
        settlementsToSave: MutableList<BacktestTrade>
    ): BigDecimal {
        var balance = currentBalance

        for ((positionKey, position) in positions.toList()) {
            val quantity = position.quantity
            val avgPrice = position.avgPrice
            val settlementPrice = avgPrice

            val settlementValue = quantity.multiply(settlementPrice)
            val profitLoss = settlementValue.negate()

            balance += settlementValue

            val marketTitle = marketService.getMarket(position.marketId)?.title ?: ""
            val closedTrade = BacktestTrade(
                backtestTaskId = task.id!!,
                tradeTime = currentTime,
                marketId = position.marketId,
                marketTitle = marketTitle,
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
            )
            trades.add(closedTrade)
            settlementsToSave.add(closedTrade)
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
