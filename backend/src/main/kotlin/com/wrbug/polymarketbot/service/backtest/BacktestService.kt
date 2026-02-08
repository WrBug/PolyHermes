package com.wrbug.polymarketbot.service.backtest

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.BacktestTask
import com.wrbug.polymarketbot.entity.BacktestTrade
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.repository.BacktestTaskRepository
import com.wrbug.polymarketbot.repository.BacktestTradeRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.wrbug.polymarketbot.util.toJson
import com.wrbug.polymarketbot.util.fromJson
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * 回测任务服务
 */
@Service
class BacktestService(
    private val backtestTaskRepository: BacktestTaskRepository,
    private val backtestTradeRepository: BacktestTradeRepository,
    private val leaderRepository: LeaderRepository,
    private val messageSource: MessageSource
) {
    private val logger = LoggerFactory.getLogger(BacktestService::class.java)

    /**
     * 创建回测任务
     */
    @Transactional
    fun createBacktestTask(request: BacktestCreateRequest): Result<BacktestTaskDto> {
        return try {
            // 1. 验证 Leader 是否存在
            val leader = leaderRepository.findById(request.leaderId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Leader 不存在"))

            // 2. 验证回测天数
            if (request.backtestDays < 1 || request.backtestDays > 15) {
                return Result.failure(IllegalArgumentException("回测天数必须在 1-15 之间"))
            }

            // 3. 验证恢复页码（如果提供）
            if (request.pageForResume != null && request.pageForResume < 1) {
                return Result.failure(IllegalArgumentException("恢复页码必须大于 0"))
            }

            // 4. 验证初始金额
            val initialBalance = request.initialBalance.toSafeBigDecimal()
            if (initialBalance <= BigDecimal.ZERO) {
                return Result.failure(IllegalArgumentException("初始金额必须大于 0"))
            }

            // 4. 创建回测任务
            val task = BacktestTask(
                taskName = request.taskName.trim(),
                leaderId = request.leaderId,
                initialBalance = initialBalance,
                backtestDays = request.backtestDays,
                startTime = System.currentTimeMillis() - (request.backtestDays * 24 * 3600 * 1000),
                status = "PENDING",

                // 跟单配置（不包含 max_position_count）
                copyMode = request.copyMode ?: "RATIO",
                copyRatio = request.copyRatio?.toSafeBigDecimal() ?: BigDecimal.ONE,
                fixedAmount = request.fixedAmount?.toSafeBigDecimal(),
                maxOrderSize = request.maxOrderSize?.toSafeBigDecimal() ?: "1000".toSafeBigDecimal(),
                minOrderSize = request.minOrderSize?.toSafeBigDecimal() ?: "1".toSafeBigDecimal(),
                maxDailyLoss = request.maxDailyLoss?.toSafeBigDecimal() ?: "10000".toSafeBigDecimal(),
                maxDailyOrders = request.maxDailyOrders ?: 100,
                supportSell = request.supportSell ?: true,
                keywordFilterMode = request.keywordFilterMode ?: "DISABLED",
                keywords = if (request.keywords != null && request.keywords.isNotEmpty()) {
                    request.keywords.toJson()
                } else {
                    null
                }
            )

            backtestTaskRepository.save(task)

            // 5. 转换为 DTO 返回
            Result.success(task.toDto(leader))
        } catch (e: Exception) {
            logger.error("创建回测任务失败", e)
            Result.failure(e)
        }
    }

    /**
     * 查询回测任务列表
     */
    fun getBacktestTaskList(request: BacktestListRequest): Result<BacktestListResponse> {
        return try {
            // 获取所有符合条件的任务
            val allTasks = when {
                request.leaderId != null && request.status != null -> {
                    backtestTaskRepository.findByLeaderIdAndStatus(request.leaderId, request.status)
                }
                request.leaderId != null -> {
                    backtestTaskRepository.findByLeaderId(request.leaderId)
                        .filter { request.status == null || it.status == request.status }
                }
                request.status != null -> {
                    backtestTaskRepository.findByStatus(request.status)
                }
                else -> {
                    backtestTaskRepository.findAll()
                }
            }

            // 排序
            val sortedTasks = when (request.sortBy) {
                "profitAmount" -> {
                    if (request.sortOrder == "asc") {
                        allTasks.sortedBy { it.profitAmount }
                    } else {
                        allTasks.sortedByDescending { it.profitAmount }
                    }
                }
                "profitRate" -> {
                    if (request.sortOrder == "asc") {
                        allTasks.sortedBy { it.profitRate }
                    } else {
                        allTasks.sortedByDescending { it.profitRate }
                    }
                }
                else -> {
                    if (request.sortOrder == "asc") {
                        allTasks.sortedBy { it.createdAt }
                    } else {
                        allTasks.sortedByDescending { it.createdAt }
                    }
                }
            }

            // 分页
            val total = sortedTasks.size
            val pagedTasks = sortedTasks
                .drop((request.page - 1) * request.size)
                .take(request.size)

            val list = pagedTasks.map { task ->
                val leader = leaderRepository.findById(task.leaderId).orElse(null)
                task.toDto(leader)
            }

            Result.success(
                BacktestListResponse(
                    list = list,
                    total = total.toLong(),
                    page = request.page,
                    size = request.size
                )
            )
        } catch (e: Exception) {
            logger.error("查询回测任务列表失败", e)
            Result.failure(e)
        }
    }

    /**
     * 查询回测任务详情
     */
    fun getBacktestTaskDetail(request: BacktestDetailRequest): Result<BacktestDetailResponse> {
        return try {
            val task = backtestTaskRepository.findById(request.id).orElse(null)
                ?: return Result.failure(IllegalArgumentException("回测任务不存在"))

            val leader = leaderRepository.findById(task.leaderId).orElse(null)

            val config = BacktestConfigDto(
                copyMode = task.copyMode,
                copyRatio = task.copyRatio.toPlainString(),
                fixedAmount = task.fixedAmount?.toPlainString(),
                maxOrderSize = task.maxOrderSize.toPlainString(),
                minOrderSize = task.minOrderSize.toPlainString(),
                maxDailyLoss = task.maxDailyLoss.toPlainString(),
                maxDailyOrders = task.maxDailyOrders,
                supportSell = task.supportSell,
                keywordFilterMode = task.keywordFilterMode,
                keywords = if (task.keywords != null) {
                    task.keywords.fromJson<List<String>>()
                } else {
                    emptyList()
                }
            )

            val statistics = BacktestStatisticsDto(
                totalTrades = task.totalTrades,
                buyTrades = task.buyTrades,
                sellTrades = task.sellTrades,
                winTrades = task.winTrades,
                lossTrades = task.lossTrades,
                winRate = task.winRate?.toPlainString() ?: "0.00",
                maxProfit = task.maxProfit?.toPlainString() ?: "0.00",
                maxLoss = task.maxLoss?.toPlainString() ?: "0.00",
                maxDrawdown = task.maxDrawdown?.toPlainString() ?: "0.00",
                avgHoldingTime = task.avgHoldingTime
            )

            val taskDto = task.toDto(leader)

            Result.success(
                BacktestDetailResponse(
                    task = taskDto,
                    config = config,
                    statistics = statistics
                )
            )
        } catch (e: Exception) {
            logger.error("查询回测任务详情失败", e)
            Result.failure(e)
        }
    }

    /**
     * 查询回测交易记录
     */
    fun getBacktestTrades(request: BacktestTradeListRequest): Result<BacktestTradeListResponse> {
        return try {
            val pageRequest = PageRequest.of(
                request.page - 1,
                request.size,
                Sort.by(Sort.Order.asc("tradeTime"))
            )

            val tradesPage = backtestTradeRepository.findByBacktestTaskId(
                request.taskId,
                pageRequest
            )

            val list = tradesPage.content.map { trade ->
                BacktestTradeDto(
                    id = trade.id!!,
                    tradeTime = trade.tradeTime,
                    marketId = trade.marketId,
                    marketTitle = trade.marketTitle,
                    side = trade.side,
                    outcome = trade.outcome,
                    outcomeIndex = trade.outcomeIndex,
                    quantity = trade.quantity.toPlainString(),
                    price = trade.price.toPlainString(),
                    amount = trade.amount.toPlainString(),
                    fee = trade.fee.toPlainString(),
                    profitLoss = trade.profitLoss?.toPlainString(),
                    balanceAfter = trade.balanceAfter.toPlainString(),
                    leaderTradeId = trade.leaderTradeId
                )
            }

            Result.success(
                BacktestTradeListResponse(
                    list = list,
                    total = tradesPage.totalElements,
                    page = request.page,
                    size = request.size
                )
            )
        } catch (e: Exception) {
            logger.error("查询回测交易记录失败", e)
            Result.failure(e)
        }
    }

    /**
     * 删除回测任务
     */
    @Transactional
    fun deleteBacktestTask(request: BacktestDeleteRequest): Result<Unit> {
        return try {
            val task = backtestTaskRepository.findById(request.id).orElse(null)
                ?: return Result.failure(IllegalArgumentException("回测任务不存在"))

            if (task.status == "RUNNING") {
                return Result.failure(IllegalStateException("回测任务正在运行，无法删除"))
            }

            backtestTaskRepository.deleteById(request.id)
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除回测任务失败", e)
            Result.failure(e)
        }
    }

    /**
     * 停止回测任务
     */
    @Transactional
    fun stopBacktestTask(request: BacktestStopRequest): Result<Unit> {
        return try {
            val task = backtestTaskRepository.findById(request.id).orElse(null)
                ?: return Result.failure(IllegalArgumentException("回测任务不存在"))

            if (task.status != "RUNNING") {
                return Result.failure(IllegalArgumentException("回测任务未在运行中"))
            }

            task.status = "STOPPED"
            task.updatedAt = System.currentTimeMillis()
            backtestTaskRepository.save(task)

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("停止回测任务失败", e)
            Result.failure(e)
        }
    }

    /**
     * 重试回测任务
     * 从断点继续执行，保留已处理的交易记录
     */
    @Transactional
    fun retryBacktestTask(request: BacktestRetryRequest): Result<Unit> {
        return try {
            val task = backtestTaskRepository.findById(request.id).orElse(null)
                ?: return Result.failure(IllegalArgumentException("回测任务不存在"))

            if (task.status == "RUNNING") {
                return Result.failure(IllegalArgumentException("回测任务正在运行中，无需重试"))
            }

            // 重置任务状态为 PENDING，进度保持不变
            task.status = "PENDING"
            task.errorMessage = null
            task.updatedAt = System.currentTimeMillis()

            // 不清理已处理的交易记录，保留恢复点
            backtestTaskRepository.save(task)

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("重试回测任务失败", e)
            Result.failure(e)
        }
    }

    /**
     * 按当前配置重新测试：基于已完成的回测任务创建一份相同配置的新任务（名称可修改）
     */
    @Transactional
    fun rerunBacktestTask(request: BacktestRerunRequest): Result<BacktestTaskDto> {
        return try {
            val source = backtestTaskRepository.findById(request.id).orElse(null)
                ?: return Result.failure(IllegalArgumentException("回测任务不存在"))

            if (source.status != "COMPLETED") {
                return Result.failure(IllegalStateException("仅支持对已完成的回测任务重新测试"))
            }

            val newTaskName = request.taskName?.trim()?.takeIf { it.isNotEmpty() }
                ?: "${source.taskName} (副本)"

            val newTask = BacktestTask(
                taskName = newTaskName,
                leaderId = source.leaderId,
                initialBalance = source.initialBalance,
                backtestDays = source.backtestDays,
                startTime = source.startTime,
                status = "PENDING",
                copyMode = source.copyMode,
                copyRatio = source.copyRatio,
                fixedAmount = source.fixedAmount,
                maxOrderSize = source.maxOrderSize,
                minOrderSize = source.minOrderSize,
                maxDailyLoss = source.maxDailyLoss,
                maxDailyOrders = source.maxDailyOrders,
                supportSell = source.supportSell,
                keywordFilterMode = source.keywordFilterMode,
                keywords = source.keywords
            )

            backtestTaskRepository.save(newTask)
            val leader = leaderRepository.findById(newTask.leaderId).orElse(null)
            Result.success(newTask.toDto(leader))
        } catch (e: Exception) {
            logger.error("按配置重新测试失败", e)
            Result.failure(e)
        }
    }
}

/**
 * 扩展函数：BacktestTask 转 DTO
 */
private fun BacktestTask.toDto(leader: Leader?): BacktestTaskDto {
    return BacktestTaskDto(
        id = this.id!!,
        taskName = this.taskName,
        leaderId = this.leaderId,
        leaderName = leader?.leaderName,
        leaderAddress = leader?.leaderAddress,
        initialBalance = this.initialBalance.toPlainString(),
        finalBalance = this.finalBalance?.toPlainString(),
        profitAmount = this.profitAmount?.toPlainString(),
        profitRate = this.profitRate?.toPlainString(),
        backtestDays = this.backtestDays,
        startTime = this.startTime,
        endTime = this.endTime,
        status = this.status,
        progress = this.progress,
        totalTrades = this.totalTrades,
        createdAt = this.createdAt,
        executionStartedAt = this.executionStartedAt,
        executionFinishedAt = this.executionFinishedAt
    )
}

