package com.wrbug.polymarketbot.service.backtest

import com.wrbug.polymarketbot.entity.BacktestTask
import com.wrbug.polymarketbot.repository.BacktestTaskRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import kotlinx.coroutines.runBlocking

/**
 * 回测轮询服务
 * 定时获取待执行的回测任务并执行
 */
@Service
class BacktestPollingService(
    private val backtestTaskRepository: BacktestTaskRepository,
    private val executionService: BacktestExecutionService
) {
    private val logger = LoggerFactory.getLogger(BacktestPollingService::class.java)

    // 线程池：同一时刻只执行一个任务
    private val executor: ExecutorService = Executors.newFixedThreadPool(1) as ThreadPoolExecutor

    /**
     * 轮询待执行的回测任务
     * 每 10 秒执行一次
     * 规则：同一时刻只执行一个任务，如果有多个待执行任务，按创建时间先后执行最早创建的
     */
    @Scheduled(fixedDelay = 10000) // 10 秒
    fun pollPendingTasks() {
        try {
            // 1. 检查是否有长时间处于 RUNNING 状态的任务（可能是应用重启导致的）
            val runningTasks = backtestTaskRepository.findByStatus("RUNNING")
            if (runningTasks.isNotEmpty()) {
                val activeQueueSize = (executor as ThreadPoolExecutor).queue.size
                val activeCount = (executor as ThreadPoolExecutor).activeCount

                // 如果有线程池中没有活跃任务但有 RUNNING 状态的任务，说明是应用重启导致的
                // 重置这些任务的状态为 PENDING，以便恢复执行
                if (activeCount == 0 && runningTasks.isNotEmpty()) {
                    logger.info("检测到应用重启导致的异常 RUNNING 任务，重置为 PENDING 以便恢复")
                    runningTasks.forEach { task ->
                        val now = System.currentTimeMillis()
                        val executionStartedAt = task.executionStartedAt
                        val executionDuration = if (executionStartedAt != null) {
                            now - executionStartedAt
                        } else {
                            0L
                        }

                        // 如果任务执行时间超过 1 分钟，认为是异常状态
                        if (executionDuration > 60000) {
                            logger.info("重置异常 RUNNING 任务: taskId=${task.id}, executionStartedAt=$executionStartedAt, duration=${executionDuration}ms")
                            task.status = "PENDING"
                            task.updatedAt = now
                            backtestTaskRepository.save(task)
                        }
                    }
                } else {
                logger.debug("有 ${runningTasks.size} 个任务正在执行，跳过本次轮询")
                return
                }
            }

            // 2. 查询所有 PENDING 状态的任务，按创建时间升序排序
            val pendingTasks = backtestTaskRepository.findByStatus("PENDING")
                .sortedBy { it.createdAt }

            if (pendingTasks.isEmpty()) {
                return
            }

            // 3. 只执行最早创建的任务
            val taskToExecute = pendingTasks.first()
            logger.info("找到 ${pendingTasks.size} 个待执行的回测任务，执行最早创建的任务: taskId=${taskToExecute.id}, createdAt=${taskToExecute.createdAt}")

            // 4. 提交任务到线程池执行
            executor.submit {
                try {
                    // 执行前再次检查任务状态（防止并发执行）
                    val currentTask = backtestTaskRepository.findById(taskToExecute.id!!).orElse(null)
                    if (currentTask == null || currentTask.status != "PENDING") {
                        logger.debug("任务状态已变更，跳过执行: taskId=${taskToExecute.id}, currentStatus=${currentTask?.status}")
                        return@submit
                    }

                    runBlocking {
                        // 使用 start 游标分页，恢复时由 lastProcessedTradeTime 决定从何时开始拉取
                        logger.info("执行回测任务: taskId=${currentTask.id}（游标分页，limit=500）")
                        executionService.executeBacktest(currentTask, page = 0, size = 500)
                    }
                } catch (e: Exception) {
                    logger.error("回测任务执行失败: taskId=${taskToExecute.id}", e)
                    // 更新任务状态为 FAILED
                    val failedTask = backtestTaskRepository.findById(taskToExecute.id!!).orElse(null)
                    if (failedTask != null) {
                        failedTask.status = "FAILED"
                        failedTask.errorMessage = e.message
                        failedTask.updatedAt = System.currentTimeMillis()
                        backtestTaskRepository.save(failedTask)
                    }
                }
            }

        } catch (e: Exception) {
            logger.error("轮询回测任务失败", e)
        }
    }

}
