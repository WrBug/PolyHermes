package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.repository.ProcessedTradeRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 已处理交易清理服务
 * 定期清理过期的去重记录
 */
@Service
class ProcessedTradeCleanupService(
    private val processedTradeRepository: ProcessedTradeRepository
) {

    companion object {
        private val logger = LoggerFactory.getLogger(ProcessedTradeCleanupService::class.java)

        // 保留时间：1小时（3600000毫秒）
        // 说明：重复订单通常10秒后就不会再出现，保留10分钟是为了安全起见
        private const val RETENTION_MS = 600_000L

        // 定时清理间隔：10分钟（600000毫秒）
        private const val CLEANUP_INTERVAL_MS = 600_000L
    }

    /**
     * 定时清理过期记录
     * 每10分钟执行一次
     */
    @Scheduled(fixedDelay = CLEANUP_INTERVAL_MS)
    @Transactional
    fun cleanupExpiredProcessedTrades() {
        try {
            val expireTime = System.currentTimeMillis() - RETENTION_MS
            val deletedCount = processedTradeRepository.deleteByProcessedAtBefore(expireTime)

            if (deletedCount > 0) {
                logger.info("清理过期已处理交易记录: deletedCount=$deletedCount, expireTime=$expireTime")
            }
        } catch (e: Exception) {
            logger.error("清理过期已处理交易记录失败", e)
        }
    }
}

