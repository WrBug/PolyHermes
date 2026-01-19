package com.wrbug.polymarketbot.service.common

import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * 市场信息轮询服务
 * 定期检查买入订单的市场信息是否缺失，如果缺失则从API获取并更新
 */
@Service
class MarketPollingService(
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val marketService: MarketService
) {
    
    private val logger = LoggerFactory.getLogger(MarketPollingService::class.java)
    
    @Value("\${market.polling.interval:30000}")
    private var pollingInterval: Long = 30000  // 轮询间隔（毫秒），默认30秒
    
    @Value("\${market.polling.batch.size:50}")
    private var batchSize: Int = 50  // 批量处理大小，每次最多处理50个市场
    
    // 协程作用域和任务
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pollingJob: Job? = null
    
    // 同步锁，确保轮询任务的启动和停止是线程安全的
    private val lock = Any()
    
    /**
     * 初始化服务（后端启动时直接启动轮询）
     */
    @PostConstruct
    fun init() {
        logger.info("MarketPollingService 初始化，启动市场信息轮询任务，轮询间隔: ${pollingInterval}ms (${pollingInterval / 1000 / 60}分钟)")
        startPolling()
    }
    
    /**
     * 清理资源
     */
    @PreDestroy
    fun destroy() {
        synchronized(lock) {
            pollingJob?.cancel()
            pollingJob = null
        }
        scope.cancel()
    }
    
    /**
     * 启动轮询任务
     */
    private fun startPolling() {
        synchronized(lock) {
            // 如果已经有轮询任务在运行，先取消
            pollingJob?.cancel()
            
            // 启动新的轮询任务
            pollingJob = scope.launch(Dispatchers.IO) {
                // 启动时立即执行一次
                try {
                    checkAndUpdateMissingMarkets()
                } catch (e: Exception) {
                    logger.error("初始检查市场信息失败: ${e.message}", e)
                }
                
                // 然后按间隔定期执行
                while (isActive) {
                    try {
                        delay(pollingInterval)
                        checkAndUpdateMissingMarkets()
                    } catch (e: Exception) {
                        logger.error("轮询市场信息失败: ${e.message}", e)
                    }
                }
            }
        }
    }
    
    /**
     * 检查并更新缺失的市场信息
     */
    private suspend fun checkAndUpdateMissingMarkets() {
        try {
            // 1. 获取所有买入订单的市场ID（去重）
            val allOrders = copyOrderTrackingRepository.findAll()
            val marketIds = allOrders.map { it.marketId }.distinct()
            
            if (marketIds.isEmpty()) {
                logger.debug("没有找到任何订单，跳过市场信息检查")
                return
            }
            // 2. 检查哪些市场信息在数据库中缺失
            val existingMarkets = marketService.marketRepository.findByMarketIdIn(marketIds)
            val existingMarketIds = existingMarkets.map { it.marketId }.toSet()
            val missingMarketIds = marketIds.filter { it !in existingMarketIds }
            
            // 过滤掉空字符串和无效的市场ID
            val validMissingMarketIds = missingMarketIds.filter { 
                it.isNotBlank() && it.startsWith("0x") 
            }
            
            if (validMissingMarketIds.isEmpty()) {
                return
            }
            
            logger.info("发现 ${validMissingMarketIds.size} 个缺失的市场信息，开始批量更新...")
            
            // 3. 批量从API获取缺失的市场信息（分批处理，避免一次性请求过多）
            val batches = validMissingMarketIds.chunked(batchSize)
            var successCount = 0
            var failCount = 0
            
            for ((index, batch) in batches.withIndex()) {
                try {
                    logger.debug("处理第 ${index + 1}/${batches.size} 批，包含 ${batch.size} 个市场: ${batch.take(3).joinToString(", ")}${if (batch.size > 3) "..." else ""}")
                    
                    // 使用 MarketService 的批量获取方法
                    // 这个方法会尝试从API获取并保存到数据库
                    val markets = marketService.getMarkets(batch)
                    val batchSuccessCount = markets.size
                    val batchFailCount = batch.size - batchSuccessCount
                    
                    successCount += batchSuccessCount
                    failCount += batchFailCount
                    
                    if (batchFailCount > 0) {
                        logger.warn("第 ${index + 1} 批中有 ${batchFailCount} 个市场信息获取失败")
                    }
                    
                    // 避免请求过于频繁，每批之间稍作延迟
                    if (index < batches.size - 1) {
                        delay(1000)  // 延迟1秒，避免API限流
                    }
                } catch (e: Exception) {
                    logger.error("批量获取市场信息失败: batch=${batch.take(5).joinToString(", ")}..., error=${e.message}", e)
                    failCount += batch.size
                }
            }
            
            logger.info("市场信息更新完成: 成功=${successCount}, 失败=${failCount}, 总计=${validMissingMarketIds.size}")
        } catch (e: Exception) {
            logger.error("检查并更新市场信息异常: ${e.message}", e)
        }
    }
    
    /**
     * 手动触发检查（用于测试或手动刷新）
     */
    fun triggerCheck() {
        scope.launch(Dispatchers.IO) {
            try {
                checkAndUpdateMissingMarkets()
            } catch (e: Exception) {
                logger.error("手动触发检查市场信息失败: ${e.message}", e)
            }
        }
    }
}

